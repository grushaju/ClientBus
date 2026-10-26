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
    const audio = { contexts: [], played: 0, closed: 0 }
    if (options.audio) window.AudioContext = class {
        constructor() { this.state = 'suspended'; this.currentTime = 0; audio.contexts.push(this) }
        async resume() { if (options.rejectAudio) throw new Error('autoplay'); this.state = 'running'; this.onstatechange?.() }
        async close() { this.state = 'closed'; audio.closed++ }
        createOscillator() { return { frequency: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {},
            disconnect() {}, start() { audio.played++ }, stop() {} } }
        createGain() { return { gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {} } }
    }
    global.document = Object.assign(new EventTarget(), { hidden: false, querySelector: () => null })
    const gestures = {}
    if (options.captureGestures) {
        const add = document.addEventListener.bind(document)
        document.addEventListener = (type, listener, ...args) => {
            if (['pointerdown', 'keydown'].includes(type)) gestures[type] = listener
            add(type, listener, ...args)
        }
    }
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
    const calls = { summary: 0, list: [], detail: [], read: [], all: [], clears: [], preferences: [], writes: [], navigation: [], workspaces: [] }
    const auth = { accessToken: 'A', sessionEpoch: 0, currentEmployee: { id: 'E', organizationId: 'O', role: 'EMPLOYEE' },
        employeeWorkspaces: [], refreshCurrentUser: async () => {}, invalidateIdentity() {}, logout() {}, synchronizeSession() {} }
    const workspace = { currentWorkspace: { id: 'W-A' }, workspaces: [{ id: 'W-A' }, { id: 'W-B' }],
        setCurrentWorkspaceId: id => calls.workspaces.push(id), refreshWorkspaces: async () => {}, invalidateWorkspaces() {} }
    const location = { pathname: '/inbox' }
    const notifications = {
        getNotificationPreferences: async signal => { calls.preferences.push({ signal }); return api.preferences ? api.preferences(signal) : { soundEnabled: true } },
        updateNotificationPreferences: async (soundEnabled, signal) => { calls.writes.push({ soundEnabled, signal });
            return api.savePreferences ? api.savePreferences(soundEnabled, signal) : { soundEnabled } },
        getNotificationSummary: async signal => { calls.summary++; return api.summary ? api.summary(signal) : { unreadCount: 7 } },
        getNotifications: async (unread, cursor, signal) => { calls.list.push({ unread, cursor, signal });
            return api.list ? api.list(unread, cursor, signal) : { items: [notification()], nextCursor: null } },
        getNotification: async (id, signal) => { calls.detail.push({ id, signal }); return api.detail ? api.detail(id, signal) : notification(id) },
        readNotification: async (id, signal) => { calls.read.push({ id, signal });
            return api.read ? api.read(id, signal) : notification(id, { readAt: boundary, alertActive: false }) },
        readAllNotifications: async (key, signal) => { calls.all.push({ key, signal });
            return api.all ? api.all(key, signal) : { readAt: boundary, updatedCount: 100 } },
        clearNotifications: async (key, signal) => { calls.clears.push({ key, signal });
            return api.clear ? api.clear(key, signal) : { clearedAt: boundary, deletedCount: 3 } },
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
    return { calls, auth, workspace, location, storage, values, audio, gestures, get stream() { return stream }, get renderer() { return renderer },
        mount: async () => { await act(async () => { renderer = create(tree(), { createNodeMock: () => host }); await flush() }) },
        update: async () => { await act(async () => { renderer.update(tree()); await flush() }) },
        ready: async () => { await act(async () => { await stream.onReady({ stream: { alertsAfter: boundary } }, new AbortController().signal); await flush() }) },
        event: async event => { await act(async () => { stream.onEvent(event); await flush() }) },
        open: async () => { await act(async () => { renderer.root.findByProps({ className: 'notification-bell' }).props.onClick(); await flush() }) },
    }
}
const classNodes = (ui, name) => ui.renderer.root.findAll(node => node.props.className?.split(' ').includes(name))
const buttonWith = (ui, text) => ui.renderer.root.findAllByType('button').find(node => node.children.join('') === text)
const created = id => ({ type: 'notifications.created', resource: { kind: 'notification', id } })
const checkSound = async ui => act(async () => { buttonWith(ui, 'Проверить звук').props.onClick(); await flush() })

test('center and live banner show contact and channel from REST without per-row detail requests', async t => {
    const item = notification('incoming', { type: 'INCOMING_MESSAGE',
        templateArgs: { senderName: 'Анна Петрова', channelType: 'TELEGRAM', channelName: 'Поддержка' } })
    const ui = fixture(t, { list: async () => ({ items: [item], nextCursor: null }), detail: async () => item })
    await ui.mount(); await ui.ready(); await ui.open()
    assert.equal(classNodes(ui, 'notification-sender')[0].children.join(''), 'От: Анна Петрова')
    assert.equal(classNodes(ui, 'notification-source')[0].children.join(''), 'Telegram · Поддержка')
    assert.equal(ui.calls.detail.length, 0, 'List presentation does not cause an extra request for every row')
    await ui.event(created(item.id))
    const banner = classNodes(ui, 'notification-banner')[0]
    assert.equal(banner.findByProps({ className: 'notification-sender' }).children.join(''), 'От: Анна Петрова')
    assert.equal(banner.findByProps({ className: 'notification-source' }).children.join(''), 'Telegram · Поддержка')
    assert.equal(ui.calls.detail.length, 1, 'The existing live-alert ACL read supplies banner presentation too')
})

test('fresh authorized notifications sound once across SSE/push after activation; refresh never plays', async t => {
    const ui = fixture(t, {}, { audio: true })
    await ui.mount(); await ui.ready()
    assert.equal(ui.audio.contexts.length, 0, 'Initial page load must not create an AudioContext')
    await ui.event(created('before-gesture'))
    assert.equal(ui.audio.played, 0); assert.equal(classNodes(ui, 'notification-banner').length, 1)
    await ui.open(); await checkSound(ui)
    assert.equal(ui.audio.played, 1, 'The explicit test plays one preview tone')
    const id = '00000000-0000-4000-8000-000000000025'
    await ui.event(created(id)); assert.equal(ui.audio.played, 2)
    await act(async () => { window.dispatchEvent(new CustomEvent('clientbus:foreground-notification', { detail: { notificationId: id } })); await flush() })
    assert.equal(ui.audio.played, 2)
    await ui.ready(); await ui.event({ type: 'notifications.invalidated' })
    assert.equal(ui.audio.played, 2)
})

test('employee mute preserves visual banners, and saving the enabled preference uses its server result', async t => {
    let enabled = false
    const ui = fixture(t, { preferences: async () => ({ soundEnabled: enabled }),
        savePreferences: async value => ({ soundEnabled: enabled = value }) }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    await ui.event(created('muted'))
    assert.equal(ui.audio.played, 1); assert.equal(classNodes(ui, 'notification-banner').length, 1)
    await act(async () => { ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.onChange({ target: { checked: true } }); await flush() })
    assert.deepEqual(ui.calls.writes.map(value => value.soundEnabled), [true])
    await ui.event(created('enabled'))
    assert.equal(ui.audio.played, 2)
})

test('enabling sound while muted synchronously unlocks from the checkbox without a test tone or second gesture', async t => {
    let enabled = false
    const ui = fixture(t, { preferences: async () => ({ soundEnabled: enabled }),
        savePreferences: async value => ({ soundEnabled: enabled = value }) }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open()
    assert.equal(ui.audio.contexts.length, 0)
    await act(async () => {
        ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.onChange({ target: { checked: true } })
        assert.equal(ui.audio.contexts.length, 1, 'Resume starts inside the checkbox gesture, before persistence')
        await flush()
    })
    assert.equal(ui.audio.played, 0)
    await ui.event(created('enabled-with-checkbox'))
    assert.equal(ui.audio.played, 1)
})

test('workspace changes cancel old alert work while preserving the session audio context and gesture unlock', async t => {
    const held = deferred()
    const ui = fixture(t, { detail: async id => id === 'old-workspace' ? held.promise : notification(id) }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    await ui.event(created('old-workspace'))
    const oldSignal = ui.calls.detail.at(-1).signal
    ui.workspace.currentWorkspace = { id: 'W-B' }
    await ui.update(); await ui.ready()
    assert.equal(oldSignal.aborted, true)
    assert.equal(ui.audio.contexts.length, 1); assert.equal(ui.audio.closed, 0)
    await act(async () => { held.resolve(notification('old-workspace')); await flush() })
    assert.equal(ui.audio.played, 1, 'The old workspace result cannot produce a tone')
    await ui.event(created('new-workspace'))
    assert.equal(ui.audio.played, 2, 'No new pointer/key gesture is necessary after a workspace switch')
})

test('read, expired, pre-boundary and denied details produce no sound', async t => {
    let detail = notification()
    const ui = fixture(t, { detail: async id => { if (id === 'denied') throw Object.assign(new Error('denied'), { status: 404 }); return { ...detail, id } } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    for (const [id, changes] of [['read', { readAt: boundary }], ['old', { createdAt: boundary }],
        ['inactive', { alertActive: false }], ['expired', { alertExpiresAt: boundary }], ['denied', {}]]) {
        detail = notification(id, changes); await ui.event(created(id))
    }
    assert.equal(ui.audio.played, 1); assert.equal(classNodes(ui, 'notification-banner').length, 0)
})

test('background eligible notifications use at most two immediate ACL reads, with no hidden banner or queued burst', async t => {
    const held = deferred()
    const ui = fixture(t, { detail: async id => { await held.promise; return notification(id) } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    document.hidden = true
    await ui.event(created('hidden-one')); await ui.event(created('hidden-two')); await ui.event(created('hidden-drop'))
    assert.equal(ui.calls.detail.length, 2)
    await act(async () => { held.resolve(); await flush() })
    assert.equal(ui.audio.played, 2, 'The burst coalesces to one background tone')
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
    assert.equal(ui.calls.detail.length, 2, 'No dropped hidden event is queued for later')
})

test('background sound recovers after another session reads one or all notifications', async t => {
    let now = Date.now()
    t.mock.method(Date, 'now', () => now)
    const ui = fixture(t, {}, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    document.hidden = true
    for (const resource of [{ kind: 'notification', id: 'read-one' }, { kind: 'employee', id: 'read-all' }]) {
        now += 3000
        await ui.event({ type: 'notifications.invalidated', resource })
        await ui.event(created(resource.id + '-new'))
    }
    assert.equal(ui.audio.played, 3)
    assert.equal(ui.calls.detail.length, 2)
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
    assert.equal(document.hidden, true, 'Recovery does not require a visibility change')
})

test('hidden preference recovery is shared, applies a remote mute, and obeys Retry-After', async t => {
    const held = deferred()
    let now = Date.now(), muted = true, limited = false, hold = true
    t.mock.method(Date, 'now', () => now)
    const ui = fixture(t, { preferences: async () => {
        if (!document.hidden) return { soundEnabled: true }
        if (limited) throw Object.assign(new Error('limited'), { retryAfterMs: 60000 })
        if (hold) await held.promise
        return { soundEnabled: !muted }
    } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    const before = ui.calls.preferences.length
    document.hidden = true
    await ui.event({ type: 'notifications.invalidated' })
    await ui.event(created('muted-one')); await ui.event(created('muted-two')); await ui.event(created('dropped'))
    assert.equal(ui.calls.preferences.length, before + 1, 'A burst shares one hidden preference request')
    await act(async () => { hold = false; held.resolve(); await flush() })
    assert.equal(ui.audio.played, 1); assert.equal(ui.calls.detail.length, 0)
    muted = false; limited = true
    await ui.event({ type: 'notifications.invalidated' }); await ui.event(created('limited'))
    const limitedCalls = ui.calls.preferences.length
    await ui.event(created('no-early-retry'))
    assert.equal(ui.calls.preferences.length, limitedCalls)
    now += 60000; limited = false
    await ui.event(created('after-backoff'))
    assert.equal(ui.audio.played, 2); assert.equal(ui.calls.detail.length, 1)
})

test('logout fences a delayed hidden preference response and closes its request', async t => {
    const held = deferred()
    const ui = fixture(t, { preferences: async () => document.hidden ? held.promise : { soundEnabled: true } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    document.hidden = true
    await ui.event({ type: 'notifications.invalidated' }); await ui.event(created('pending-preference'))
    const signal = ui.calls.preferences.at(-1).signal
    ui.auth.accessToken = null; ui.auth.currentEmployee = null
    await ui.update()
    assert.equal(signal.aborted, true)
    await act(async () => { held.resolve({ soundEnabled: true }); await flush() })
    assert.equal(ui.audio.played, 1); assert.equal(ui.calls.detail.length, 0)
})

test('a stale foreground preference response cannot clear hidden Retry-After or re-enable sound', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    let now = Date.now(), hold = false
    t.mock.method(Date, 'now', () => now)
    const old = deferred()
    const ui = fixture(t, { preferences: async () => {
        if (document.hidden) throw Object.assign(new Error('limited'), { retryAfterMs: 60000 })
        return hold ? old.promise : { soundEnabled: true }
    } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    const initialCalls = ui.calls.preferences.length
    hold = true; await ui.event({ type: 'notifications.invalidated' })
    await act(async () => { now += 300; t.mock.timers.tick(300); await flush() })
    const foregroundCalls = ui.calls.preferences.length
    assert.equal(foregroundCalls, initialCalls + 1, 'Keep an actual foreground GET pending before switching to hidden')
    document.hidden = true; document.dispatchEvent(new Event('visibilitychange'))
    await ui.event({ type: 'notifications.invalidated' }); await ui.event(created('rate-limited'))
    assert.equal(ui.calls.preferences.length, foregroundCalls + 1)
    await act(async () => { old.resolve({ soundEnabled: true }); await flush() })
    await ui.event(created('still-limited'))
    assert.equal(ui.calls.preferences.length, foregroundCalls + 1, 'The obsolete GET must not reset the newer backoff')
    assert.equal(ui.audio.played, 1); assert.equal(ui.calls.detail.length, 0)
})

test('mute and unavailable or autoplay-blocked audio avoid background detail reads', async t => {
    const ui = fixture(t, {}, { audio: true, rejectAudio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    document.hidden = true
    await ui.event(created('blocked'))
    assert.equal(ui.calls.detail.length, 0); assert.equal(ui.audio.played, 0)
})

test('preference failure never assumes enabled and does not suppress banners', async t => {
    const ui = fixture(t, { preferences: async () => { throw new Error('offline') } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open()
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.disabled, true)
    await ui.event(created('unknown-preference'))
    assert.equal(ui.audio.played, 0); assert.equal(classNodes(ui, 'notification-banner').length, 1)
    assert.match(JSON.stringify(ui.renderer.toJSON()), /Не удалось загрузить настройку звука/)
})

for (const status of [429, 503]) test(`optional preferences respect HTTP ${status} Retry-After through ready, invalidation and manual reload`, async t => {
    let now = Date.now(), limited = true
    t.mock.method(Date, 'now', () => now)
    const ui = fixture(t, { preferences: async () => {
        if (limited) throw Object.assign(new Error('limited'), { status, retryAfterMs: 60000 })
        return { soundEnabled: true }
    } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open()
    assert.equal(ui.calls.preferences.length, 1)
    for (let attempt = 0; attempt < 3; attempt++) {
        await ui.event({ type: 'notifications.invalidated' }); await ui.ready()
        await act(async () => { buttonWith(ui, 'Повторить загрузку настройки').props.onClick(); await flush() })
    }
    now += 59999
    await ui.ready()
    assert.equal(ui.calls.preferences.length, 1, 'The noncritical preference must still honor the server admission deadline')
    await ui.event(created('visual-during-preference-backoff'))
    assert.equal(classNodes(ui, 'notification-banner').length, 1, 'Preference rate limiting does not block visual alerts')
    now++; limited = false
    await act(async () => { buttonWith(ui, 'Повторить загрузку настройки').props.onClick(); await flush() })
    assert.equal(ui.calls.preferences.length, 2)
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.checked, true)
})

test('a new session clears optional preference backoff and an aborted old read cannot install an error', async t => {
    const held = deferred()
    let phase = 'limited'
    const ui = fixture(t, { preferences: async () => {
        if (phase === 'limited') throw Object.assign(new Error('limited'), { status: 429, retryAfterMs: 60000 })
        if (phase === 'held') return held.promise
        return { soundEnabled: true }
    } })
    await ui.mount()
    phase = 'held'; ui.auth.accessToken = 'B'; ui.auth.sessionEpoch++
    await ui.update()
    assert.equal(ui.calls.preferences.length, 2, 'The old session deadline cannot delay the new identity')
    const oldSignal = ui.calls.preferences.at(-1).signal
    phase = 'healthy'; ui.auth.accessToken = 'C'; ui.auth.sessionEpoch++
    await ui.update(); await ui.open()
    assert.equal(oldSignal.aborted, true)
    await act(async () => { held.reject(new Error('late old session')); await flush() })
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.checked, true)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'notification-sound-error' }).length, 0)
})

test('remote mute fences a stale preference GET synchronously until a fresh snapshot is confirmed', async t => {
    let enabled = true, hold = false
    const stale = deferred()
    const ui = fixture(t, { preferences: async () => hold ? stale.promise : { soundEnabled: enabled } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    hold = true
    let ready
    await act(async () => {
        ready = ui.stream.onReady({ stream: { alertsAfter: boundary } }, new AbortController().signal)
        await flush()
    })
    enabled = false
    await ui.event({ type: 'notifications.invalidated' })
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.disabled, true)
    hold = false
    await act(async () => { stale.resolve({ soundEnabled: true }); await ready; await flush() })
    await ui.event(created('after-remote-mute'))
    assert.equal(ui.audio.played, 1, 'The stale GET cannot re-enable audio during the next scheduled reconciliation gap')
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.checked, false)
    await ui.ready()
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.checked, false)
})

test('failed mute remains fail closed until the persisted preference can be read again', async t => {
    let fail = false
    const ui = fixture(t, { preferences: async () => { if (fail) throw new Error('offline'); return { soundEnabled: true } },
        savePreferences: async () => { throw new Error('offline') } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    fail = true
    await act(async () => { ui.renderer.root.findByProps({ 'aria-label': 'Звуковые уведомления' }).props.onChange({ target: { checked: false } }); await flush() })
    await ui.event(created('ambiguous-mute'))
    assert.equal(ui.audio.played, 1); assert.equal(classNodes(ui, 'notification-banner').length, 1)
})

test('logout cancels hidden detail and closes the audio context before a late result', async t => {
    const held = deferred()
    const ui = fixture(t, { detail: async id => { await held.promise; return notification(id) } }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    document.hidden = true
    await ui.event(created('late-private'))
    const signal = ui.calls.detail[0].signal
    ui.auth.accessToken = null; ui.auth.currentEmployee = null
    await ui.update()
    assert.equal(signal.aborted, true); assert.equal(ui.audio.closed, 1)
    await act(async () => { held.resolve(); await flush() })
    assert.equal(ui.audio.played, 1); assert.equal(classNodes(ui, 'notification-banner').length, 0)
})

test('only trusted unmodified gestures unlock silently and do not replay previously missed notifications', async t => {
    const ui = fixture(t, {}, { audio: true, captureGestures: true })
    await ui.mount(); await ui.ready()
    await ui.event(created('missed-before-unlock'))
    await act(async () => {
        ui.gestures.pointerdown({ type: 'pointerdown', isTrusted: false })
        ui.gestures.keydown({ type: 'keydown', isTrusted: true, ctrlKey: true })
        await flush()
    })
    assert.equal(ui.audio.contexts.length, 0)
    await act(async () => {
        ui.gestures.pointerdown({ type: 'pointerdown', isTrusted: true })
        assert.equal(ui.audio.contexts.length, 1, 'Context creation occurs synchronously within the gesture')
        await flush()
    })
    assert.equal(ui.audio.played, 0, 'Unlock is silent and never queues missed sounds')
    await ui.event(created('fresh-after-unlock'))
    assert.equal(ui.audio.played, 1)
})

test('an incoming message in the visible conversation suppresses sound, while the hidden conversation can sound', async t => {
    const ui = fixture(t, { detail: async id => notification(id, { type: 'INCOMING_MESSAGE' }) }, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    ui.location.pathname = '/inbox/conversation'; document.querySelector = () => ({})
    await ui.update(); await ui.event(created('current-visible'))
    assert.equal(ui.audio.played, 1); assert.equal(classNodes(ui, 'notification-banner').length, 0)
    document.hidden = true
    await ui.event(created('current-hidden'))
    assert.equal(ui.audio.played, 2)
})

test('logout while a cross-tab lock is pending prevents a late tone and storage claim', async t => {
    const descriptor = Object.getOwnPropertyDescriptor(global, 'navigator')
    let release
    Object.defineProperty(global, 'navigator', { configurable: true, value: { locks: {
        request: (_name, _options, callback) => new Promise(resolve => { release = () => resolve(callback()) }),
    } } })
    t.after(() => descriptor ? Object.defineProperty(global, 'navigator', descriptor) : delete global.navigator)
    const ui = fixture(t, {}, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    await ui.event(created('waiting-on-tab'))
    assert.equal(typeof release, 'function')
    ui.auth.accessToken = 'OTHER'; ui.auth.currentEmployee = { id: 'OTHER', organizationId: 'O', role: 'EMPLOYEE' }
    await ui.update()
    await act(async () => { release(); await flush() })
    assert.equal(ui.audio.played, 1); assert.equal(ui.audio.closed, 1)
    assert.equal([...ui.values.keys()].some(key => key.includes(':sounds:')), false)
})

for (const all of [false, true]) test(`local ${all ? 'read-all' : 'read'} completion cancels an already authorized sound waiting on a tab lock`, async t => {
    const descriptor = Object.getOwnPropertyDescriptor(global, 'navigator')
    let release
    Object.defineProperty(global, 'navigator', { configurable: true, value: { locks: {
        request: (_name, _options, callback) => new Promise(resolve => { release = () => resolve(callback()) }),
    } } })
    t.after(() => descriptor ? Object.defineProperty(global, 'navigator', descriptor) : delete global.navigator)
    const ui = fixture(t, {}, { audio: true })
    await ui.mount(); await ui.ready(); await ui.open(); await checkSound(ui)
    await ui.event(created('notification'))
    await act(async () => {
        buttonWith(ui, all ? 'Отметить все прочитанными' : 'Прочитано').props.onClick()
        await flush(); release(); await flush()
    })
    assert.equal(ui.audio.played, 1)
    assert.equal([...ui.values.keys()].some(key => key.includes(':sounds:')), false)
})

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
    await act(async () => { ui.renderer.root.findAllByType('input').find(node => !node.props['aria-label']).props.onChange({ target: { checked: true } }); await flush() })
    assert.equal(ui.calls.list.at(-1).unread, true)
    assert.equal(ui.calls.list.at(-1).cursor, null, 'Changing the filter starts a fresh cursor chain')
})

test('clear requires confirmation, cancellation sends nothing, and success refreshes the entire center and banners', async t => {
    let rows = [notification('read', { readAt: boundary }), notification('unread')]
    const ui = fixture(t, {
        summary: async () => ({ unreadCount: rows.filter(row => !row.readAt).length }),
        list: async () => ({ items: rows, nextCursor: null }),
        clear: async () => { rows = []; return { clearedAt: boundary, deletedCount: 2 } },
    })
    await ui.mount(); await ui.ready(); await ui.open(); await ui.event(created('unread'))
    assert.equal(classNodes(ui, 'notification-banner').length, 1)
    await act(async () => { buttonWith(ui, 'Очистить уведомления').props.onClick(); await flush() })
    assert.equal(ui.calls.clears.length, 0)
    assert.match(JSON.stringify(ui.renderer.toJSON()), /во всех рабочих пространствах/)
    await act(async () => { buttonWith(ui, 'Отмена').props.onClick(); await flush() })
    assert.equal(classNodes(ui, 'notification-clear-confirm').length, 0); assert.equal(ui.calls.clears.length, 0)
    await act(async () => { buttonWith(ui, 'Очистить уведомления').props.onClick(); await flush() })
    await act(async () => { buttonWith(ui, 'Удалить все').props.onClick(); await flush() })
    assert.equal(ui.calls.clears.length, 1); assert.equal(classNodes(ui, 'notification-item').length, 0)
    assert.equal(classNodes(ui, 'notification-banner').length, 0)
    assert.equal(ui.renderer.root.findByProps({ className: 'notification-bell' }).props['aria-label'], 'Уведомления: 0 непрочитанных')
    assert.equal(ui.storage.pendingClear('O:E'), null)
})

test('uncertain clear persists before sending and replays its original key without deleting later arrivals', async t => {
    let lose = true, rows = [notification('old')]
    const ui = fixture(t, { summary: async () => ({ unreadCount: rows.length }), list: async () => ({ items: rows, nextCursor: null }),
        clear: async key => {
            assert.equal(ui.storage.pendingClear('O:E').key, key)
            if (lose) { lose = false; rows = []; throw Error('response lost') }
            return { clearedAt: boundary, deletedCount: 1 }
        } })
    await ui.mount(); await ui.ready(); await ui.open()
    await act(async () => { buttonWith(ui, 'Очистить уведомления').props.onClick(); await flush() })
    await act(async () => { buttonWith(ui, 'Удалить все').props.onClick(); await flush() })
    const key = ui.calls.clears[0].key
    rows = [notification('arrived-later')]; await ui.ready()
    await act(async () => { buttonWith(ui, 'Повторить очистку').props.onClick(); await flush() })
    assert.deepEqual(ui.calls.clears.map(call => call.key), [key, key])
    assert.equal(classNodes(ui, 'notification-item')[0].props['data-notification-id'], 'arrived-later')
    assert.equal(ui.renderer.root.findByProps({ className: 'notification-bell' }).props['aria-label'], 'Уведомления: 1 непрочитанных')
    assert.equal(ui.storage.pendingClear('O:E'), null)
    await act(async () => { buttonWith(ui, 'Очистить уведомления').props.onClick(); await flush() })
    await act(async () => { buttonWith(ui, 'Удалить все').props.onClick(); await flush() })
    assert.notEqual(ui.calls.clears[2].key, key)
})

test('account change cancels clear, preserves its retry and ignores the previous accounts late completion', async t => {
    const held = deferred()
    const ui = fixture(t, { list: async () => ({ items: [notification(ui.auth.currentEmployee.id === 'E' ? 'old-account' : 'new-account')], nextCursor: null }),
        clear: async () => held.promise })
    await ui.mount(); await ui.ready(); await ui.open()
    await act(async () => { buttonWith(ui, 'Очистить уведомления').props.onClick(); await flush() })
    await act(async () => { buttonWith(ui, 'Удалить все').props.onClick(); await flush() })
    assert.equal(buttonWith(ui, 'Очистить уведомления').props.disabled, true)
    assert.equal(buttonWith(ui, 'Отметить все прочитанными').props.disabled, true)
    assert.equal(ui.renderer.root.findAllByType('input').find(node => !node.props['aria-label']).props.disabled, true)
    ui.auth.accessToken = 'B'; ui.auth.currentEmployee = { id: 'NEW', organizationId: 'O', role: 'EMPLOYEE' }
    await ui.update()
    assert.equal(ui.calls.clears[0].signal.aborted, true)
    await act(async () => { held.resolve({ clearedAt: boundary, deletedCount: 1 }); await flush() })
    assert.equal(classNodes(ui, 'notification-item')[0].props['data-notification-id'], 'new-account')
    assert.ok(ui.storage.pendingClear('O:E')); assert.equal(ui.storage.pendingClear('O:NEW'), null)
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
