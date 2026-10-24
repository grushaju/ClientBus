const assert = require('node:assert/strict')
const { test } = require('node:test')
const load = require('./loadInboxModule.cjs')
const React = require('react')
const { act, create } = require('react-test-renderer')
class Storage {
    data = new Map()
    get length() { return this.data.size }
    key(index) { return [...this.data.keys()][index] ?? null }
    getItem(key) { return this.data.get(key) ?? null }
    setItem(key, value) { this.data.set(key, value) }
    removeItem(key) { this.data.delete(key) }
}
const context = { account: 'org:employee', workspaceId: 'workspace', anchorMessageId: 'anchor' }
const body = { beforeExternalId: 'external-anchor', limit: 100 }
const operation = (status = 'SUCCEEDED', extra = {}) => ({ id: 'operation', conversationId: 'conversation', status,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), deadlineAt: new Date(Date.now() + 120000).toISOString(),
    result: status === 'SUCCEEDED' ? { importedCount: 2, historyStartReached: true } : null,
    errorCode: status === 'FAILED' ? 'IMPORT_FAILED' : null, ...extra })
const response = value => ({ json: async () => value })
const tick = () => new Promise(resolve => setImmediate(resolve))
function setup(t, apiFetch) {
    const prior = { storage: global.sessionStorage, enabled: global.__HISTORY_OPERATIONS_ENABLED__ }
    global.sessionStorage = new Storage()
    global.__HISTORY_OPERATIONS_ENABLED__ = true
    t.after(() => { global.sessionStorage = prior.storage; global.__HISTORY_OPERATIONS_ENABLED__ = prior.enabled })
    const mocks = { './apiClient': { apiFetch } }
    return { api: load('../../api/historySyncApi.ts', mocks), reload: () => load('../../api/historySyncApi.ts', mocks),
        messages: () => load('../../api/messageApi.ts', mocks) }
}
test('lost 202 and reload recover the same already terminal operation by original key', async t => {
    const accepted = new Map(), requests = []
    const fixture = setup(t, async (url, init) => {
        requests.push({ url, key: init.headers['Idempotency-Key'], body: JSON.parse(init.body) })
        accepted.set(init.headers['Idempotency-Key'], operation())
        if (requests.length === 1) throw new TypeError('Connection lost after commit')
        return response(accepted.get(init.headers['Idempotency-Key']))
    })
    await assert.rejects(fixture.api.startHistory('conversation', body, context, new AbortController().signal))
    const key = fixture.api.pendingHistory(context.account, 'conversation').key
    const fresh = fixture.reload()
    const recovered = await fresh.resumeHistory('conversation', context, new AbortController().signal)
    assert.equal(recovered.operation.status, 'SUCCEEDED')
    assert.equal(recovered.key, key)
    assert.equal(accepted.size, 1)
    assert.equal(requests.length, 2)
    assert.deepEqual(requests[0], requests[1])
    fresh.completeHistory(context.account, 'conversation', recovered)
    assert.equal(fresh.pendingHistory(context.account, 'conversation'), null)
})
test('an unresolved submission keeps its original body when the visible anchor changes', async t => {
    const sent = []
    const { api } = setup(t, async (_url, init) => {
        sent.push({ key: init.headers['Idempotency-Key'], body: JSON.parse(init.body) })
        if (sent.length === 1) throw Error('Unavailable')
        return response(operation('FAILED'))
    })
    await assert.rejects(api.startHistory('conversation', body, context, new AbortController().signal))
    const outcome = await api.startHistory('conversation', { beforeExternalId: 'different', limit: 20 }, context, new AbortController().signal)
    assert.equal(outcome.operation.status, 'FAILED')
    assert.deepEqual(sent[0], sent[1])
})
test('disabled client storage prevents submission before any command reaches the server', async t => {
    let sent = 0
    const { api } = setup(t, async () => { sent++; return response(operation()) })
    global.sessionStorage.setItem = () => { throw Error('Storage denied') }
    await assert.rejects(api.startHistory('conversation', body, context, new AbortController().signal), /хранилища/)
    assert.equal(sent, 0)
})
test('SSE invalidation wakes a pending status read; cancelling preserves the retry key', async t => {
    let reads = 0
    const { api } = setup(t, async (_url, init) => {
        if (init.method === 'POST') return response(operation('RUNNING'))
        reads++; return response(operation())
    })
    const controller = new AbortController()
    t.after(() => controller.abort())
    const pending = api.startHistory('conversation', body, context, controller.signal)
    await tick(); api.wakeHistory('conversation')
    assert.equal((await pending).operation.status, 'SUCCEEDED')
    assert.equal(reads, 1)
    const cancelled = api.startHistory('conversation', body, context, controller.signal)
    await tick(); controller.abort()
    await assert.rejects(cancelled, error => error.name === 'AbortError')
    assert.ok(api.pendingHistory(context.account, 'conversation'))
})
test('hidden pages pause status HTTP and resume by invalidation', async t => {
    let reads = 0
    const prior = global.document
    const document = new EventTarget(); document.hidden = true
    global.document = document
    t.after(() => { global.document = prior })
    const { api } = setup(t, async (_url, init) => {
        if (init.method === 'POST') return response(operation('RUNNING'))
        reads++; return response(operation())
    })
    const controller = new AbortController(); t.after(() => controller.abort())
    const pending = api.startHistory('conversation', body, context, controller.signal)
    await tick(); api.wakeHistory('conversation'); await tick()
    assert.equal(reads, 0)
    document.hidden = false; document.dispatchEvent(new Event('visibilitychange'))
    await pending
    assert.equal(reads, 1)
})
test('submission rollback flag still permits recovery of an accepted key', async t => {
    let sent = 0
    const { api } = setup(t, async () => { if (++sent === 1) throw Error('Lost 202'); return response(operation()) })
    await assert.rejects(api.startHistory('conversation', body, context, new AbortController().signal))
    global.__HISTORY_OPERATIONS_ENABLED__ = false
    const result = await api.resumeHistory('conversation', context, new AbortController().signal)
    assert.equal(result.operation.status, 'SUCCEEDED')
    assert.equal(sent, 2)
})
test('expired retry keys require a manual action before generating a new command', async t => {
    let sent = 0
    const { api } = setup(t, async () => { sent++; return response(operation()) })
    global.sessionStorage.setItem('clientbus.history.pending:' + context.account + ':conversation', JSON.stringify({
        key: '11111111-1111-4111-8111-111111111111', body, createdAt: Date.now() - 8 * 86400000, workspaceId: 'workspace' }))
    await assert.rejects(api.resumeHistory('conversation', context, new AbortController().signal), /Срок восстановления/)
    assert.equal(sent, 0)
    const result = await api.startHistory('conversation', body, { ...context, manual: true }, new AbortController().signal)
    assert.notEqual(result.key, '11111111-1111-4111-8111-111111111111')
    assert.equal(sent, 1)
})
test('terminal operation survives a failed cache read and repeats the same POST until cache recovery', async t => {
    let reads = 0; const keys = [], urls = []
    const fixture = setup(t, async (url, init) => {
        urls.push(url)
        if (init.method === 'POST') { keys.push(init.headers['Idempotency-Key']); return response(operation()) }
        if (++reads === 1) throw Error('Cache unavailable')
        return response({ content: [{ id: 'older' }], last: true })
    })
    const api = fixture.messages()
    await assert.rejects(api.syncConversationHistory('conversation', body.beforeExternalId, 100, undefined, context), /Cache unavailable/)
    assert.ok(fixture.api.pendingHistory(context.account, 'conversation'))
    const result = await api.syncConversationHistory('conversation', body.beforeExternalId, 100, undefined, context)
    assert.deepEqual(result.messages, [{ id: 'older' }])
    assert.equal(result.historyStartReached, true)
    assert.equal(result.cacheAnchorMessageId, 'older')
    assert.equal(keys[0], keys[1])
    assert.ok(urls.some(url => url.includes('beforeMessageId=anchor')))
    assert.equal(fixture.api.pendingHistory(context.account, 'conversation'), null)
})
test('a global cached end never hides remaining local pages after reload', async t => {
    const fixture = setup(t, async (_url, init) => init.method === 'POST' ? response(operation())
        : response({ content: [{ id: 'older' }], page: { number: 0, size: 100, totalPages: 8, totalElements: 800 } }))
    const result = await fixture.messages().syncConversationHistory('conversation', body.beforeExternalId, 100, undefined, context)
    assert.equal(result.historyStartReached, false)
    assert.equal(result.cacheHasMore, true)
})
test('failed operations display their partial cache with an inline error', async t => {
    const fixture = setup(t, async (_url, init) => init.method === 'POST' ? response(operation('FAILED'))
        : response({ content: [{ id: 'partial' }], last: true }))
    const result = await fixture.messages().syncConversationHistory('conversation', body.beforeExternalId, 100, undefined, context)
    assert.deepEqual(result.messages, [{ id: 'partial' }])
    assert.match(result.error, /Не удалось полностью/)
    assert.equal(result.historyStartReached, false)
})
test('account scopes isolate pending keys and logout removes history commands', async t => {
    const { api } = setup(t, async () => response(operation()))
    const pending = await api.startHistory('conversation', body, context, new AbortController().signal)
    assert.ok(pending.key)
    assert.equal(api.pendingHistory('other-account', 'conversation'), null)
    const prior = global.localStorage; global.localStorage = new Storage(); t.after(() => { global.localStorage = prior })
    global.sessionStorage.setItem('unrelated', 'keep')
    load('../../auth/authStorage.ts').clearAccessToken()
    assert.equal(api.pendingHistory(context.account, 'conversation'), null)
    assert.equal(global.sessionStorage.getItem('unrelated'), 'keep')
})

test('corrupt pending storage never silently creates a replacement command', async t => {
    let sent = 0
    const { api } = setup(t, async () => { sent++; return response(operation()) })
    global.sessionStorage.setItem('clientbus.history.pending:' + context.account + ':conversation', '{broken')
    await assert.rejects(api.startHistory('conversation', body, context, new AbortController().signal), /восстановить/)
    assert.equal(sent, 0)
    await api.startHistory('conversation', body, { ...context, manual: true }, new AbortController().signal)
    assert.equal(sent, 1)
})

async function mountMessages(t, apiFetch) {
    setup(t, apiFetch)
    const scope = { owner: {}, workspaceId: 'workspace', historyAccount: context.account, accessVersion: 0,
        isCurrent: () => true, managedPolling: true }
    const { useConversationMessages } = load('useConversationMessages.ts', {
        './apiClient': { apiFetch, getApiErrorStatus: error => error.status },
        '../../api/events/uiScope': { useUiScope: () => scope, isAccessEvent: () => false, affectsConversation: () => true },
        '../../api/events/InvalidationContext': { useUiInvalidation: () => undefined },
    })
    let state, renderer
    function Probe() { state = useConversationMessages('conversation', false, undefined, 'workspace'); return null }
    await act(async () => { renderer = create(React.createElement(Probe)); await tick() })
    t.after(() => act(() => renderer.unmount()))
    return { get state() { return state } }
}
const cachedMessage = (id, second) => ({ id, conversationId: 'conversation', externalId: 'external-' + id,
    createdAt: `2026-10-24T08:00:0${second}Z`, sentAt: `2026-10-24T08:00:0${second}Z`, direction: 'INBOUND' })
function foreground(t) {
    const previous = global.document
    const document = new EventTarget(); document.hidden = false
    global.document = document
    t.after(() => { global.document = previous })
    return () => document.dispatchEvent(new Event('visibilitychange'))
}

test('discovery retains the operation ID through a failed status read and terminal completion', async t => {
    let discovery = 0, reads = 0
    const { api, reload } = setup(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        if (url.includes('history-sync-operations?')) { discovery++; return response({ items: [operation('RUNNING')] }) }
        if (++reads === 1) throw Object.assign(Error('Temporary status failure'), { status: 503 })
        return response(operation())
    })
    const following = api.resumeHistory('conversation', context, new AbortController().signal)
    const rejected = assert.rejects(following, /Temporary status failure/)
    await tick(); api.wakeHistory('conversation'); await rejected
    const fresh = reload()
    assert.equal(fresh.pendingHistory(context.account, 'conversation'), null, 'Discovered operations never invent a POST key/body')
    assert.equal(fresh.hasHistoryRecovery(context.account, 'conversation'), true)
    const terminal = await fresh.resumeHistory('conversation', context, new AbortController().signal)
    assert.equal(terminal.operation.status, 'SUCCEEDED')
    assert.equal(discovery, 1); assert.equal(reads, 2)
    fresh.completeHistory(context.account, 'conversation', terminal)
    assert.equal(fresh.hasHistoryRecovery(context.account, 'conversation'), false)
})

test('discovered terminal is retained until the ordinary message cache is successfully read', async t => {
    let discovery = 0, cacheReads = 0, statusReads = 0
    const fixture = setup(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        if (url.includes('history-sync-operations?')) { discovery++; return response({ items: [operation()] }) }
        if (url.startsWith('/api/history-sync-operations/')) { statusReads++; return response(operation()) }
        if (++cacheReads === 1) throw Error('Cache unavailable')
        return response({ content: [cachedMessage('older', 1)], last: true })
    })
    await assert.rejects(fixture.messages().resumeConversationHistory('conversation', context, new AbortController().signal), /Cache unavailable/)
    assert.equal(fixture.api.hasHistoryRecovery(context.account, 'conversation'), true)
    const result = await fixture.messages().resumeConversationHistory('conversation', context, new AbortController().signal)
    assert.equal(result.messages[0].id, 'older'); assert.equal(discovery, 1); assert.equal(statusReads, 1)
    assert.equal(fixture.api.hasHistoryRecovery(context.account, 'conversation'), false)
})

test('rollback still resumes an accepted discovered operation and logout clears its reference', async t => {
    const fixture = setup(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        return response(url.includes('history-sync-operations?') ? { items: [operation()] } : operation())
    })
    await fixture.api.resumeHistory('conversation', context, new AbortController().signal)
    global.__HISTORY_OPERATIONS_ENABLED__ = false
    const terminal = await fixture.reload().resumeHistory('conversation', context, new AbortController().signal)
    assert.equal(terminal.operation.status, 'SUCCEEDED')
    load('../../auth/authStorage.ts').clearHistoryRecovery()
    assert.equal(fixture.api.hasHistoryRecovery(context.account, 'conversation'), false)
})

test('an expired discovered reference requires an explicit new command', async t => {
    let posts = 0
    const { api } = setup(t, async (_url, init) => {
        assert.equal(init.method, 'POST'); posts++; return response(operation())
    })
    sessionStorage.setItem('clientbus.history.pending:' + context.account + ':conversation', JSON.stringify({
        operationId: 'old-operation', createdAt: Date.now() - 8 * 86400000, workspaceId: 'workspace',
    }))
    await assert.rejects(api.resumeHistory('conversation', context, new AbortController().signal), /Срок восстановления/)
    assert.equal(posts, 0)
    const outcome = await api.startHistory('conversation', body, { ...context, manual: true }, new AbortController().signal)
    assert.ok(outcome.key); assert.equal(posts, 1)
})

test('ordinary reconciliation retries failed initial discovery without submitting a new operation', async t => {
    let discoveries = 0
    const ui = await mountMessages(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        if (url.includes('history-sync-operations?')) {
            if (++discoveries === 1) throw Object.assign(Error('Discovery unavailable'), { status: 503 })
            return response({ items: [] })
        }
        return response({ content: [cachedMessage('anchor', 2)], last: true })
    })
    assert.equal(ui.state.error, 'Discovery unavailable')
    await act(async () => ui.state.refreshMessages())
    assert.equal(discoveries, 2); assert.equal(ui.state.error, null)
    assert.equal(ui.state.messages[0].id, 'anchor')
})

test('ordinary reconciliation resumes a discovered operation after a transient status error', async t => {
    const pulse = foreground(t)
    let discoveries = 0, reads = 0
    const ui = await mountMessages(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        if (url.includes('history-sync-operations?')) { discoveries++; return response({ items: [operation('RUNNING')] }) }
        if (url.startsWith('/api/history-sync-operations/')) {
            if (++reads === 1) throw Object.assign(Error('Status unavailable'), { status: 503 })
            return response(operation())
        }
        return response({ content: [url.includes('beforeMessageId') ? cachedMessage('older', 1) : cachedMessage('anchor', 2)], last: true })
    })
    await act(async () => { pulse(); await tick() })
    assert.equal(ui.state.error, 'Status unavailable')
    await act(async () => ui.state.refreshMessages())
    assert.equal(discoveries, 1); assert.equal(reads, 2)
    assert.deepEqual(ui.state.messages.map(message => message.id), ['older', 'anchor'])
    assert.equal(ui.state.error, null)
})

test('an empty initial conversation submits history after failed discovery recovers', async t => {
    let discoveries = 0, posts = 0
    const ui = await mountMessages(t, async (url, init) => {
        if (url.includes('history-sync-operations?')) {
            if (++discoveries === 1) throw Object.assign(Error('Discovery unavailable'), { status: 503 })
            return response({ items: [] })
        }
        if (init.method === 'POST') { posts++; return response(operation('SUCCEEDED', { result: { importedCount: 0, historyStartReached: true } })) }
        return response({ content: [], last: true })
    })
    assert.equal(ui.state.error, 'Discovery unavailable'); assert.equal(posts, 0)
    await act(async () => { await ui.state.refreshMessages(); await tick() })
    assert.equal(discoveries, 2); assert.equal(posts, 1)
    assert.equal(ui.state.historyStartReached, true); assert.equal(ui.state.error, null)
})

test('background history recovery does not occupy the live reconciliation subscription', async t => {
    const pulse = foreground(t)
    let discoveries = 0, cacheReads = 0
    const ui = await mountMessages(t, async (url, init) => {
        assert.notEqual(init.method, 'POST')
        if (url.includes('history-sync-operations?')) {
            if (++discoveries === 1) throw Object.assign(Error('Discovery unavailable'), { status: 503 })
            return response({ items: [operation('RUNNING')] })
        }
        if (url.startsWith('/api/history-sync-operations/')) return response(operation())
        if (url.includes('beforeMessageId')) return response({ content: [cachedMessage('older', 1)], last: true })
        return response({ content: ++cacheReads < 3 ? [cachedMessage('anchor', 2)]
            : [cachedMessage('live', 3), cachedMessage('anchor', 2)], last: true })
    })
    await act(async () => { await ui.state.refreshMessages(); await tick() })
    assert.equal(ui.state.loadingOlder, true)
    await act(async () => ui.state.refreshMessages())
    assert.equal(cacheReads, 3)
    assert.ok(ui.state.messages.some(message => message.id === 'live'))
    await act(async () => { pulse(); await tick() })
    assert.deepEqual(ui.state.messages.map(message => message.id), ['older', 'anchor', 'live'])
    assert.equal(ui.state.error, null); assert.equal(ui.state.loadingOlder, false)
})

for (const source of ['discovery', 'submission']) test(`cache and live updates remain visible while history ${source} is running`, async t => {
    const pulse = foreground(t)
    let cacheReads = 0, statusReads = 0, posts = 0
    const anchor = { ...cachedMessage('anchor', 2), direction: 'OUTBOUND', deliveryStatus: 'SENT' }
    const current = { ...anchor, deliveryStatus: 'READ' }
    const ui = await mountMessages(t, async (url, init) => {
        if (url.includes('history-sync-operations?')) return response({ items: source === 'discovery' ? [operation('RUNNING')] : [] })
        if (init.method === 'POST') { posts++; return response(operation('RUNNING')) }
        if (url.startsWith('/api/history-sync-operations/')) { statusReads++; return response(operation()) }
        if (url.includes('beforeMessageId')) return response({ content: [cachedMessage('older', 1)], last: true })
        return response({ content: ++cacheReads === 1 ? [anchor] : [cachedMessage('live', 3), current], last: true })
    })
    let loading
    if (source === 'submission') await act(async () => { loading = ui.state.loadOlderMessages(); await tick() })
    assert.equal(ui.state.loading, false, 'Already cached conversation is readable')
    assert.equal(ui.state.loadingOlder, true)
    assert.equal(ui.state.messages[0].id, 'anchor')
    await act(async () => ui.state.refreshMessages())
    assert.equal(cacheReads, 2); assert.equal(statusReads, 0, 'Live reconciliation does not create another status tracker')
    assert.deepEqual(ui.state.messages.map(message => message.id), ['anchor', 'live'])
    await act(async () => { pulse(); await tick(); await loading })
    assert.deepEqual(ui.state.messages.map(message => message.id), ['older', 'anchor', 'live'])
    assert.equal(ui.state.messages.find(message => message.id === 'anchor').deliveryStatus, 'READ')
    assert.equal(ui.state.loadingOlder, false); assert.equal(ui.state.error, null)
    assert.equal(posts, source === 'submission' ? 1 : 0)
})

test('hook preserves cached messages when operation discovery is temporarily unavailable', async t => {
    const ui = await mountMessages(t, async url => {
        if (url.includes('history-sync-operations')) throw Object.assign(Error('Discovery unavailable'), { status: 503 })
        return response({ content: [cachedMessage('anchor', 2)], last: true })
    })
    assert.equal(ui.state.messages[0].id, 'anchor')
    assert.equal(ui.state.loading, false)
    assert.equal(ui.state.error, 'Discovery unavailable')
})
test('normal reconciliation recovers a lost history response with the same key and merges the older cache', async t => {
    const keys = []
    const ui = await mountMessages(t, async (url, init) => {
        if (url.includes('history-sync-operations')) return response({ items: [] })
        if (init.method === 'POST') {
            keys.push(init.headers['Idempotency-Key'])
            if (keys.length === 1) throw Error('Lost 202')
            return response(operation())
        }
        return response({ content: [url.includes('beforeMessageId') ? cachedMessage('older', 1) : cachedMessage('anchor', 2)], last: true })
    })
    await act(async () => ui.state.loadOlderMessages())
    assert.equal(ui.state.error, 'Lost 202')
    assert.equal(ui.state.messages.length, 1)
    await act(async () => ui.state.refreshMessages())
    assert.equal(keys.length, 2); assert.equal(keys[0], keys[1])
    assert.deepEqual(ui.state.messages.map(message => message.id), ['older', 'anchor'])
    assert.equal(ui.state.error, null)
    assert.equal(ui.state.refreshError, null)
    assert.equal(ui.state.loadingOlder, false)
})
test('keyset ordering includes createdAt before UUID when sentAt ties', () => {
    const { mergeMessages } = load('useConversationMessages.ts')
    const first = cachedMessage('z', 1), second = cachedMessage('a', 2)
    second.sentAt = first.sentAt
    assert.deepEqual(mergeMessages([], [second, first]).map(message => message.id), ['z', 'a'])
})
test('an old pending outbound never hides imported messages between it and the platform cursor', async t => {
    const pending = { ...cachedMessage('pending', 0), externalId: null, sentAt: null, direction: 'OUTBOUND', deliveryStatus: 'PENDING' }
    let boundary
    const ui = await mountMessages(t, async (url, init) => {
        if (url.includes('history-sync-operations')) return response({ items: [] })
        if (init.method === 'POST') {
            assert.equal(JSON.parse(init.body).beforeExternalId, 'external-anchor')
            return response(operation())
        }
        if (url.includes('beforeMessageId')) { boundary = new URL(url, 'http://test').searchParams.get('beforeMessageId')
            return response({ content: [cachedMessage('older', 1), pending], last: true }) }
        if (/\/api\/messages\/[^/]+$/.test(url)) return response(pending)
        return response({ content: [pending, cachedMessage('anchor', 2)], last: true })
    })
    await act(async () => ui.state.loadOlderMessages())
    assert.equal(boundary, 'anchor')
    assert.deepEqual(ui.state.messages.map(message => message.id), ['pending', 'older', 'anchor'])
})

for (const nextToken of [null, 'other-token']) test(`storage session change to ${nextToken ?? 'logout'} clears this tab's history recovery`, async t => {
    setup(t, async () => { throw Error('Unexpected history request') })
    const previous = { window: global.window, localStorage: global.localStorage }
    global.window = new EventTarget(); global.localStorage = new Storage()
    localStorage.setItem('clientbus.accessToken', 'old-token')
    const key = 'clientbus.history.pending:' + context.account + ':conversation'
    const pending = JSON.stringify({ key: '11111111-1111-4111-8111-111111111111', body, createdAt: Date.now(), workspaceId: 'workspace' })
    sessionStorage.setItem(key, pending); sessionStorage.setItem('unrelated', 'keep')
    const auth = load('../../auth/AuthContext.tsx', {
        '../api/employeeApi': { getCurrentEmployee: async () => ({ id: localStorage.getItem('clientbus.accessToken') === 'old-token' ? 'employee' : 'other',
            organizationId: 'org', role: 'EMPLOYEE', enabled: true }) },
        '../api/employeeWorkspaceApi': { getCurrentEmployeeWorkspaces: async () => [] },
        '../api/organizationApi': { getOrganization: async () => ({ id: 'org' }) },
    })
    let state, renderer
    function Probe() { state = auth.useAuth(); return null }
    t.after(async () => { await act(async () => renderer?.unmount()); Object.assign(global, previous) })
    const changed = () => { const event = new Event('storage'); event.key = 'clientbus.accessToken'; window.dispatchEvent(event) }
    await act(async () => { renderer = create(React.createElement(auth.AuthProvider, {}, React.createElement(Probe))); await tick() })
    assert.equal(state.isAuthenticated, true)
    await act(async () => { changed(); await tick() })
    assert.equal(sessionStorage.getItem(key), pending, 'Reload and unchanged session retain the retry key')
    if (nextToken) localStorage.setItem('clientbus.accessToken', nextToken)
    else localStorage.removeItem('clientbus.accessToken')
    await act(async () => { changed(); await tick() })
    assert.equal(state.isAuthenticated, nextToken !== null)
    assert.equal(localStorage.getItem('clientbus.accessToken'), nextToken, 'Do not revoke the new shared session')
    assert.equal(sessionStorage.getItem(key), null)
    assert.equal(sessionStorage.getItem('unrelated'), 'keep')
})

test('keyset reads progress through a burst of distinct microsecond timestamps', async t => {
    const messages = Array.from({ length: 250 }, (_, index) => ({
        id: index === 249 ? '00000000-0000-0000-0000-000000000000' : 'ffffffff-ffff-ffff-ffff-' + index.toString().padStart(12, '0'),
        conversationId: 'conversation', externalId: String(index), direction: 'INBOUND',
        sentAt: '2026-10-24T08:00:00Z', createdAt: '2026-10-24T08:00:00.' + String(index + 1).padStart(6, '0') + 'Z',
    })).reverse()
    const boundaries = []
    const ui = await mountMessages(t, async url => {
        if (url.includes('history-sync-operations')) return response({ items: [] })
        const anchor = new URL(url, 'http://test').searchParams.get('beforeMessageId')
        boundaries.push(anchor)
        const start = anchor ? messages.findIndex(message => message.id === anchor) + 1 : 0
        const remaining = messages.slice(start)
        return response({ content: remaining.slice(0, 100), last: remaining.length <= 100 })
    })
    await act(async () => ui.state.loadOlderMessages())
    await act(async () => ui.state.loadOlderMessages())
    assert.equal(ui.state.messages.length, 250)
    assert.deepEqual(boundaries, [null, messages[99].id, messages[199].id])
    assert.deepEqual(ui.state.messages.map(message => message.externalId), messages.toReversed().map(message => message.externalId))
})

test('local pending messages cannot replace the server pagination anchor', async t => {
    let boundary
    const ui = await mountMessages(t, async url => {
        if (url.includes('history-sync-operations')) return response({ items: [] })
        if (url.includes('beforeMessageId')) {
            boundary = new URL(url, 'http://test').searchParams.get('beforeMessageId')
            return response({ content: [cachedMessage('older', 1)], last: true })
        }
        return response({ content: [cachedMessage('anchor', 2)], last: false })
    })
    await act(async () => ui.state.acceptMessage({ ...cachedMessage('pending', 0), externalId: null,
        direction: 'OUTBOUND', deliveryStatus: 'PENDING', sentAt: null }))
    await act(async () => ui.state.loadOlderMessages())
    assert.equal(boundary, 'anchor')
    assert.deepEqual(ui.state.messages.map(message => message.id), ['pending', 'older', 'anchor'])
})

test('message ordering retains the submillisecond precision of sentAt', () => {
    const { mergeMessages } = load('useConversationMessages.ts')
    const first = { ...cachedMessage('z', 2), sentAt: '2026-10-24T08:00:00.123001Z' }
    const second = { ...cachedMessage('a', 1), sentAt: '2026-10-24T08:00:00.123002Z' }
    assert.deepEqual(mergeMessages([], [second, first]).map(message => message.id), ['z', 'a'])
})

test('messages with null, missing or invalid timestamps remain sortable without inventing a date', () => {
    const { mergeMessages } = load('useConversationMessages.ts')
    const known = cachedMessage('known', 2)
    for (const value of [null, undefined, '', 'invalid-date']) {
        const pending = { ...cachedMessage('pending', 0), sentAt: value, createdAt: value }
        const merged = mergeMessages([], [pending, known])
        assert.deepEqual(merged.map(message => message.id), ['known', 'pending'])
        assert.equal(merged[1].createdAt, value)
        assert.equal(merged[1].sentAt, value)
    }
})

test('equal sentAt tolerates a null createdAt in a command response', () => {
    const { mergeMessages } = load('useConversationMessages.ts')
    const known = cachedMessage('z-known', 2)
    const pending = { ...cachedMessage('a-pending', 2), createdAt: null }
    assert.deepEqual(mergeMessages([], [pending, known]).map(message => message.id), ['z-known', 'a-pending'])
    const second = { ...pending, id: 'b-pending' }
    assert.deepEqual(mergeMessages([], [second, pending]).map(message => message.id), ['a-pending', 'b-pending'])
})

test('a null-timestamp send response survives in the hook until server reconciliation supplies its time', async t => {
    const anchor = cachedMessage('anchor', 2)
    const pending = { ...cachedMessage('pending', 0), sentAt: null, createdAt: null, externalId: null,
        direction: 'OUTBOUND', deliveryStatus: 'PENDING', processingStatus: 'QUEUED' }
    const saved = { ...pending, ...cachedMessage('pending', 3), direction: 'OUTBOUND', deliveryStatus: 'SENT' }
    let reads = 0
    const ui = await mountMessages(t, async url => {
        if (url.includes('history-sync-operations')) return response({ items: [] })
        return response({ content: ++reads === 1 ? [anchor] : [saved, anchor], last: true })
    })
    await act(async () => ui.state.acceptMessage(pending))
    assert.deepEqual(ui.state.messages.map(message => message.id), ['anchor', 'pending'])
    assert.equal(ui.state.messages[1].createdAt, null)
    await act(async () => ui.state.refreshMessages())
    assert.equal(ui.state.messages[1].createdAt, saved.createdAt)
    assert.equal(ui.state.messages[1].deliveryStatus, 'SENT')
    assert.equal(ui.state.error, null)
})
