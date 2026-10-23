const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const { InvalidationCoordinator } = load('../../api/events/invalidationCoordinator.ts')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

test('Inbox ready awaits the fresh read after initial load and retries its 503', { timeout: 4000 }, async t => {
    const initial = deferred(), fresh = deferred()
    const manager = new InvalidationCoordinator(() => true, 1, 1)
    const previousWindow = global.window; global.window = new EventTarget()
    let refresh, state, calls = 0, renderer, ready = false
    const { useInboxConversations } = load('list/useInboxConversations.ts', {
        './inboxData': { loadInboxConversations: async () => ++calls === 1 ? initial.promise : calls === 2 ? fresh.promise : { items: [], channels: [] } },
        '../../../api/apiClient': { getApiErrorStatus: error => error.status },
        '../../../api/events/InvalidationContext': { useUiInvalidation: (_key,_enabled,_matches,callback) => { refresh = callback } },
    })
    function Probe() { state = useInboxConversations('workspace', { view: 'MINE' }, true); return null }
    t.after(async () => { manager.close(); await act(async () => renderer?.unmount()); global.window = previousWindow })
    await act(async () => { renderer = create(React.createElement(Probe)) })
    manager.subscribe(() => true, signal => refresh(signal))
    const reconciliation = manager.reconcile().then(() => { ready = true })
    const rejected = assert.rejects(reconciliation, /offline/)
    await act(async () => { initial.resolve({ items: [], channels: [] }); await pause(1) })
    assert.equal(calls, 2); assert.equal(ready, false)
    await act(async () => { fresh.reject(Object.assign(new Error('offline'), { status: 503 })); await rejected })
    assert.equal(state.error, 'offline')
    await act(async () => { await pause(1150) })
    assert.equal(calls, 3); assert.equal(state.error, null)
})

test('aborting Inbox reconciliation cancels its pending initial request and ignores a late response', async t => {
    const initial = deferred(), manager = new InvalidationCoordinator(() => true, 1, 1)
    const previousWindow = global.window; global.window = new EventTarget()
    let refresh, state, signal, calls = 0, renderer
    const { useInboxConversations } = load('list/useInboxConversations.ts', {
        './inboxData': { loadInboxConversations: async (_w,_q,_current,s) => { signal = s; return ++calls === 1 ? initial.promise : { items: [], channels: [] } } },
        '../../../api/apiClient': { getApiErrorStatus: error => error.status },
        '../../../api/events/InvalidationContext': { useUiInvalidation: (_key,_enabled,_matches,callback) => { refresh = callback } },
    })
    function Probe() { state = useInboxConversations('workspace', { view: 'MINE' }, true); return null }
    t.after(async () => { manager.close(); await act(async () => renderer?.unmount()); global.window = previousWindow })
    await act(async () => { renderer = create(React.createElement(Probe)) })
    const initialSignal = signal, attempt = new AbortController()
    manager.subscribe(() => true, s => refresh(s))
    const first = manager.reconcile(attempt.signal), rejected = assert.rejects(first, error => error.name === 'AbortError')
    await act(async () => { await pause(1); attempt.abort(); await rejected })
    assert.equal(initialSignal.aborted, true)
    await act(async () => { await manager.reconcile() })
    assert.equal(calls, 2)
    await act(async () => { initial.resolve({ items: [{ conversation: { id: 'obsolete' } }], channels: [] }); await pause(1) })
    assert.deepEqual(state.items, [])
})

test('attempt cancellation releases message refresh and a late old response cannot overwrite reconnect', async t => {
    // Keep the scheduled retry from racing the explicit reconnect under parallel test load.
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve() }
    const blocked = deferred(), manager = new InvalidationCoordinator(() => true, 1, 1)
    const message = id => ({ id, conversationId: 'conversation', sentAt: '2026-10-22T08:00:00Z', createdAt: '2026-10-22T08:00:00Z', direction: 'INBOUND' })
    const page = id => ({ content: [message(id)], last: true })
    let refresh, state, calls = 0, signal, renderer
    const { useConversationMessages } = load('useConversationMessages.ts', {
        '../../api/messageApi': {
            getConversationMessages: async (_id,_page,_size,s) => { signal = s; calls++; return calls === 2 ? blocked.promise : page(calls === 1 ? 'initial' : 'reconnected') },
        },
        '../../api/apiClient': { getApiErrorStatus: error => error.status },
        '../../api/events/InvalidationContext': { useUiInvalidation: (_key,_enabled,_matches,callback) => { refresh = callback } },
    })
    function Probe() { state = useConversationMessages('conversation'); return null }
    t.after(async () => { manager.close(); await act(async () => renderer?.unmount()) })
    await act(async () => { renderer = create(React.createElement(Probe)); await flush() })
    manager.subscribe(() => true, s => refresh(s))
    const attempt = new AbortController(), first = manager.reconcile(attempt.signal)
    const rejected = assert.rejects(first, error => error.name === 'AbortError')
    await act(async () => { await flush() }); const abortedSignal = signal
    await act(async () => { attempt.abort(); await rejected })
    assert.equal(abortedSignal.aborted, true)
    await act(async () => { await manager.reconcile() })
    assert.equal(calls, 3); assert.ok(state.messages.some(value => value.id === 'reconnected'))
    await act(async () => { blocked.resolve(page('obsolete')); await flush() })
    assert.ok(!state.messages.some(value => value.id === 'obsolete'))
    await act(async () => { t.mock.timers.tick(250); await flush() })
    assert.equal(calls, 3, 'Successful explicit reconnect clears the cancelled attempt retry')
})

test('read API cancellation remains active while response.json is blocked', async () => {
    let requestSignal
    const { searchInbox } = load('../../api/conversationApi.ts', {
        './apiClient': { apiFetch: async (_url, init) => { requestSignal = init.signal; return { json: () => new Promise(() => {}) } } },
    })
    const controller = new AbortController(), request = searchInbox('workspace', {}, controller.signal)
    await pause(1); controller.abort()
    await assert.rejects(request, error => error.name === 'AbortError')
    assert.equal(requestSignal.aborted, true)
})
