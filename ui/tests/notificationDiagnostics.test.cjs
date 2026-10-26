const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

function sample(extra = {}) {
    return {
        version: 1, managedPolling: true,
        transport: { state: 'backoff', attempts: 3, reconnects: 2, resyncs: 1 },
        recovery: { mode: 'fallback', rounds: 4, failures: 2 },
        reconciliation: { started: 12, completed: 8, failed: 2, cancelled: 1,
            peakRunning: 4, resources: 5, dirty: 2, running: 1 },
        requests: { started: 15, cancelled: 1, rejected: 2, peakActive: 4, peakQueued: 5, active: 2, queued: 3 },
        ...extra,
    }
}

function browser(t) {
    const previous = { window: global.window, document: global.document }
    global.window = new EventTarget()
    global.document = Object.assign(new EventTarget(), { hidden: false })
    const restore = () => Object.assign(global, previous)
    t?.after(restore)
    return restore
}

test('local diagnostics project safe fields and return detached immutable snapshots', t => {
    browser(t)
    const diagnostics = load('../../api/events/uiDiagnostics.ts')
    const source = sample({ employeeId: 'private-employee', accessToken: 'private-token', url: 'https://private-url' })
    source.transport.endpoint = 'private-endpoint'
    source.requests.urls = ['private-conversation']
    source.reconciliation.resourcesById = { 'private-resource': 1 }
    const cleanup = diagnostics.registerUiDiagnostics(() => source, () => true)
    t.after(cleanup)
    const value = diagnostics.readUiDiagnostics()
    assert.deepEqual(value, sample())
    assert.equal(JSON.stringify(value).includes('private'), false)
    assert.equal(Object.isFrozen(value), true)
    for (const key of ['transport', 'recovery', 'reconciliation', 'requests']) assert.equal(Object.isFrozen(value[key]), true)
    source.requests.active = 3
    assert.equal(value.requests.active, 2)
    assert.equal(diagnostics.readUiDiagnostics().requests.active, 3)
    assert.equal(window.clientbusNotificationDiagnostics().transport.reconnects, 2)
})

test('scope replacement revokes stored readers and obsolete cleanup cannot remove the new reader', t => {
    browser(t)
    const diagnostics = load('../../api/events/uiDiagnostics.ts')
    const first = diagnostics.registerUiDiagnostics(() => sample(), () => true)
    const oldRead = window.clientbusNotificationDiagnostics
    const second = diagnostics.registerUiDiagnostics(() => sample({ managedPolling: false, requests: null }), () => true)
    t.after(second)
    const newRead = window.clientbusNotificationDiagnostics
    assert.notEqual(newRead, oldRead)
    assert.equal(oldRead(), null)
    first()
    assert.equal(window.clientbusNotificationDiagnostics, newRead)
    assert.equal(diagnostics.readUiDiagnostics().managedPolling, false)
    second()
    assert.equal(newRead(), null)
    assert.equal(diagnostics.readUiDiagnostics(), null)
    assert.equal(window.clientbusNotificationDiagnostics, undefined)
})

test('a scope guard prevents stale reads before effect cleanup or logout rerender', t => {
    browser(t)
    const diagnostics = load('../../api/events/uiDiagnostics.ts')
    let current = true, reads = 0
    const cleanup = diagnostics.registerUiDiagnostics(() => { reads++; return sample() }, () => current)
    t.after(cleanup)
    const retained = window.clientbusNotificationDiagnostics
    assert.ok(retained())
    current = false
    assert.equal(retained(), null)
    assert.equal(diagnostics.readUiDiagnostics(), null)
    assert.equal(window.clientbusNotificationDiagnostics, undefined)
    current = true
    assert.equal(retained(), null, 'a revoked reader cannot revive when the original scope returns')
    assert.equal(reads, 1)
})

function provider(t, policy) {
    const restore = browser()
    const diagnostics = load('../../api/events/uiDiagnostics.ts')
    const streams = []
    let renderer
    const auth = { accessToken: 'private-token', currentEmployee: { id: 'private-employee', organizationId: 'private-org', role: 'EMPLOYEE' },
        sessionEpoch: 1, employeeWorkspaces: [], refreshCurrentUser: async () => {}, invalidateIdentity() {}, logout() {}, synchronizeSession() {} }
    const workspace = { currentWorkspace: { id: 'private-workspace' }, refreshWorkspaces: async () => {}, invalidateWorkspaces() {} }
    const { UiEventsProvider } = load('../../api/events/UiEventsProvider.tsx', {
        '../../auth/AuthContext': { useAuth: () => auth },
        '../../auth/authStorage': { getAccessToken: () => auth.accessToken },
        '../../workspace/WorkspaceContext': { useWorkspace: () => workspace },
        './uiDiagnostics': diagnostics,
        './uiEventStream': { startUiEventStream: options => {
            streams.push(options)
            options.onState('connecting')
            return { stop() {} }
        } },
    })
    const tree = () => React.createElement(UiEventsProvider, { policy }, null)
    t.after(async () => { try { await act(async () => renderer?.unmount()) } finally { restore() } })
    return {
        auth, workspace, diagnostics,
        get stream() { return streams.at(-1) },
        mount: async () => { await act(async () => { renderer = create(tree()) }) },
        update: async () => { await act(async () => renderer.update(tree())) },
        unmount: async () => { await act(async () => { renderer.unmount(); renderer = undefined }) },
    }
}

test('provider exposes transport attempts and resyncs together with existing REST counters', async t => {
    const ui = provider(t)
    await ui.mount()
    const read = window.clientbusNotificationDiagnostics
    assert.equal(read().transport.attempts, 1)
    await act(async () => {
        ui.stream.onState('open')
        ui.stream.onState('backoff')
        ui.stream.onState('connecting')
        ui.stream.onResync()
    })
    const value = read()
    assert.deepEqual(value.transport, { state: 'connecting', attempts: 2, reconnects: 1, resyncs: 1 })
    assert.equal(value.managedPolling, true)
    assert.equal(typeof value.reconciliation.started, 'number')
    assert.equal(typeof value.requests.started, 'number')
    assert.equal(JSON.stringify(value).includes('private'), false)
    await ui.unmount()
    assert.equal(read(), null)
    assert.equal(ui.diagnostics.readUiDiagnostics(), null)
    assert.equal(window.clientbusNotificationDiagnostics, undefined)
})

test('workspace and account changes revoke old provider readers and reset counters', async t => {
    const ui = provider(t)
    await ui.mount()
    const old = window.clientbusNotificationDiagnostics
    await act(async () => { ui.stream.onState('connecting'); ui.stream.onResync() })
    ui.workspace.currentWorkspace = { id: 'private-workspace-B' }
    await ui.update()
    const workspaceRead = window.clientbusNotificationDiagnostics
    assert.equal(old(), null)
    assert.deepEqual(workspaceRead().transport, { state: 'connecting', attempts: 1, reconnects: 0, resyncs: 0 })
    ui.auth.accessToken = 'private-token-B'
    assert.equal(workspaceRead(), null, 'storage token changes are guarded before the React cleanup')
    ui.auth.currentEmployee = { ...ui.auth.currentEmployee, id: 'private-employee-B' }
    await ui.update()
    assert.equal(workspaceRead(), null)
    assert.ok(window.clientbusNotificationDiagnostics())
    const accountRead = window.clientbusNotificationDiagnostics
    ui.auth.accessToken = null
    assert.equal(accountRead(), null)
    ui.auth.currentEmployee = null
    await ui.update()
    assert.equal(window.clientbusNotificationDiagnostics, undefined)
    assert.equal(ui.diagnostics.readUiDiagnostics(), null)
})

test('rollback mode is explicit and reports no automatic request budget', async t => {
    const ui = provider(t, { enabled: false })
    await ui.mount()
    const value = window.clientbusNotificationDiagnostics()
    assert.equal(value.managedPolling, false)
    assert.equal(value.requests, null)
})
