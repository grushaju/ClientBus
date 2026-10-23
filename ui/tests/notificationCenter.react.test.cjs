const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const boundary = '2026-10-23T08:00:00.000Z'
const notification = (id = 'notification', overrides = {}) => ({ id, type: 'ASSIGNMENT', workspaceId: 'W-B',
    target: { kind: 'CONVERSATION', id: 'conversation' }, templateArgs: {}, createdAt: '2026-10-23T08:00:01.000Z',
    readAt: null, alertExpiresAt: '2099-10-23T08:10:00.000Z', alertActive: true, ...overrides })

function fixture(t, api = {}, options = {}) {
    const original = Object.fromEntries(['window', 'document', 'localStorage', 'sessionStorage', 'CustomEvent'].map(key => [key, global[key]]))
    global.window = new EventTarget()
    global.document = Object.assign(new EventTarget(), { hidden: false, querySelector: () => null })
    global.CustomEvent = class extends Event { constructor(type, init) { super(type); this.detail = init?.detail } }
    const values = options.values ?? new Map()
    global.localStorage = { getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
    const session = options.session ?? new Map()
    global.sessionStorage = { getItem: key => session.get(key) ?? null,
        setItem: (key, value) => session.set(key, value), removeItem: key => session.delete(key) }
    const invalidation = load('../../api/events/InvalidationContext.tsx')
    const scope = load('../../api/events/uiScope.ts')
    const feed = load('../../api/events/NotificationFeedContext.tsx')
    const storage = load('../notifications/notificationStorage.ts')
    const calls = { summary: 0, list: [], detail: [], read: [], all: [], navigation: [], workspaces: [] }
    const auth = { accessToken: 'A', sessionEpoch: 0, currentEmployee: { id: 'E', organizationId: 'O', role: 'EMPLOYEE' },
        employeeWorkspaces: [], refreshCurrentUser: async () => {}, invalidateIdentity() {}, logout() {}, synchronizeSession() {} }
    const workspace = { currentWorkspace: { id: 'W-A' }, workspaces: [{ id: 'W-A' }, { id: 'W-B' }],
        setCurrentWorkspaceId: id => calls.workspaces.push(id), refreshWorkspaces: async () => {}, invalidateWorkspaces() {} }
    const location = { pathname: '/inbox' }
    const notifications = {
        getNotificationSummary: async signal => { calls.summary++; return api.summary ? api.summary(signal) : { unreadCount: 7 } },
        getNotifications: async (unread, cursor, signal) => { calls.list.push({ unread, cursor, signal });
            return api.list ? api.list(unread, cursor, signal) : { items: [notification()], nextCursor: null } },
        getNotification: async (id, signal) => { calls.detail.push({ id, signal }); return api.detail ? api.detail(id, signal) : notification(id) },
        readNotification: async (id, signal) => { calls.read.push({ id, signal });
            return api.read ? api.read(id, signal) : notification(id, { readAt: boundary, alertActive: false }) },
        readAllNotifications: async (key, signal) => { calls.all.push({ key, signal });
            return api.all ? api.all(key, signal) : { readAt: boundary, updatedCount: 100 } },
    }
    let stream, renderer
    const mocks = {
        './InvalidationContext': invalidation, '../../api/events/InvalidationContext': invalidation,
        './uiScope': scope, '../../api/events/uiScope': scope,
        './NotificationFeedContext': feed, '../../api/events/NotificationFeedContext': feed,
        '../../auth/AuthContext': { useAuth: () => auth }, '../../auth/authStorage': { getAccessToken: () => auth.accessToken },
        '../../workspace/WorkspaceContext': { useWorkspace: () => workspace },
        './uiEventStream': { startUiEventStream: options => { stream = options; return { stop() {} } } },
        '../../api/notificationApi': notifications, './notificationStorage': storage,
        'react-router-dom': { useLocation: () => location, useNavigate: () => route => calls.navigation.push(route) },
        './Notifications.css': {},
    }
    const { UiEventsProvider } = load('../../api/events/UiEventsProvider.tsx', mocks)
    const Center = load('../notifications/NotificationCenter.tsx', mocks).default
    const policy = { jitter: 0, requestSpacingMs: 0, fallbackMs: 120000, fallbackMaxMs: 120000, controlMs: 240000 }
    const tree = () => React.createElement(UiEventsProvider, { policy }, React.createElement(Center))
    const host = { focus() {}, querySelector: () => ({ focus() {} }), querySelectorAll: () => [] }
    t.after(async () => { try { await act(async () => renderer?.unmount()) } finally { Object.assign(global, original) } })
    return { calls, auth, workspace, location, storage, values, get stream() { return stream }, get renderer() { return renderer },
        mount: async () => { await act(async () => { renderer = create(tree(), { createNodeMock: () => host }); await flush() }) },
        update: async () => { await act(async () => { renderer.update(tree()); await flush() }) },
        ready: async () => { await act(async () => { await stream.onReady({ stream: { alertsAfter: boundary } }, new AbortController().signal); await flush() }) },
        event: async event => { await act(async () => { stream.onEvent(event); await flush() }) },
        open: async () => { await act(async () => { renderer.root.findByProps({ className: 'notification-bell' }).props.onClick(); await flush() }) },
    }
}
const classNodes = (ui, name) => ui.renderer.root.findAll(node => node.props.className?.split(' ').includes(name))
const buttonWith = (ui, text) => ui.renderer.root.findAllByType('button').find(node => node.children.join('') === text)

test('center reads all workspaces, refreshes loaded pages, and uses REST count after read', async t => {
    let count = 7
    const ui = fixture(t, {
        summary: async () => ({ unreadCount: count }),
        list: async (unread, cursor) => ({ items: [notification(cursor ? 'second' : 'first')], nextCursor: cursor ? null : 'opaque-cursor' }),
        read: async id => { count = 13; return notification(id, { readAt: boundary, alertActive: false }) },
    })
    await ui.mount()
    assert.equal(ui.calls.summary, 1)
    assert.equal(ui.calls.list.length, 0, 'The closed center does not request a list')
    await ui.open()
    assert.deepEqual(ui.calls.list.map(({ unread, cursor }) => ({ unread, cursor })), [{ unread: false, cursor: null }])
    await act(async () => { buttonWith(ui, '\u041f\u043e\u043a\u0430\u0437\u0430\u0442\u044c \u0435\u0449\u0451').props.onClick(); await flush() })
    assert.equal(classNodes(ui, 'notification-item').length, 2)
    assert.equal(ui.calls.list.at(-1).cursor, 'opaque-cursor')
    await act(async () => { buttonWith(ui, '\u041f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043e').props.onClick(); await flush() })
    assert.match(ui.renderer.root.findByProps({ className: 'notification-bell' }).props['aria-label'], /13/,
        'Read must refetch count rather than subtract one locally')
    await act(async () => { ui.renderer.root.findByType('input').props.onChange({ target: { checked: true } }); await flush() })
    assert.equal(ui.calls.list.at(-1).unread, true)
    assert.equal(ui.calls.list.at(-1).cursor, null, 'Changing the filter starts a fresh cursor chain')
})

test('read-all persists before sending, retries the same key and allocates a new key for a new action', async t => {
    let fail = true
    const ui = fixture(t, { all: async key => {
        assert.equal(ui.storage.pendingReadAll('O:E').key, key, 'A failed response must remain safely retryable after reload')
        if (fail) throw new Error('network')
        return { readAt: boundary, updatedCount: 3 }
    } })
    await ui.mount(); await ui.open()
    const mark = () => buttonWith(ui, '\u041e\u0442\u043c\u0435\u0442\u0438\u0442\u044c \u0432\u0441\u0435 \u043f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043d\u044b\u043c\u0438')
    await act(async () => { await mark().props.onClick(); await flush() })
    const key = ui.calls.all[0].key
    assert.match(key, /^[0-9a-f-]{36}$/)
    assert.equal(ui.storage.pendingReadAll('O:E').key, key)
    fail = false
    await act(async () => { await buttonWith(ui, '\u041f\u043e\u0432\u0442\u043e\u0440\u0438\u0442\u044c \u043e\u0442\u043c\u0435\u0442\u043a\u0443').props.onClick(); await flush() })
    assert.deepEqual(ui.calls.all.map(call => call.key), [key, key])
    assert.equal(ui.storage.pendingReadAll('O:E'), null)
    await act(async () => { await mark().props.onClick(); await flush() })
    assert.notEqual(ui.calls.all[2].key, key)
})

test('read-all retries its captured operation after another tab starts a new command and a notification arrives', async t => {
    const unread = new Set(['old']), snapshots = new Map()
    let loseResponse = true
    const ui = fixture(t, { all: async key => {
        if (!snapshots.has(key)) { snapshots.set(key, { readAt: boundary, updatedCount: unread.size }); unread.clear() }
        if (loseResponse) { loseResponse = false; throw new Error('response lost') }
        return snapshots.get(key)
    } })
    await ui.mount(); await ui.open()
    await act(async () => { buttonWith(ui, 'Отметить все прочитанными').props.onClick(); await flush() })
    const originalKey = ui.calls.all[0].key, firstTab = global.sessionStorage
    const secondTab = new Map()
    global.sessionStorage = { getItem: key => secondTab.get(key) ?? null,
        setItem: (key, value) => secondTab.set(key, value), removeItem: key => secondTab.delete(key) }
    const other = ui.storage.beginReadAll('O:E')
    global.sessionStorage = firstTab
    unread.add('new-arrival')
    await act(async () => { buttonWith(ui, 'Повторить отметку').props.onClick(); await flush() })
    assert.deepEqual(ui.calls.all.map(call => call.key), [originalKey, originalKey])
    assert.equal(unread.has('new-arrival'), true)
    assert.notEqual(other.key, originalKey)
})

test('scope changes abort old list and commands and hide late private results', async t => {
    const oldList = deferred(), oldRead = deferred()
    let holdList = false
    const ui = fixture(t, { list: async () => holdList ? oldList.promise : { items: [notification('old-account')], nextCursor: null },
        read: async () => oldRead.promise })
    await ui.mount(); await ui.open()
    await act(async () => { buttonWith(ui, '\u041f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043e').props.onClick(); await flush() })
    holdList = true
    let ready
    await act(async () => { ready = ui.stream.onReady({ stream: { alertsAfter: boundary } }, new AbortController().signal); await flush() })
    const signal = ui.calls.list.at(-1).signal
    ui.auth.accessToken = 'B'; ui.auth.currentEmployee = { id: 'NEW', organizationId: 'O', role: 'EMPLOYEE' }
    holdList = false
    await ui.update()
    assert.equal(signal.aborted, true)
    assert.equal(ui.calls.read[0].signal.aborted, true)
    await act(async () => { oldList.resolve({ items: [notification('late-secret')], nextCursor: null }); oldRead.resolve(notification('late-secret')); await ready; await flush() })
    assert.doesNotMatch(JSON.stringify(ui.renderer.toJSON()), /late-secret/)
    assert.equal(ui.calls.navigation.length, 0)
})

test('alerts require ready reconciliation and fresh eligible REST detail, with one shared SSE/push claim', async t => {
    let detail = notification('fresh')
    const ui = fixture(t, { detail: async id => ({ ...detail, id }) })
    await ui.mount()
    const created = id => ({ type: 'notifications.created', resource: { kind: 'notification', id } })
    await ui.event(created('before-ready'))
    assert.equal(ui.calls.detail.length, 0)
    await ui.ready()
    for (const changes of [{ createdAt: boundary }, { readAt: boundary }, { alertActive: false }, { alertExpiresAt: '1970-01-01T00:00:00Z' }]) {
        detail = notification('ineligible', changes)
        await ui.event(created(JSON.stringify(changes)))
        assert.equal(classNodes(ui, 'notification-banner').length, 0)
    }
    detail = notification('fresh')
    await ui.event(created('00000000-0000-4000-8000-000000000001'))
    assert.equal(classNodes(ui, 'notification-banner').length, 1)
    await act(async () => { window.dispatchEvent(new CustomEvent('clientbus:foreground-notification', {
        detail: { notificationId: '00000000-0000-4000-8000-000000000001' } })); await flush() })
    assert.equal(classNodes(ui, 'notification-banner').length, 1)
    assert.equal(ui.calls.detail.length, 6, 'Foreground push also reads current ACL/eligibility through REST')
    await act(async () => { document.hidden = true })
    await ui.event(created('hidden'))
    assert.equal(ui.calls.detail.length, 6)
})

test('access invalidation cancels pending alert detail and clears banners even for another workspace', async t => {
    const held = deferred(); let blocked = false
    const ui = fixture(t, { detail: async id => blocked ? held.promise : notification(id) })
    await ui.mount(); await ui.ready()
    await ui.event({ type: 'notifications.created', resource: { kind: 'notification', id: 'visible' } })
    assert.equal(classNodes(ui, 'notification-banner').length, 1)
    blocked = true
    await ui.event({ type: 'notifications.created', resource: { kind: 'notification', id: 'pending' } })
    const signal = ui.calls.detail.at(-1).signal
    await ui.event({ type: 'access.invalidated', workspaceId: 'W-B', resource: { kind: 'workspace', id: 'W-B' } })
    assert.equal(signal.aborted, true)
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
    await act(async () => { held.resolve(notification('pending')); await flush() })
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
})

test('notification invalidation immediately removes obsolete banners and cancels pending detail', async t => {
    const held = deferred(); let blocked = false
    const ui = fixture(t, { detail: async id => blocked ? held.promise : notification(id) })
    await ui.mount(); await ui.ready()
    await ui.event({ type: 'notifications.created', resource: { kind: 'notification', id: 'visible' } })
    assert.equal(classNodes(ui, 'notification-banner').length, 1)
    blocked = true
    await ui.event({ type: 'notifications.created', resource: { kind: 'notification', id: 'pending' } })
    const signal = ui.calls.detail.at(-1).signal
    await ui.event({ type: 'notifications.invalidated' })
    assert.equal(signal.aborted, true)
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
    await act(async () => { held.resolve(notification('pending')); await flush() })
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
})

test('opening a target rechecks detail, reads, switches workspace and uses only the typed target route', async t => {
    const ui = fixture(t, { detail: async id => notification(id, { target: { kind: 'CHANNEL_ACCOUNT', id: 'account/value' },
        templateArgs: { url: 'https://untrusted.invalid/' } }) })
    await ui.mount(); await ui.open()
    await act(async () => { await classNodes(ui, 'notification-target')[0].props.onClick(); await flush() })
    assert.equal(ui.calls.detail.length, 1); assert.equal(ui.calls.read.length, 1)
    assert.deepEqual(ui.calls.workspaces, ['W-B'])
    assert.deepEqual(ui.calls.navigation, ['/channels?accountId=account%2Fvalue'])
    assert.equal(classNodes(ui, 'notification-dialog').length, 0)
})

test('an unavailable target does not navigate or mark a stale list item read', async t => {
    const ui = fixture(t, { detail: async () => { throw Object.assign(new Error('denied'), { status: 404 }) } })
    await ui.mount(); await ui.open()
    await act(async () => { await classNodes(ui, 'notification-target')[0].props.onClick(); await flush() })
    assert.equal(ui.calls.read.length, 0); assert.equal(ui.calls.navigation.length, 0)
    assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 1)
})

test('channel account deep link opens its loaded details in the selected workspace and consumes only its own query', async () => {
    let renderer, replaced, selected
    const channel = { id: 'channel', workspaceId: 'W-B', type: 'TELEGRAM', status: 'ERROR', name: 'Channel',
        account: { id: 'account', displayName: 'Account' } }
    const Details = props => { selected = props.channel; return null }
    const Empty = () => null
    const workspace = { currentWorkspace: { id: 'W-B' } }
    const Page = load('../../pages/ChannelsPage.tsx', {
        'react-router-dom': { useSearchParams: () => {
            const [query, setQuery] = React.useState(new URLSearchParams('accountId=account&keep=value'))
            const set = React.useCallback((next, options) => { replaced = { next, options }; setQuery(next) }, [])
            return [query, set]
        } },
        '../auth/AuthContext': { useAuth: () => ({ isSuperAdmin: false }) },
        '../workspace/WorkspaceContext': { useWorkspace: () => workspace },
        '../api/channelApi': { getWorkspaceChannels: async id => { assert.equal(id, 'W-B'); return [channel] } },
        '../components/channels/ChannelDetailsDialog': { ChannelDetailsDialog: Details },
        '../components/channels/ChannelConnectionDialog': { ChannelConnectionDialog: Empty },
        '../components/channels/CreateChannelDialog': { CreateChannelDialog: Empty },
        '../components/channels/DeleteChannelDialog': { DeleteChannelDialog: Empty },
        '../components/channels/EditChannelDialog': { EditChannelDialog: Empty },
        '../components/channels/ChannelAccountAvatar': { ChannelAccountAvatar: Empty },
        '../components/common/platform/PlatformIcon': { default: Empty },
    }).default
    try {
        await act(async () => { renderer = create(React.createElement(Page)); await flush() })
        assert.equal(selected.id, 'channel')
        assert.equal(replaced.next.has('accountId'), false)
        assert.equal(replaced.next.get('keep'), 'value')
        assert.deepEqual(replaced.options, { replace: true })
    } finally { await act(async () => renderer?.unmount()) }
})
