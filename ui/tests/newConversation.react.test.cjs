const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

const deferred = () => {
    let resolve, reject
    const promise = new Promise((a, b) => { resolve = a; reject = b })
    return { promise, resolve, reject }
}
const channel = id => ({ id, workspaceId: 'workspace', type: 'TELEGRAM', status: 'CONNECTED', name: id,
    account: { id: `${id}-account`, externalId: id, avatarUrl: null } })
const preview = { contentType: 'image/jpeg', base64: 'AQID' }
const discovery = extra => ({ externalId: '123', username: 'contact', phone: '+79990000000',
    displayName: 'Ирина', existingClientAccountId: null, avatarPreview: preview, ...extra })
const text = renderer => JSON.stringify(renderer.toJSON())
const button = (renderer, label) => {
    const found = renderer.root.findAllByType('button').filter(node => node.children.join('') === label)
    assert.equal(found.length, 1, label)
    return found[0]
}
const click = (renderer, label) => act(async () => button(renderer, label).props.onClick())
const type = (renderer, value) => act(async () => renderer.root.findByType('input').props.onChange({ target: { value } }))

async function dialog(t, overrides = {}) {
    const previousWindow = global.window
    const previousCreate = URL.createObjectURL, previousRevoke = URL.revokeObjectURL
    global.window = new EventTarget()
    const blobs = [], revoked = [], searches = [], creates = [], created = []
    URL.createObjectURL = blob => { blobs.push(blob); return `blob:preview-${blobs.length}` }
    URL.revokeObjectURL = value => revoked.push(value)
    const auth = { sessionEpoch: 0 }
    const scopeModule = load('../../api/events/uiScope.ts')
    const owner = {}
    let scope = { owner, workspaceId: 'workspace', accessVersion: 0, isCurrent: () => true }
    let props = { workspaceId: 'workspace', channels: [channel('first'), channel('second')],
        onCreated: id => created.push(id), onClose: () => { closed++ } }
    let closed = 0, renderer
    const Dialog = load('NewConversationDialog.tsx', {
        './uiScope': scopeModule,
        '../../auth/AuthContext': { useAuth: () => auth },
        '../../api/telegramChannelApi': { findTelegramClientAccount: (...args) => {
            searches.push(args)
            return overrides.search ? overrides.search(...args) : Promise.resolve(discovery())
        } },
        '../../api/conversationApi': { createOutboundConversation: (...args) => {
            creates.push(args)
            return overrides.create ? overrides.create(...args) : Promise.resolve({ id: 'conversation' })
        } },
    }).default
    const tree = () => React.createElement(scopeModule.UiScopeContext.Provider, { value: scope }, React.createElement(Dialog, props))
    await act(async () => { renderer = create(tree()) })
    t.after(async () => {
        try { await act(async () => renderer.unmount()) }
        finally { global.window = previousWindow; URL.createObjectURL = previousCreate; URL.revokeObjectURL = previousRevoke }
    })
    return { renderer, blobs, revoked, searches, creates, created, get closed() { return closed },
        update: next => act(async () => { props = { ...props, ...next }; renderer.update(tree()) }),
        scope: () => act(async () => { scope = { ...scope, owner: {} }; renderer.update(tree()) }),
        session: () => act(async () => { auth.sessionEpoch++; renderer.update(tree()) }),
        unmount: () => act(async () => renderer.unmount()),
    }
}

test('phone search shows a temporary raster avatar; creation sends identity only and releases the preview', async t => {
    const ui = await dialog(t)
    await type(ui.renderer, '+7999')
    await click(ui.renderer, 'Найти')
    assert.equal(ui.searches[0][0], 'first-account')
    assert.deepEqual(ui.searches[0][1], { phone: '+7999' })
    assert.ok(ui.searches[0][2] instanceof AbortSignal)
    assert.equal(ui.renderer.root.findByType('img').props.src, 'blob:preview-1')
    assert.equal(ui.blobs[0].type, 'image/jpeg')
    assert.deepEqual([...new Uint8Array(await ui.blobs[0].arrayBuffer())], [1, 2, 3])
    await click(ui.renderer, 'Открыть диалог')
    assert.deepEqual(ui.creates[0][0], { workspaceId: 'workspace', channelAccountId: 'first-account', channelType: 'TELEGRAM',
        externalId: '123', username: 'contact', phone: '+79990000000', displayName: 'Ирина' })
    assert.ok(ui.creates[0][1] instanceof AbortSignal)
    assert.deepEqual(ui.created, ['conversation'])
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
})

test('username search uses the same preview path and a new search revokes it without creating a conversation', async t => {
    const ui = await dialog(t)
    await click(ui.renderer, 'Username')
    await type(ui.renderer, '@contact')
    await click(ui.renderer, 'Найти')
    assert.deepEqual(ui.searches[0][1], { username: '@contact' })
    assert.equal(ui.renderer.root.findAllByType('img').length, 1)
    await click(ui.renderer, 'Новый поиск')
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
    assert.equal(ui.renderer.root.findAllByType('img').length, 0)
    assert.equal(ui.creates.length, 0)
})

test('a failed image falls back to the contact initial and releases its object URL once', async t => {
    const ui = await dialog(t)
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await act(async () => ui.renderer.root.findByType('img').props.onError())
    assert.equal(ui.renderer.root.findAllByType('img').length, 0)
    assert.deepEqual(ui.renderer.root.findByProps({ className: 'new-conversation-result-avatar' }).children, ['И'])
    await click(ui.renderer, 'Отмена')
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
    assert.equal(ui.closed, 1)
})

test('a preview at the backend raw-byte limit is accepted even when Base64 exceeds 256 KiB', async t => {
    const avatarPreview = { ...preview, base64: Buffer.alloc(256 * 1024).toString('base64') }
    const ui = await dialog(t, { search: async () => discovery({ avatarPreview }) })
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    assert.equal(ui.blobs.length, 1)
    assert.equal(ui.blobs[0].size, 256 * 1024)
    assert.equal(ui.renderer.root.findByType('img').props.src, 'blob:preview-1')
})

for (const [name, avatarPreview] of [
    ['missing', null], ['invalid Base64', { ...preview, base64: '%%%' }],
    ['non-raster', { ...preview, contentType: 'image/svg+xml' }],
    ['oversized encoded content', { ...preview, base64: 'A'.repeat(Math.ceil(256 * 1024 / 3) * 4 + 1) }],
    ['oversized decoded content', { ...preview, base64: Buffer.alloc(256 * 1024 + 1).toString('base64') }],
    ['null content', { ...preview, base64: null }],
]) test(`${name} preview keeps search usable with an initial`, async t => {
    const ui = await dialog(t, { search: async () => discovery({ avatarPreview }) })
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    assert.equal(ui.blobs.length, 0)
    assert.deepEqual(ui.renderer.root.findByProps({ className: 'new-conversation-result-avatar' }).children, ['И'])
    assert.equal(button(ui.renderer, 'Открыть диалог').props.disabled, false)
})

test('channel change aborts an old search and its late result cannot replace the next contact', async t => {
    const old = deferred()
    const ui = await dialog(t, { search: (account) => account === 'first-account' ? old.promise
        : Promise.resolve(discovery({ displayName: 'Новый', externalId: '456' })) })
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await act(async () => ui.renderer.root.findByType('select').props.onChange({ target: { value: 'second' } }))
    assert.equal(ui.searches[0][2].aborted, true)
    await click(ui.renderer, 'Найти')
    await act(async () => old.resolve(discovery({ displayName: 'Устаревший' })))
    assert.match(text(ui.renderer), /Новый/)
    assert.doesNotMatch(text(ui.renderer), /Устаревший/)
    await click(ui.renderer, 'Открыть диалог')
    assert.equal(ui.creates[0][0].channelAccountId, 'second-account')
    assert.equal(ui.creates[0][0].externalId, '456')
})

test('editing the search invalidates a pending result and closing aborts the replacement request', async t => {
    const pending = [deferred(), deferred()]
    let count = 0
    const ui = await dialog(t, { search: () => pending[count++].promise })
    await type(ui.renderer, 'old')
    await click(ui.renderer, 'Найти')
    await type(ui.renderer, 'new')
    assert.equal(ui.searches[0][2].aborted, true)
    await click(ui.renderer, 'Найти')
    await click(ui.renderer, 'Отмена')
    assert.equal(ui.searches[1][2].aborted, true)
    await act(async () => { pending[0].resolve(discovery()); pending[1].resolve(discovery()) })
    assert.equal(ui.blobs.length, 0)
    assert.equal(ui.creates.length, 0)
    assert.equal(ui.closed, 1)
})

for (const transition of ['workspace', 'scope', 'session']) test(`${transition} transition releases preview, aborts creation, and ignores its late callback`, async t => {
    const pending = deferred()
    const ui = await dialog(t, { create: () => pending.promise })
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await click(ui.renderer, 'Открыть диалог')
    if (transition === 'workspace') await ui.update({ workspaceId: 'another-workspace' })
    else await ui[transition]()
    assert.equal(ui.creates[0][1].aborted, true)
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
    await act(async () => pending.resolve({ id: 'old-conversation' }))
    assert.deepEqual(ui.created, [])
    assert.equal(ui.renderer.root.findAllByType('img').length, 0)
    assert.equal(button(ui.renderer, 'Найти').props.disabled, false)
})

test('duplicate create clicks produce one request; a failure retains a usable preview for retry', async t => {
    const pending = deferred()
    const ui = await dialog(t, { create: () => pending.promise })
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await act(async () => {
        const send = button(ui.renderer, 'Открыть диалог').props.onClick
        send(); send()
    })
    assert.equal(ui.creates.length, 1)
    await act(async () => pending.reject(Error('Creation unavailable')))
    assert.match(text(ui.renderer), /Creation unavailable/)
    assert.equal(ui.renderer.root.findByType('img').props.src, 'blob:preview-1')
    assert.equal(button(ui.renderer, 'Открыть диалог').props.disabled, false)
    assert.deepEqual(ui.revoked, [])
})

test('removing the selected channel clears its preview before selecting the remaining connected channel', async t => {
    const ui = await dialog(t)
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await ui.update({ channels: [channel('second')] })
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
    assert.equal(ui.renderer.root.findAllByType('img').length, 0)
    await click(ui.renderer, 'Найти')
    assert.equal(ui.searches[1][0], 'second-account')
})

test('unmount releases a search preview and prevents any persistence request', async t => {
    const ui = await dialog(t)
    await type(ui.renderer, '123')
    await click(ui.renderer, 'Найти')
    await ui.unmount()
    assert.deepEqual(ui.revoked, ['blob:preview-1'])
    assert.equal(ui.creates.length, 0)
})

test('search and outbound API adapters pass AbortSignal without adding avatar content to the creation body', async () => {
    const calls = []
    const api = { apiFetch: async (...args) => { calls.push(args); return { json: async () => ({}) } } }
    const signal = new AbortController().signal
    const search = load('../../api/telegramChannelApi.ts', { './apiClient': api })
    const conversations = load('../../api/conversationApi.ts', { './apiClient': api })
    await search.findTelegramClientAccount('channel', { username: '@contact' }, signal)
    assert.deepEqual(calls[0], ['/api/channels/channel/telegram/find?username=contact', { signal, cache: 'no-store' }])
    await conversations.createOutboundConversation({ workspaceId: 'workspace', externalId: '123' }, signal)
    assert.equal(calls[1][1].signal, signal)
    assert.deepEqual(JSON.parse(calls[1][1].body), { workspaceId: 'workspace', externalId: '123' })
})
