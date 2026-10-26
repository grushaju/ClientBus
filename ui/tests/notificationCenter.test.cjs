const { test } = require('node:test')
const assert = require('node:assert/strict')
const load = require('./loadInboxModule.cjs')

test('notification presentation handles unnamed contacts, missing metadata and all channel types', () => {
    const { notificationContact, notificationSource } = load('../notifications/notificationPresentation.ts')
    const item = { target: { kind: 'CONVERSATION', id: 'technical-id' }, templateArgs: {} }
    assert.equal(notificationContact(item), 'Контакт без имени')
    assert.equal(notificationSource(item), null)
    assert.equal(notificationContact({ ...item, templateArgs: null }), 'Контакт без имени')
    assert.equal(notificationSource({ ...item, templateArgs: null }), null)
    assert.equal(notificationContact({ ...item, target: { kind: 'CHANNEL_ACCOUNT' } }), null)
    assert.equal(notificationSource({ ...item, templateArgs: { channelType: 'WHATSAPP', channelName: ' Продажи ' } }), 'WhatsApp · Продажи')
    assert.equal(notificationSource({ ...item, templateArgs: { channelType: 'FUTURE', channelName: 'Канал' } }), 'Канал')
    assert.equal(notificationContact({ ...item, templateArgs: { senderName: '<b>Анна</b>' } }), '<b>Анна</b>')
})

function storageEnvironment(t) {
    const previous = global.localStorage
    const previousSession = global.sessionStorage
    const values = new Map()
    global.localStorage = { getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
    const session = new Map()
    global.sessionStorage = { getItem: key => session.get(key) ?? null,
        setItem: (key, value) => session.set(key, value), removeItem: key => session.delete(key) }
    t.after(() => { global.localStorage = previous; global.sessionStorage = previousSession })
    return values
}

test('alert dedup is account scoped, survives a new module, expires after 48 hours and stays bounded', t => {
    const values = storageEnvironment(t)
    const storage = load('../notifications/notificationStorage.ts')
    const now = Date.now()
    assert.equal(storage.notificationAccount('org:x', 'employee:y'), 'org%3Ax:employee%3Ay')
    assert.equal(storage.claimNotificationAlert('A', 'same', now), true)
    assert.equal(storage.claimNotificationAlert('A', 'same', now), false)
    assert.equal(storage.claimNotificationAlert('B', 'same', now), true)
    assert.equal(load('../notifications/notificationStorage.ts').claimNotificationAlert('A', 'same', now), false)
    assert.equal(storage.claimNotificationAlert('A', 'same', now + 48 * 60 * 60 * 1000), true)
    // Seed one opaque bounded list so the final boundary check stays fast.
    values.set('clientbus:notifications:alerts:A', JSON.stringify(Array.from({ length: 4096 }, (_, i) => ({ id: 'id-' + i, expiresAt: now + 1000 }))))
    assert.equal(storage.claimNotificationAlert('A', 'newest', now), true)
    const persisted = JSON.parse(values.get('clientbus:notifications:alerts:A'))
    assert.equal(persisted.length, 4096)
    assert.equal(persisted[0].id, 'id-1')
    assert.equal(persisted.at(-1).id, 'newest')
    assert.deepEqual(Object.keys(persisted[0]).sort(), ['expiresAt', 'id'], 'No content or credentials may be persisted')
    storage.clearNotificationAlertDedup('A')
    assert.equal(values.has('clientbus:notifications:alerts:A'), false)
    assert.equal(storage.claimNotificationAlert('A', 'newest', now), true)
    assert.equal(storage.claimNotificationAlert('B', 'same', now), false, 'Logout cleanup does not affect another account')
})

test('blocked or corrupt local storage retains in-session alert protection', t => {
    const values = storageEnvironment(t)
    values.set('clientbus:notifications:alerts:A', '{invalid')
    const storage = load('../notifications/notificationStorage.ts')
    assert.equal(storage.claimNotificationAlert('A', 'notification'), true)
    global.localStorage = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('quota') }, removeItem() { throw new Error('blocked') } }
    assert.equal(storage.claimNotificationAlert('A', 'notification'), false)
    assert.doesNotThrow(() => storage.clearNotificationAlertDedup('A'))
    assert.equal(storage.claimNotificationAlert('A', 'notification'), true)
})

test('clear retry is isolated from read-all and other accounts, survives reload and expires after seven days', t => {
    storageEnvironment(t)
    const storage = load('../notifications/notificationStorage.ts'), now = Date.now()
    const read = storage.beginReadAll('A', now), first = storage.beginClear('A', now), other = storage.beginClear('B', now)
    assert.deepEqual(load('../notifications/notificationStorage.ts').pendingClear('A', now), first)
    const second = storage.beginClear('A', now)
    storage.completeClear('A', first.key)
    assert.equal(storage.pendingClear('A', now).key, second.key)
    assert.equal(storage.pendingReadAll('A', now).key, read.key); assert.equal(storage.pendingClear('B', now).key, other.key)
    assert.equal(storage.pendingClear('A', now + 7 * 24 * 60 * 60 * 1000), null)
    global.sessionStorage.setItem = () => { throw Error('storage unavailable') }
    assert.throws(() => storage.beginClear('A'), /storage unavailable/)
})

test('read-all command survives reload for seven days and completing an old response preserves a newer command', t => {
    storageEnvironment(t)
    const storage = load('../notifications/notificationStorage.ts')
    const now = Date.now()
    const first = storage.beginReadAll('A', now)
    assert.deepEqual(load('../notifications/notificationStorage.ts').pendingReadAll('A', now), first)
    const second = storage.beginReadAll('A', now)
    assert.notEqual(first.key, second.key)
    storage.completeReadAll('A', first.key)
    assert.equal(storage.pendingReadAll('A', now).key, second.key)
    assert.equal(storage.pendingReadAll('B', now), null)
    assert.equal(storage.pendingReadAll('A', now + 7 * 24 * 60 * 60 * 1000 - 1).key, second.key)
    assert.equal(storage.pendingReadAll('A', now + 7 * 24 * 60 * 60 * 1000), null)
})

test('read-all commands in active tabs and a duplicated tab retain independent retry keys after reload', t => {
    storageEnvironment(t)
    const storage = load('../notifications/notificationStorage.ts')
    const firstTab = global.sessionStorage
    const first = storage.beginReadAll('A')
    const values = new Map([['clientbus:notifications:read-all:A', JSON.stringify(first)]])
    global.sessionStorage = { getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
    const second = storage.beginReadAll('A')
    assert.notEqual(first.key, second.key)
    assert.equal(load('../notifications/notificationStorage.ts').pendingReadAll('A').key, second.key)
    global.sessionStorage = firstTab
    assert.equal(load('../notifications/notificationStorage.ts').pendingReadAll('A').key, first.key)
    storage.completeReadAll('A', first.key)
    global.sessionStorage = { getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
    assert.equal(storage.pendingReadAll('A').key, second.key)
})

test('read-all must persist before the caller can send a command', t => {
    storageEnvironment(t)
    const storage = load('../notifications/notificationStorage.ts')
    global.localStorage.setItem = global.sessionStorage.setItem = () => { throw new Error('storage unavailable') }
    assert.throws(() => storage.beginReadAll('A'), /storage unavailable/)
    assert.equal(storage.pendingReadAll('A'), null)
})

test('actual logout clears the account alert dedup without deleting an unconfirmed read-all retry', async t => {
    const React = require('react'), { act, create } = require('react-test-renderer')
    storageEnvironment(t)
    const previous = global.window
    global.window = new EventTarget()
    t.after(() => { global.window = previous })
    const storage = load('../notifications/notificationStorage.ts')
    storage.claimNotificationAlert('O:E', 'shown')
    const command = storage.beginReadAll('O:E')
    let token = 'A', auth, renderer
    const module = load('../../auth/AuthContext.tsx', {
        './authStorage': { getAccessToken: () => token, clearAccessToken: () => { token = null }, clearHistoryRecovery: () => {} },
        '../api/employeeApi': { getCurrentEmployee: async () => ({ id: 'E', organizationId: 'O', enabled: true }) },
        '../api/employeeWorkspaceApi': { getCurrentEmployeeWorkspaces: async () => [] },
        '../api/organizationApi': { getOrganization: async () => ({ id: 'O' }) },
        '../components/notifications/notificationStorage': storage,
    })
    function Probe() { auth = module.useAuth(); return null }
    try {
        await act(async () => { renderer = create(React.createElement(module.AuthProvider, null, React.createElement(Probe))) })
        assert.equal(auth.currentEmployee.id, 'E')
        await act(async () => auth.logout())
        assert.equal(storage.claimNotificationAlert('O:E', 'shown'), true, 'Logout clears both memory and persistent dedup')
        assert.equal(storage.pendingReadAll('O:E').key, command.key, 'A seven-day uncertain command retains its original retry key')
    } finally { await act(async () => renderer?.unmount()) }
})

test('feed gates alerts on successful reconciliation, rejects stale completion and resets on access', () => {
    const { NotificationFeed } = load('../../api/events/NotificationFeedContext.tsx')
    const feed = new NotificationFeed(), seen = []
    feed.subscribe(event => seen.push(event))
    feed.created('before')
    const first = feed.begin('2026-10-23T08:00:00Z')
    feed.health(true); feed.created('during')
    feed.finish(first, false); feed.created('failed')
    const second = feed.begin('2026-10-23T09:00:00Z')
    feed.finish(first, true); feed.created('obsolete')
    feed.finish(second, true); feed.created('fresh', 'push')
    assert.deepEqual(seen.filter(event => event.kind === 'created'), [
        { kind: 'created', id: 'fresh', source: 'push', alertsAfter: Date.parse('2026-10-23T09:00:00Z') },
    ])
    feed.invalidateAlerts(); feed.created('access-pending')
    feed.health(true); feed.created('after-control')
    assert.equal(seen.filter(event => event.kind === 'created').length, 2)
    feed.reset(); feed.health(true); feed.created('after-close')
    assert.equal(seen.filter(event => event.kind === 'created').length, 2)
    const invalid = feed.begin('invalid')
    feed.finish(invalid, true); feed.created('invalid-boundary')
    assert.equal(seen.filter(event => event.kind === 'created').length, 2)
})

test('API uses all-workspace routes, forwards opaque cursor and sends read-all key with no body', async t => {
    const budget = load('../../api/events/requestBudget.ts')
    const { apiFetch } = load('../../api/apiClient.ts', { './events/requestBudget': budget, '../auth/authStorage': { getAccessToken: () => 'token' } })
    const api = load('../../api/notificationApi.ts', { './apiClient': { apiFetch }, '../auth/authStorage': { getAccessToken: () => 'token' }, './requestBudget': budget })
    const calls = []
    t.mock.method(global, 'fetch', async (url, init) => { calls.push({ url, init }); return new Response('{}') })
    const controller = new AbortController(), limiter = new budget.RequestBudget(2, 8, 0)
    budget.bindRequestBudget(controller.signal, limiter)
    try {
        await api.getNotifications(true, 'opaque +/?', controller.signal)
        await api.getNotificationSummary(controller.signal)
        await api.getNotification('id/value', controller.signal)
        await api.readNotification('id/value', controller.signal)
        await api.readAllNotifications('00000000-0000-4000-8000-000000000001', controller.signal)
        await api.clearNotifications('00000000-0000-4000-8000-000000000002', controller.signal)
        const url = new URL(calls[0].url, 'https://clientbus.test')
        assert.equal(url.searchParams.get('unreadOnly'), 'true')
        assert.equal(url.searchParams.get('limit'), '30')
        assert.equal(url.searchParams.get('pageCursor'), 'opaque +/?')
        assert.equal(url.searchParams.has('workspaceId'), false)
        assert.equal(calls[1].url, '/api/notifications/summary')
        assert.equal(calls[2].url, '/api/notifications/id%2Fvalue')
        assert.equal(calls[3].url, '/api/notifications/id%2Fvalue/read')
        assert.equal(calls[4].init.method, 'PUT')
        assert.equal(new Headers(calls[4].init.headers).get('Idempotency-Key'), '00000000-0000-4000-8000-000000000001')
        assert.equal(calls[4].init.body, undefined)
        assert.equal(calls[5].url, '/api/notifications')
        assert.equal(calls[5].init.method, 'DELETE')
        assert.equal(new Headers(calls[5].init.headers).get('Idempotency-Key'), '00000000-0000-4000-8000-000000000002')
        assert.equal(calls[5].init.body, undefined)
        assert.equal(limiter.snapshot().started, 3, 'Only real GETs consume the recovery budget')
        assert.equal(limiter.snapshot().active, 0, 'Every body is consumed before releasing its permit')
    } finally { limiter.close() }
})

test('visible incoming conversation is suppressed only when its content is visible on desktop or mobile', t => {
    const previous = { window: global.window, document: global.document }
    t.after(() => Object.assign(global, previous))
    let mobile = false, listVisible = false, conversationVisible = true
    global.window = { matchMedia: () => ({ matches: mobile }) }
    global.document = { hidden: false, querySelector: selector => selector === '.conversation-view' ? conversationVisible
        : selector === '.inbox-layout[data-mobile-list="true"]' ? listVisible : null }
    const { isVisibleNotificationConversation, notificationRoute } = load('../notifications/notificationPresentation.ts')
    const item = { type: 'INCOMING_MESSAGE', target: { kind: 'CONVERSATION', id: 'conversation' } }
    assert.equal(isVisibleNotificationConversation(item, '/inbox/conversation'), true)
    mobile = true; listVisible = true
    assert.equal(isVisibleNotificationConversation(item, '/inbox/conversation'), false)
    listVisible = false
    assert.equal(isVisibleNotificationConversation(item, '/inbox/conversation'), true)
    conversationVisible = false
    assert.equal(isVisibleNotificationConversation(item, '/inbox/conversation'), false)
    assert.equal(notificationRoute({ target: { kind: 'HISTORY_OPERATION', id: 'operation' } }), null,
        'The future history operation must not invent a route in this stage')
})
