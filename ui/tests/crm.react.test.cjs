const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const uiScope = load('../../api/events/uiScope.ts')
const connection = { id: 'connection', provider: 'DIKIDI', displayName: 'Main account', loginHint: '••••0000', credentialConfigured: true, status: 'CONNECTED', version: 4 }
const project = { id: 'project', workspaceId: 'workspace', connectionId: 'connection', provider: 'DIKIDI', externalId: '100', displayName: 'Test project',
    zoneId: null, enabled: true, archived: false, contextVersion: 1, connectionState: 'CONNECTED', version: 3, capabilities: [{ code: 'READ_SERVICES', availability: 'AVAILABLE' }] }
const master = { id: 'master', projectId: 'project', externalId: 'm1', displayName: 'Test master', employeeId: null, version: 7 }
const state = { dataset: 'CATALOG', status: 'SUCCEEDED', freshness: 'CURRENT', lastSuccessAt: '2026-10-28T10:00:00Z', completeness: 'COMPLETE', generation: 1 }
const employee = { id: 'employee', organizationId: 'organization', firstName: 'Test', lastName: 'Employee', role: 'EMPLOYEE' }
const candidate = { id: 'employee', displayName: 'Test Employee', username: 'test-login', enabled: true }
const page = (items = [master], extra = {}) => ({ items, nextCursor: null, hasMore: false, dataState: state, revision: 1, ...extra })
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const text = node => JSON.stringify(node.toJSON())
const button = (ui, name) => ui.root.findAllByType('button').find(node => node.children.join('') === name)
const change = async (ui, label, value) => act(async () => ui.root.findByProps({ 'aria-label': label }).props.onChange({ target: { value } }))
const workspaceScope = workspaceId => ({ owner: {}, workspaceId, accessVersion: 0, isCurrent: () => true })

async function setup(t, kind, overrides = {}, props = {}, admin = true, scope = null, moduleMocks = {}) {
    let auth = { currentEmployee: { ...employee, id: 'actor', role: admin ? 'SUPER_ADMIN' : 'EMPLOYEE' }, isSuperAdmin: admin, sessionEpoch: 1, employeeWorkspaces: [] }
    const calls = [], invalidations = new Map(), params = new URLSearchParams()
    const api = {
        getCrmTypes: async () => ['DIKIDI'], getCrmConnections: async () => [connection], getOrganizationCrmProjects: async signal => api.getCrmProjects('workspace', signal),
        createCrmConnection: async (...args) => { calls.push(['create', ...args]); return connection },
        patchCrmConnection: async (...args) => { calls.push(['patch', ...args]); return { ...connection, ...args[1], version: 5 } },
        deleteCrmConnection: async (...args) => { calls.push(['delete', ...args]) },
        verifyCrmConnection: async (...args) => { calls.push(['verify', ...args]); return connection },
        getCrmProjects: async () => [project], getCrmMasters: async () => page(), getCrmServices: async () => page([]),
        getCrmEmployeeCandidates: async () => { calls.push(['candidates']); return [candidate] },
        checkCrmProject: async (_, id) => ({ externalId: id, displayName: 'Verified project', currency: 'RUB', zoneId: null }),
        getAvailableCrmProjects: async () => ['100','200','300'].map(id => ({ externalId: id, displayName: 'Available project ' + id, currency: null, zoneId: null })),
        bindCrmProject: async (...args) => { calls.push(['bind', ...args]); return project },
        patchCrmProject: async (...args) => { calls.push(['patchProject', ...args]); return project },
        changeCrmProjectConnection: async (...args) => { calls.push(['rebind', ...args]); return project },
        detachCrmProject: async (...args) => { calls.push(['detach', ...args]); return { ...project, archived: true, enabled: false, contextVersion: 2, version: 4 } },
        linkCrmMaster: async (row, id) => { calls.push(['link', row, id]); return { ...row, employeeId: id, version: row.version + 1 } },
        refreshCrmCatalog: async (...args) => { calls.push(['refresh', ...args]); return state },
        crmAccountName: row => row.displayName || row.provider + ' · ' + row.loginHint,
        crmConnectionLabel: value => value, crmError: error => error.message, ...overrides,
    }
    const mocks = {
        '../../auth/AuthContext': { useAuth: () => auth }, '../auth/AuthContext': { useAuth: () => auth },
        '../../api/crmApi': api, '../api/crmApi': api, './crm.css': {}, '../components/crm/crm.css': {},
        '../../api/events/uiScope': uiScope, '../api/events/uiScope': uiScope, './uiScope': uiScope,
        '../../workspace/WorkspaceContext': { useWorkspace: () => ({ workspaces: [{ id: 'workspace', name: 'Workspace' }] }) },
        'react-router-dom': { useSearchParams: () => [params, () => {}], Link: props => React.createElement('a', { ...props, href: props.to }) },
        './InvalidationContext': { useUiInvalidation: (key, enabled, matches, read, reset) => { if (enabled) invalidations.set(key, { read, reset }) } },
        ...moduleMocks,
    }
    const exports = load(kind === 'CrmMasters' || kind === 'CrmServices' ? '../crm/CrmCatalog.tsx' : '../crm/' + kind + '.tsx', mocks)
    const component = exports[kind] ?? exports.default
    const attributes = { workspaceId: 'workspace', project, mapping: false, onMaster() {}, ...props }
    const render = () => React.createElement(uiScope.UiScopeContext.Provider, { value: scope }, React.createElement(component, attributes))
    let ui
    await act(async () => { ui = create(render()) })
    t.after(() => act(() => ui.unmount()))
    return { ui, api, calls, params,
        invalidate(prefix) { return invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).read() },
        async refresh(prefix) { await act(async () => invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).read()) },
        async revoke(prefix) { await act(async () => invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).reset()) },
        async auth(value) { auth = value; await act(async () => ui.update(render())) },
        async scope(value) { scope = value; await act(async () => ui.update(render())) },
    }
}
async function newAccount(ui) { await act(async () => button(ui, 'Добавить аккаунт').props.onClick()) }

test('a failed project profile warns without hiding a successful catalog and clears after recovery', async t => {
    let failedProfile = true
    const fixture = await setup(t, 'CrmMasters', {
        getCrmMasters: async () => page([master], { dataState: { ...state,
            attemptCoverage: failedProfile ? { projectErrorCode: 'CRM_PROVIDER_FORBIDDEN' } : {} } }),
    })
    assert.match(text(fixture.ui), /Test master/)
    assert.match(text(fixture.ui), /название проекта не обновлено/)
    assert.doesNotMatch(text(fixture.ui), /Обновление не удалось/)
    failedProfile = false
    await fixture.refresh('crm-masters:')
    assert.match(text(fixture.ui), /Test master/)
    assert.doesNotMatch(text(fixture.ui), /название проекта не обновлено/)
})

test('only available CRM providers are offered; credentials submit once and immediately clear', async t => {
    const pending = deferred(), sent = []
    const { ui } = await setup(t, 'CrmConnections', { createCrmConnection: (...args) => { sent.push(args); return pending.promise } })
    await newAccount(ui)
    assert.deepEqual(ui.root.findByProps({ 'aria-label': 'Тип CRM' }).findAllByType('option').map(row => row.props.value), ['DIKIDI'])
    await change(ui, 'Название аккаунта', 'Branch'); await change(ui, 'Телефон DIKIDI', '70000000000'); await change(ui, 'Пароль DIKIDI', 'test-password')
    await act(async () => { const submit = ui.root.findByType('form').props.onSubmit; submit({ preventDefault() {} }); submit({ preventDefault() {} }) })
    assert.equal(sent.length, 1); assert.equal(sent[0][0].displayName, 'Branch'); assert.equal(sent[0][0].secret, 'test-password')
    assert.equal(ui.root.findByProps({ 'aria-label': 'Пароль DIKIDI' }).props.value, '')
    await act(async () => pending.reject(Error('Missing server key')))
    assert.match(text(ui), /Missing server key/)
})
test('renaming an account sends no credentials and uses the current version', async t => {
    const { ui, calls } = await setup(t, 'CrmConnections')
    await act(async () => button(ui, 'Изменить данные').props.onClick())
    assert.equal(button(ui, 'Сохранить').props.disabled, true)
    await change(ui, 'Название аккаунта', 'New alias')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.deepEqual(calls[0].slice(0, 3), ['patch', 'connection', { expectedVersion: 4, displayName: 'New alias' }])
})
test('connection mutation is aborted on scope change; a late failure does not affect a new operation', async t => {
    const first = deferred(), second = deferred(), signals = []
    const fixture = await setup(t, 'CrmConnections', { verifyCrmConnection: (_, signal) => { signals.push(signal); return signals.length === 1 ? first.promise : second.promise } }, {}, true, workspaceScope('workspace'))
    await act(async () => button(fixture.ui, 'Проверить').props.onClick())
    await fixture.scope(workspaceScope('new-scope'))
    assert.equal(signals[0].aborted, true)
    await act(async () => button(fixture.ui, 'Проверить').props.onClick())
    await act(async () => first.reject(Error('Old failure')))
    assert.doesNotMatch(text(fixture.ui), /Old failure/)
    assert.equal(button(fixture.ui, 'Проверить').props.disabled, true)
    await act(async () => second.resolve(connection))
    assert.equal(button(fixture.ui, 'Проверить').props.disabled, false)
})
test('enabling an account applies its new version before a delayed or older GET', async t => {
    const delayed = deferred(); let reads = 0
    const fixture = await setup(t, 'CrmConnections', {
        getCrmConnections: async () => ++reads === 1 ? [{ ...connection, status: 'DISABLED' }] : delayed.promise,
        patchCrmConnection: async () => ({ ...connection, status: 'CONFIGURED', version: 5 }),
    })
    await act(async () => button(fixture.ui, 'Включить аккаунт').props.onClick())
    assert.equal(button(fixture.ui, 'Включить аккаунт'), undefined)
    assert.equal(button(fixture.ui, 'Изменить данные').props.disabled, true)
    await act(async () => delayed.resolve([{ ...connection, status: 'DISABLED', version: 4 }]))
    assert.equal(button(fixture.ui, 'Включить аккаунт'), undefined)
    await act(async () => button(fixture.ui, 'Изменить данные').props.onClick())
    await change(fixture.ui, 'Название аккаунта', 'Latest version')
    let submitted
    fixture.api.patchCrmConnection = async (_, request) => { submitted = request; return { ...connection, version: 6 } }
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.equal(submitted.expectedVersion, 5)
})
test('changing identity clears the account draft and aborts a submission', async t => {
    const pending = deferred(); let signal
    const fixture = await setup(t, 'CrmConnections', { createCrmConnection: (_, s) => { signal = s; return pending.promise } })
    await newAccount(fixture.ui); await change(fixture.ui, 'Телефон DIKIDI', '70000000000'); await change(fixture.ui, 'Пароль DIKIDI', 'old-secret')
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await fixture.auth({ currentEmployee: { ...employee, id: 'other' }, isSuperAdmin: true, sessionEpoch: 2 })
    assert.equal(signal.aborted, true); assert.equal(fixture.ui.root.findAllByType('form').length, 0)
    await act(async () => pending.reject(Error('Old account failure')))
    assert.doesNotMatch(text(fixture.ui), /Old account failure|old-secret/)
})
test('employee cannot load settings, even if the settings component is mounted directly', async t => {
    let reads = 0
    for (const kind of ['CrmConnections', 'WorkspaceCrmPanel']) {
        const { ui } = await setup(t, kind, { getCrmConnections: async () => { reads++; return [] } }, {}, false)
        assert.equal(ui.toJSON(), null)
    }
    assert.equal(reads, 0)
})
test('an unused account is deleted only after confirmation and old reads do not restore it', async t => {
    const { ui, calls, refresh } = await setup(t, 'CrmConnections', { getOrganizationCrmProjects: async () => [],
        getCrmConnections: async () => [{ ...connection, status: 'DISABLED' }] })
    assert.equal(button(ui, 'Удалить').props.disabled, false)
    await act(async () => button(ui, 'Удалить').props.onClick())
    assert.deepEqual(calls, [])
    await act(async () => button(ui, 'Отмена').props.onClick())
    assert.equal(button(ui, 'Подтвердить удаление'), undefined)
    await act(async () => button(ui, 'Удалить').props.onClick())
    await act(async () => button(ui, 'Подтвердить удаление').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['delete', connection.id, connection.version])
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 0)
    assert.match(text(ui), /Аккаунт CRM удалён/)
    await refresh('crm-connections:')
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 0)
})

test('even a disabled project prevents account deletion', async t => {
    const { ui, calls } = await setup(t, 'CrmConnections', { getOrganizationCrmProjects: async () => [{ ...project, enabled: false }] })
    assert.equal(button(ui, 'Удалить').props.disabled, true)
    assert.match(button(ui, 'Удалить').props.title, /смените аккаунт.*проектов/)
    assert.deepEqual(calls, [])
})

test('deletion uses the confirmed version; a conflict preserves the account and requires a fresh confirmation', async t => {
    let version = connection.version; const attempts = []
    const { ui, refresh } = await setup(t, 'CrmConnections', { getOrganizationCrmProjects: async () => [],
        getCrmConnections: async () => [{ ...connection, version }],
        deleteCrmConnection: async (id, expected) => {
            attempts.push([id, expected])
            if (expected !== version) throw Object.assign(Error('Version conflict'), { code: 'CRM_VERSION_CONFLICT', status: 409 })
        } })
    await act(async () => button(ui, 'Удалить').props.onClick())
    version++; await refresh('crm-connections:')
    await act(async () => button(ui, 'Подтвердить удаление').props.onClick())
    assert.deepEqual(attempts, [[connection.id, connection.version]])
    assert.match(text(ui), /Version conflict/)
    assert.equal(button(ui, 'Подтвердить удаление'), undefined)
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 1)
    await act(async () => button(ui, 'Удалить').props.onClick())
    await act(async () => button(ui, 'Подтвердить удаление').props.onClick())
    assert.deepEqual(attempts[1], [connection.id, version])
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 0)
})

test('a late delete response cannot change another employee session', async t => {
    const pending = deferred(); let signal
    const fixture = await setup(t, 'CrmConnections', { getOrganizationCrmProjects: async () => [],
        deleteCrmConnection: (_, __, value) => { signal = value; return pending.promise } })
    await act(async () => button(fixture.ui, 'Удалить').props.onClick())
    await act(async () => button(fixture.ui, 'Подтвердить удаление').props.onClick())
    await fixture.auth({ currentEmployee: { ...employee, id: 'other-admin', role: 'SUPER_ADMIN' }, isSuperAdmin: true, sessionEpoch: 2 })
    assert.equal(signal.aborted, true)
    await act(async () => pending.resolve())
    assert.equal(fixture.ui.root.findAllByProps({ className: 'crm-connection' }).length, 1)
    assert.doesNotMatch(text(fixture.ui), /Аккаунт CRM удалён/)
})

test('external deletion closes the account editor and clears its credential draft', async t => {
    let rows = [connection]
    const { ui, refresh } = await setup(t, 'CrmConnections', { getCrmConnections: async () => rows, getOrganizationCrmProjects: async () => [] })
    await act(async () => button(ui, 'Изменить данные').props.onClick())
    await change(ui, 'Пароль DIKIDI', 'test-only-draft-password')
    rows = []; await refresh('crm-connections:')
    assert.equal(ui.root.findAllByType('form').length, 0)
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 0)
    await newAccount(ui)
    assert.equal(ui.root.findByProps({ 'aria-label': 'Пароль DIKIDI' }).props.value, '')
    assert.equal(ui.root.findByProps({ 'aria-label': 'Телефон DIKIDI' }).props.value, '')
})

test('external deletion removes the created-account project-binding prompt', async t => {
    let rows = []
    const { ui, refresh } = await setup(t, 'CrmConnections', { getCrmConnections: async () => rows, getOrganizationCrmProjects: async () => [],
        createCrmConnection: async () => { rows = [connection]; return connection } })
    await newAccount(ui); await change(ui, 'Телефон DIKIDI', '70000000000'); await change(ui, 'Пароль DIKIDI', 'test-only-password')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await change(ui, 'Рабочее пространство', 'workspace')
    rows = []; await refresh('crm-connections:')
    assert.equal(ui.root.findAllByType('a').filter(node => node.children.join('') === 'Подключить проект').length, 0)
    assert.doesNotMatch(text(ui), /Аккаунт добавлен/)
})

test('a pre-creation GET cannot remove the new account or its binding prompt while a fresh GET is pending', async t => {
    const previous = deferred(), fresh = deferred()
    const fixture = await setup(t, 'CrmConnections', { getCrmConnections: async () => [], getOrganizationCrmProjects: async () => [] })
    await newAccount(fixture.ui); await change(fixture.ui, 'Телефон DIKIDI', '70000000000'); await change(fixture.ui, 'Пароль DIKIDI', 'test-only-password')
    fixture.api.getCrmConnections = () => previous.promise
    let reading
    await act(async () => { reading = fixture.invalidate('crm-connections:') })
    fixture.api.getCrmConnections = () => fresh.promise
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await change(fixture.ui, 'Рабочее пространство', 'workspace')
    await act(async () => { previous.resolve([]); await reading })
    assert.equal(fixture.ui.root.findAllByProps({ className: 'crm-connection' }).length, 1)
    assert.equal(fixture.ui.root.findAllByType('a').filter(node => node.children.join('') === 'Подключить проект').length, 1)
    await act(async () => fresh.resolve([connection]))
    assert.match(text(fixture.ui), /Аккаунт добавлен/)
})

test('a delayed save response cannot restore an externally deleted account', async t => {
    let rows = [connection]; const pending = deferred()
    const { ui, refresh } = await setup(t, 'CrmConnections', { getCrmConnections: async () => rows, getOrganizationCrmProjects: async () => [],
        patchCrmConnection: () => pending.promise })
    await act(async () => button(ui, 'Изменить данные').props.onClick())
    await change(ui, 'Название аккаунта', 'Draft name')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    rows = []; await refresh('crm-connections:')
    await act(async () => pending.resolve({ ...connection, displayName: 'Draft name', version: 5 }))
    assert.equal(ui.root.findAllByProps({ className: 'crm-connection' }).length, 0)
    assert.equal(ui.root.findAllByType('form').length, 0)
    assert.doesNotMatch(text(ui), /Изменения сохранены/)
})

test('detachment requires confirmation; cancellation preserves the binding and success keeps the archive despite stale GETs', async t => {
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel')
    await act(async () => button(ui, 'Отвязать проект').props.onClick())
    assert.match(text(ui), /Каталог, клиенты CRM, графики, визиты, финансы и сопоставления останутся в архиве/)
    assert.equal(calls.length, 0)
    await act(async () => button(ui, 'Отмена').props.onClick())
    assert.equal(button(ui, 'Подтвердить отвязку'), undefined); assert.equal(calls.length, 0)
    await act(async () => button(ui, 'Отвязать проект').props.onClick())
    await act(async () => button(ui, 'Подтвердить отвязку').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['detach', 'project', 3])
    assert.match(text(ui), /В архиве/); assert.match(text(ui), /Test project/)
    assert.equal(button(ui, 'Настроить проект'), undefined); assert.equal(button(ui, 'Включить обновление'), undefined)
    assert.ok(button(ui, 'Проверить проект')); assert.equal(ui.root.findAllByType('form').length, 1)
})
test('a project detached in another session closes the editor and confirmation and frees the slot', async t => {
    let rows = [project]
    const fixture = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => rows, getOrganizationCrmProjects: async () => rows })
    await act(async () => button(fixture.ui, 'Настроить проект').props.onClick())
    rows = [{ ...project, archived: true, enabled: false, contextVersion: 2, version: 4 }]
    await fixture.refresh('workspace-crm-settings:')
    assert.equal(button(fixture.ui, 'Закрыть настройки'), undefined)
    assert.equal(button(fixture.ui, 'Отвязать проект'), undefined)
    assert.ok(button(fixture.ui, 'Проверить проект'))
})
test('a version conflict cancels project detachment without changing the current binding', async t => {
    const { ui } = await setup(t, 'WorkspaceCrmPanel', { detachCrmProject: async () => { throw Object.assign(Error('Version conflict'), { code: 'CRM_VERSION_CONFLICT' }) } })
    await act(async () => button(ui, 'Отвязать проект').props.onClick())
    await act(async () => button(ui, 'Подтвердить отвязку').props.onClick())
    assert.match(text(ui), /Version conflict/); assert.equal(button(ui, 'Подтвердить отвязку'), undefined)
    assert.ok(button(ui, 'Настроить проект')); assert.equal(ui.root.findAllByType('form').length, 0)
})
test('late detach response is discarded after access revocation', async t => {
    const pending = deferred(); let signal
    const fixture = await setup(t, 'WorkspaceCrmPanel', { detachCrmProject: (_, __, value) => { signal = value; return pending.promise } })
    await act(async () => button(fixture.ui, 'Отвязать проект').props.onClick())
    await act(async () => button(fixture.ui, 'Подтвердить отвязку').props.onClick())
    await fixture.revoke('workspace-crm-settings:')
    assert.equal(signal.aborted, true)
    await act(async () => pending.resolve({ ...project, archived: true, enabled: false, version: 4 }))
    assert.doesNotMatch(text(fixture.ui), /Test project|Проект отвязан/)
})
test('restoring an archive requires preview and sends its version with the selected account', async t => {
    const archive = { ...project, archived: true, enabled: false, contextVersion: 2, version: 4 }
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [archive], getOrganizationCrmProjects: async () => [archive],
        bindCrmProject: async (...args) => { calls.push(['restore', ...args]); return { ...project, contextVersion: 3, version: 5 } } })
    await change(ui, 'Подключение DIKIDI', 'connection'); await change(ui, 'Проект DIKIDI', '100')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.match(text(ui), /восстановлен из архива с прежней историей/)
    await act(async () => button(ui, 'Восстановить проект').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['restore', 'workspace', { connectionId: 'connection', externalProjectId: '100', expectedArchiveVersion: 4 }])
    assert.ok(button(ui, 'Отвязать проект')); assert.equal(ui.root.findAllByType('form').length, 0)
})
test('the detach API sends version and cancellation without invoking external CRM writes', async () => {
    const calls = [], controller = new AbortController()
    const api = load('../../api/crmApi.ts', { './apiClient': { apiFetch: async (...args) => { calls.push(args); return { json: async () => ({ archived: true }) } } } })
    await api.detachCrmProject('project/id', 7, controller.signal)
    assert.equal(calls[0][0], '/api/crm/projects/project%2Fid/detach?expectedVersion=7')
    assert.equal(calls[0][1].method, 'POST'); assert.equal(calls[0][1].signal, controller.signal)
})
test('a restoration preview keeps its reviewed archive version after a newer GET', async t => {
    let rows = [{ ...project, archived: true, enabled: false, contextVersion: 2, version: 4 }]
    const fixture = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => rows, getOrganizationCrmProjects: async () => rows })
    await change(fixture.ui, 'Подключение DIKIDI', 'connection'); await change(fixture.ui, 'Проект DIKIDI', '100')
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    rows = [{ ...rows[0], version: 6, contextVersion: 4 }]
    await fixture.refresh('workspace-crm-settings:')
    await act(async () => button(fixture.ui, 'Восстановить проект').props.onClick())
    assert.equal(fixture.calls[0][2].expectedArchiveVersion, 4)
})
test('archived employee mappings are visible but cannot be edited even through an old callback', async t => {
    const { ui, calls } = await setup(t, 'CrmMasters', { getCrmMasters: async () => page([{ ...master, employeeId: 'employee' }]) },
        { project: { ...project, archived: true, enabled: false }, mapping: true })
    const picker = ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' })
    assert.equal(picker.props.disabled, true); assert.equal(picker.props.value, 'employee')
    await act(async () => picker.props.onChange({ target: { value: '' } }))
    assert.equal(calls.filter(call => call[0] === 'link').length, 0)
    assert.match(text(ui), /архив/); assert.doesNotMatch(text(ui), /требуют обновления/)
})
test('a successful detachment survives empty old lists and later observed restoration cannot regress', async t => {
    let rows = [project]
    const fixture = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => rows, getOrganizationCrmProjects: async () => rows })
    await act(async () => button(fixture.ui, 'Отвязать проект').props.onClick())
    rows = []
    await act(async () => button(fixture.ui, 'Подтвердить отвязку').props.onClick())
    assert.match(text(fixture.ui), /В архиве/)
    rows = [{ ...project, version: 5, contextVersion: 3 }]
    await fixture.refresh('workspace-crm-settings:')
    assert.ok(button(fixture.ui, 'Отвязать проект'))
    rows = [{ ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }]
    await fixture.refresh('workspace-crm-settings:')
    assert.ok(button(fixture.ui, 'Отвязать проект')); assert.doesNotMatch(text(fixture.ui), /В архиве/)
})
test('a late detach reply cannot replace a newer restoration even when the following GET fails', async t => {
    const pending = deferred()
    let rows = [project], failReload = false
    const fixture = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => { if (failReload) throw Error('Unavailable'); return rows },
        getOrganizationCrmProjects: async () => rows, detachCrmProject: () => pending.promise,
    })
    await act(async () => button(fixture.ui, 'Отвязать проект').props.onClick())
    await act(async () => button(fixture.ui, 'Подтвердить отвязку').props.onClick())
    rows = [{ ...project, archived: false, enabled: true, version: 5, contextVersion: 3 }]
    await fixture.refresh('workspace-crm-settings:')
    assert.ok(button(fixture.ui, 'Отвязать проект'))
    failReload = true
    await act(async () => pending.resolve({ ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }))
    assert.ok(button(fixture.ui, 'Отвязать проект')); assert.equal(fixture.ui.root.findAllByType('form').length, 0)
    assert.doesNotMatch(text(fixture.ui), /В архиве|Проект отвязан\./)
    failReload = false; rows = [{ ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }]
    await fixture.refresh('workspace-crm-settings:')
    assert.ok(button(fixture.ui, 'Отвязать проект')); assert.doesNotMatch(text(fixture.ui), /В архиве/)
})
test('a late restore reply cannot replace a newer detachment', async t => {
    const pending = deferred()
    let rows = [{ ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }], failReload = false
    const fixture = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => { if (failReload) throw Error('Unavailable'); return rows },
        getOrganizationCrmProjects: async () => rows, bindCrmProject: () => pending.promise,
    })
    await change(fixture.ui, 'Подключение DIKIDI', 'connection'); await change(fixture.ui, 'Проект DIKIDI', '100')
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await act(async () => button(fixture.ui, 'Восстановить проект').props.onClick())
    rows = [{ ...project, archived: true, enabled: false, version: 6, contextVersion: 4 }]
    await fixture.refresh('workspace-crm-settings:')
    failReload = true
    await act(async () => pending.resolve({ ...project, archived: false, enabled: true, version: 5, contextVersion: 3 }))
    assert.match(text(fixture.ui), /В архиве/); assert.equal(button(fixture.ui, 'Отвязать проект'), undefined)
    assert.doesNotMatch(text(fixture.ui), /Проект подключён\./)
})
test('initial workspace and organization reads use the latest version without mixing workspaces', async t => {
    const newer = { ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }
    const { ui } = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => [project],
        getOrganizationCrmProjects: async () => [newer, { ...project, id: 'foreign', workspaceId: 'other-workspace', displayName: 'Other workspace project' }],
    })
    assert.match(text(ui), /В архиве/); assert.doesNotMatch(text(ui), /Other workspace project/)
    assert.equal(button(ui, 'Отвязать проект'), undefined)
})
test('an older GET cannot regress a project before any local mutation', async t => {
    let rows = [{ ...project, archived: true, enabled: false, version: 4, contextVersion: 2 }]
    const fixture = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => rows, getOrganizationCrmProjects: async () => rows })
    rows = [project]
    await fixture.refresh('workspace-crm-settings:')
    assert.match(text(fixture.ui), /В архиве/); assert.equal(button(fixture.ui, 'Отвязать проект'), undefined)
})
test('binding requires an explicit preview and changing the ID discards it', async t => {
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [] })
    await change(ui, 'Подключение DIKIDI', 'connection'); await change(ui, 'Проект DIKIDI', '100')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.ok(button(ui, 'Привязать проект')); assert.equal(calls.length, 0)
    await act(async () => button(ui, 'Назад').props.onClick()); await change(ui, 'Проект DIKIDI', '200')
    assert.equal(button(ui, 'Привязать проект'), undefined)
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await change(ui, 'Часовой пояс проекта', 'Europe/Moscow')
    await act(async () => button(ui, 'Привязать проект').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['bind', 'workspace', { connectionId: 'connection', externalProjectId: '200', zoneId: 'Europe/Moscow' }])
})
test('health invalidations preserve project selection and preview without another remote list read', async t => {
    let listCalls = 0, pending
    const fixture = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => pending ? pending.promise : [], getOrganizationCrmProjects: async () => [],
        getAvailableCrmProjects: async () => { listCalls++; return [{ externalId: '100', displayName: 'Salon' }] },
    })
    await change(fixture.ui, 'Подключение DIKIDI', 'connection')
    await change(fixture.ui, 'Проект DIKIDI', '100')
    for (const preview of [false, true]) {
        if (preview) await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
        pending = deferred(); let refreshing
        await act(async () => { refreshing = fixture.invalidate('workspace-crm-settings:'); await Promise.resolve() })
        assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '100')
        assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.disabled, preview)
        assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Подключение DIKIDI' }).props.disabled, preview)
        assert.equal(button(fixture.ui, preview ? 'Привязать проект' : 'Проверить проект').props.disabled, true)
        await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
        assert.ok(button(fixture.ui, preview ? 'Привязать проект' : 'Проверить проект'))
        await act(async () => { pending.resolve([]); await refreshing }); pending = undefined
        assert.equal(listCalls, 1, 'parent health refresh must not restart the remote project list')
        assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '100')
        assert.equal(button(fixture.ui, preview ? 'Привязать проект' : 'Проверить проект').props.disabled, false)
    }
})

test('project discovery stays interactive through fallback and reconnect reconciliation; manual refresh and access revoke still work', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve() }
    const invalidation = load('../../api/events/InvalidationContext.tsx')
    const { InvalidationCoordinator } = load('../../api/events/invalidationCoordinator.ts')
    const { UiRecovery } = load('../../api/events/uiRecovery.ts')
    const { recoveryPolicy } = load('../../api/events/recoveryPolicy.ts')
    const manager = new InvalidationCoordinator(() => true, 0, 0)
    const recovery = new UiRecovery(manager, recoveryPolicy({ jitter: 0 }))
    let reads = 0, ui, signal, hold = false
    const pending = deferred()
    const Select = load('../crm/CrmProjectSelect.tsx', {
        '../../api/crmApi': {
            getAvailableCrmProjects: async (_id, _workspace, requestSignal) => {
                signal = requestSignal
                reads++
                if (hold) return pending.promise
                return [{ externalId: String(reads), displayName: 'Salon ' + reads }]
            }, crmError: error => error.message,
        }, './InvalidationContext': invalidation, './uiScope': uiScope, '../../api/events/uiScope': uiScope,
    }).default
    function Form() {
        const [value, onChange] = React.useState('')
        return React.createElement(Select, { connection, workspaceId: 'workspace', bindings: [], value, onChange, disabled: false })
    }
    t.after(async () => { recovery.stop(); manager.close(); await act(async () => ui?.unmount()) })
    await act(async () => { ui = create(React.createElement(invalidation.InvalidationContext.Provider, { value: manager }, React.createElement(Form))); await flush() })
    await change(ui, 'Проект DIKIDI', '1')
    await act(async () => { await manager.reconcileAvailable(); await flush() })
    assert.equal(reads, 1, 'stream ready must not repeat external discovery')
    recovery.state('disabled')
    await act(async () => { t.mock.timers.tick(10_000); await flush() })
    assert.equal(reads, 1, 'notification fallback must not poll external discovery')
    recovery.state('open')
    await act(async () => { t.mock.timers.tick(120_000); await flush(); recovery.request(); t.mock.timers.tick(0); await flush() })
    assert.equal(reads, 1, 'control reconciliation and visibility resync must not poll external discovery')
    assert.equal(ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.disabled, false)
    assert.equal(ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '1')
    hold = true
    await act(async () => { button(ui, 'Обновить список проектов').props.onClick(); await flush() })
    assert.equal(reads, 2)
    const oldSignal = signal
    hold = false
    await act(async () => { manager.revoke({ type: 'access.invalidated', workspaceId: 'workspace' }); await flush(); t.mock.timers.tick(0); await flush() })
    assert.equal(oldSignal.aborted, true); assert.equal(reads, 3)
    assert.equal(ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '')
    await act(async () => { pending.resolve([{ externalId: '2', displayName: 'Obsolete salon' }]); await flush() })
    assert.doesNotMatch(text(ui), /Obsolete salon/)
})

test('a failed background settings refresh preserves the project draft through retry', async t => {
    let fail = false, listCalls = 0
    const fixture = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => { if (fail) throw Error('Settings unavailable'); return [] },
        getOrganizationCrmProjects: async () => [],
        getAvailableCrmProjects: async () => { listCalls++; return [{ externalId: '100', displayName: 'Salon' }] },
    })
    await change(fixture.ui, 'Подключение DIKIDI', 'connection')
    await change(fixture.ui, 'Проект DIKIDI', '100')
    fail = true
    await assert.rejects(fixture.refresh('workspace-crm-settings:'), /Settings unavailable/)
    assert.equal(button(fixture.ui, 'Проверить проект').props.disabled, true)
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '100')
    await act(async () => fixture.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.equal(button(fixture.ui, 'Привязать проект'), undefined)
    fail = false
    await act(async () => button(fixture.ui, 'Повторить загрузку').props.onClick())
    assert.equal(listCalls, 1); assert.equal(button(fixture.ui, 'Проверить проект').props.disabled, false)
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '100')
})

test('initial settings load and revoked access keep the binding form hidden and abort pending project options', async t => {
    const initial = deferred(), options = deferred(); let optionSignal
    const fixture = await setup(t, 'WorkspaceCrmPanel', {
        getCrmProjects: async () => initial.promise, getOrganizationCrmProjects: async () => [],
        getAvailableCrmProjects: (_, __, signal) => { optionSignal = signal; return options.promise },
    })
    assert.equal(fixture.ui.root.findAllByType('form').length, 0)
    await act(async () => initial.resolve([]))
    await change(fixture.ui, 'Подключение DIKIDI', 'connection')
    await fixture.revoke('workspace-crm-settings:')
    assert.equal(optionSignal.aborted, true)
    assert.equal(fixture.ui.root.findAllByType('form').length, 0)
    await act(async () => options.resolve([{ externalId: '100', displayName: 'Old salon' }]))
    assert.doesNotMatch(text(fixture.ui), /Old salon/)
})

test('project choices exclude occupied bindings and mark this workspaces archive', async t => {
    const { ui } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [],
        getOrganizationCrmProjects: async () => [{ ...project, archived: true, enabled: false },
            { ...project, id: 'busy', externalId: '200', workspaceId: 'other' },
            { ...project, id: 'archive-other', externalId: '300', workspaceId: 'other', archived: true, enabled: false }],
        getAvailableCrmProjects: async () => ['100','200','300','400'].map(id => ({ externalId: id, displayName: id === '400' ? 'Free salon' : 'Salon ' + id })),
    })
    await change(ui, 'Подключение DIKIDI', 'connection')
    const options = ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).findAllByType('option')
    assert.deepEqual(options.map(row => row.props.value), ['', '100', '400'])
    assert.match(options[1].children.join(''), /восстановить/)
})
test('switching CRM accounts clears the selection and ignores late options from the old account', async t => {
    const pending = deferred(); let oldSignal
    const fixture = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [],
        getCrmConnections: async () => [connection, { ...connection, id: 'second' }],
        getAvailableCrmProjects: (id, _, signal) => { if (id === 'connection') { oldSignal = signal; return pending.promise }
            return Promise.resolve([{ externalId: '200', displayName: 'Current account project' }]) },
    })
    await change(fixture.ui, 'Подключение DIKIDI', 'connection')
    await change(fixture.ui, 'Подключение DIKIDI', 'second')
    assert.equal(oldSignal.aborted, true)
    await act(async () => pending.resolve([{ externalId: '100', displayName: 'Old account project' }]))
    assert.match(text(fixture.ui), /Current account project/); assert.doesNotMatch(text(fixture.ui), /Old account project/)
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Проект DIKIDI' }).props.value, '')
    assert.equal(button(fixture.ui, 'Проверить проект').props.disabled, true)
})
test('a failed project list has an explicit retry and cannot submit a previously selected project', async t => {
    let failed = true
    const { ui } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [],
        getAvailableCrmProjects: async () => { if (failed) throw Error('Project list unavailable'); return [] },
    })
    await change(ui, 'Подключение DIKIDI', 'connection')
    assert.match(text(ui), /Project list unavailable/); assert.equal(button(ui, 'Проверить проект').props.disabled, true)
    failed = false; await act(async () => button(ui, 'Повторить загрузку проектов').props.onClick())
    assert.match(text(ui), /Нет свободных проектов/); assert.doesNotMatch(text(ui), /Project list unavailable/)
})
test('disabled binding occupies the workspace; disabling requires confirmation and preserves identity', async t => {
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel')
    assert.equal(ui.root.findAllByProps({ 'aria-label': 'Проект DIKIDI' }).length, 0)
    await act(async () => button(ui, 'Выключить обновление').props.onClick())
    assert.equal(calls.length, 0); assert.match(text(ui), /Привязка, история и связи сохранятся/)
    await act(async () => button(ui, 'Подтвердить выключение').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['patchProject', 'project', { expectedVersion: 3, enabled: false }])
    const disabled = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [{ ...project, enabled: false }] })
    assert.equal(disabled.ui.root.findAllByType('form').length, 0)
    assert.ok(button(disabled.ui, 'Включить обновление'))
})
test('disabled account is explained before the project ID form and links to that account', async t => {
    const { ui } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [], getCrmConnections: async () => [{ ...connection, status: 'DISABLED' }] })
    await change(ui, 'Подключение DIKIDI', 'connection')
    assert.equal(ui.root.findAllByProps({ 'aria-label': 'Проект DIKIDI' }).length, 0)
    assert.equal(button(ui, 'Проверить проект').props.disabled, true)
    assert.match(text(ui), /account=connection/)
})
test('ordinary working directory has no mapping labels, filters or candidate reads', async t => {
    const { ui, calls } = await setup(t, 'CrmMasters', {}, {}, false)
    assert.match(text(ui), /Test master/); assert.doesNotMatch(text(ui), /Без связи|Сопоставлен|Сотрудник ClientBus|Сотрудник:|employee-candidates/)
    assert.equal(ui.root.findAllByType('select').length, 0); assert.deepEqual(calls, [])
})
test('supervisor saves and undoes using the returned version, and duplicate names have login labels', async t => {
    const { ui, calls } = await setup(t, 'CrmMasters', { getCrmEmployeeCandidates: async () => [candidate, { ...candidate, id: 'e2', username: 'second-login' }] }, { mapping: true }, false)
    const labels = ui.root.findAllByType('option').map(row => row.children.join(''))
    assert.ok(labels.some(label => label.includes('test-login'))); assert.ok(labels.some(label => label.includes('second-login')))
    await change(ui, 'Сотрудник: Test master', 'employee')
    assert.equal(calls[0][1].version, 7)
    assert.equal(ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' }).props.value, 'employee')
    await act(async () => button(ui, 'Отменить изменение').props.onClick())
    assert.equal(calls[1][1].version, 8); assert.equal(calls[1][2], null)
    assert.match(text(ui), /изменение отменено/)
})
test('failed mapping preserves old value; a failed undo never overwrites a later edit', async t => {
    let count = 0
    const fixture = await setup(t, 'CrmMasters', { linkCrmMaster: async (row, id) => {
        if (++count !== 2) throw Object.assign(Error('Version conflict'), { status: 409, code: 'CRM_VERSION_CONFLICT' })
        return { ...row, employeeId: id, version: 8 }
    } }, { mapping: true })
    await change(fixture.ui, 'Сотрудник: Test master', 'employee')
    assert.match(text(fixture.ui), /Version conflict/)
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' }).props.value, '')
    await change(fixture.ui, 'Сотрудник: Test master', 'employee')
    await act(async () => button(fixture.ui, 'Отменить изменение').props.onClick())
    assert.equal(count, 3); assert.match(text(fixture.ui), /Version conflict/)
    assert.equal(button(fixture.ui, 'Отменить изменение'), undefined)
})
test('mapping mutation is cancelled and the controls recover after a new UI scope', async t => {
    const pending = deferred(); let submittedSignal
    const fixture = await setup(t, 'CrmMasters', {
        linkCrmMaster: async (_row, _id, signal) => { submittedSignal = signal; return pending.promise },
    }, { mapping: true }, false, workspaceScope('workspace'))
    await change(fixture.ui, 'Сотрудник: Test master', 'employee')
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' }).props['aria-disabled'], true)
    await fixture.scope(workspaceScope('workspace'))
    assert.equal(submittedSignal.aborted, true)
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' }).props['aria-disabled'], false)
    await act(async () => pending.resolve({ ...master, employeeId: 'employee', version: 8 }))
    assert.equal(fixture.ui.root.findByProps({ 'aria-label': 'Сотрудник: Test master' }).props.value, '')
    assert.equal(button(fixture.ui, 'Отменить изменение'), undefined)
})
test('search parameters go to the server, not just the loaded page', async t => {
    const reads = []
    const { ui } = await setup(t, 'CrmMasters', { getCrmMasters: async (_, signal, cursor, query) => { reads.push(query); return page(query.q ? [{ ...master, displayName: 'Later page match' }] : [master]) } })
    await act(async () => ui.root.findByType('input').props.onChange({ target: { value: 'needle' } }))
    assert.equal(reads.at(-1).q, 'needle'); assert.match(text(ui), /Later page match/); assert.doesNotMatch(text(ui), /Test master/)
})
test('successful background read preserves loaded pages of the same revision', async t => {
    const fixture = await setup(t, 'CrmMasters', { getCrmMasters: async (_, s, cursor) => cursor ? page([{ ...master, id: 'second', displayName: 'Second master' }]) : page([master], { hasMore: true, nextCursor: 'next' }) })
    await act(async () => button(fixture.ui, 'Показать ещё мастеров').props.onClick())
    await fixture.refresh('crm-masters:')
    assert.match(text(fixture.ui), /Test master/); assert.match(text(fixture.ui), /Second master/)
})
test('403 on the next page clears old rows even when the following reload returns 503', async t => {
    let revoked = false
    const fixture = await setup(t, 'CrmMasters', { getCrmMasters: async (_, s, cursor) => {
        if (cursor) { revoked = true; throw Object.assign(Error('Forbidden'), { status: 403 }) }
        if (revoked) throw Object.assign(Error('Unavailable'), { status: 503 })
        return page([master], { hasMore: true, nextCursor: 'next' })
    } })
    await act(async () => button(fixture.ui, 'Показать ещё мастеров').props.onClick())
    assert.doesNotMatch(text(fixture.ui), /Test master/)
    await act(async () => button(fixture.ui, 'Повторить').props.onClick())
    assert.match(text(fixture.ui), /Unavailable/); assert.doesNotMatch(text(fixture.ui), /Test master/)
})
test('service offers show actual price and duration; unknown is distinct from free', async t => {
    const reads = []
    const { ui } = await setup(t, 'CrmServices', { getCrmServices: async (_, query) => {
        reads.push(query)
        return page([{ id: 's1', displayName: 'Service', category: 'Care', offers: [
            { id: 'o1', masterId: 'master', masterDisplayName: 'Master A', price: '0', currency: 'RUB', durationSeconds: 1800, offerState: {}, warnings: [] },
            { id: 'o2', masterId: 'm2', masterDisplayName: 'Master B', price: null, currency: null, durationSeconds: null, offerState: {}, warnings: [] },
            { id: 'o3', masterId: 'm3', masterDisplayName: 'Master C', price: '1200.0000', currency: 'RUB', durationSeconds: 600, offerState: {}, warnings: [] },
            { id: 'o4', masterId: 'm4', masterDisplayName: 'Master D', price: '9007199254740993.2500', currency: 'RUB', durationSeconds: 600, offerState: {}, warnings: [] },
        ] }])
    } })
    assert.match(text(ui), /0 RUB/); assert.match(text(ui), /30 мин/); assert.match(text(ui), /Цена не указана/); assert.match(text(ui), /Master A/)
    assert.match(text(ui), /1200 RUB/); assert.doesNotMatch(text(ui), /1200\.0000/)
    assert.match(text(ui), /9007199254740993\.25 RUB/)
    await act(async () => ui.root.findByType('input').props.onChange({ target: { value: 'needle' } }))
    assert.equal(reads.at(-1).q, 'needle')
})
test('CRM API sends query filters and mapping versions explicitly', async () => {
    const calls = [], api = load('../../api/crmApi.ts', { './apiClient': { apiFetch: async (...args) => { calls.push(args); return { json: async () => ({}) } } } })
    await api.linkCrmMaster(master, 'employee'); await api.linkCrmMaster(master, null)
    assert.deepEqual(JSON.parse(calls[0][1].body), { employeeId: 'employee', expectedVersion: 7 }); assert.match(calls[1][0], /expectedVersion=7$/)
    await api.getCrmMasters('project', undefined, 'scope:cursor+', { q: 'name', unlinked: true, includeNotObserved: false })
    const url = new URL(calls[2][0], 'http://local'); assert.equal(url.searchParams.get('cursor'), 'scope:cursor+'); assert.equal(url.searchParams.get('unlinked'), 'true'); assert.equal(url.searchParams.get('q'), 'name')
})
test('master history is loaded on demand beyond fifty pages', async t => {
    const reads = []
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => {
            reads.push(cursor)
            const page = cursor ? Number(cursor.slice(4)) : 0
            return { items: [{ ...master, id: 'page-' + page, displayName: 'Page master ' + page }],
                nextCursor: page < 51 ? 'page' + (page + 1) : null, hasMore: page < 51, dataState: state }
        },
    }, { canManage: false }, false)
    assert.equal(reads.length, 1)
    assert.equal(ui.root.findAllByProps({ className: 'crm-master' }).length, 1)
    for (let page = 1; page <= 51; page++) {
        await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    }
    assert.equal(reads.length, 52)
    assert.equal(ui.root.findAllByProps({ className: 'crm-master' }).length, 52)
    assert.equal(button(ui, 'Показать ещё мастеров'), undefined)
    assert.match(text(ui), /Page master 0/); assert.match(text(ui), /Page master 51/)
})

test('a failed next master page preserves loaded rows and can be retried', async t => {
    let nextReads = 0
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => {
            if (!cursor) return { items: [master], hasMore: true, nextCursor: 'second', dataState: state }
            if (++nextReads === 1) throw Error('Page temporarily unavailable')
            return { items: [{ ...master, id: 'second-master', displayName: 'Second master' }], hasMore: false, nextCursor: null, dataState: state }
        },
    })
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    assert.match(text(ui), /Test master/); assert.match(text(ui), /Page temporarily unavailable/)
    assert.equal(button(ui, 'Показать ещё мастеров').props.disabled, false)
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    assert.match(text(ui), /Second master/); assert.doesNotMatch(text(ui), /Page temporarily unavailable/)
    assert.equal(ui.root.findAllByProps({ className: 'crm-master' }).length, 2)
})

test('a changed catalog restarts master pagination without merging generations', async t => {
    let firstReads = 0
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => {
            if (cursor) throw Object.assign(Error('Catalog changed'), { code: 'CRM_CONTEXT_CHANGED', status: 409 })
            firstReads++
            return { items: [{ ...master, displayName: firstReads === 1 ? 'Old generation' : 'New generation' }],
                hasMore: firstReads === 1, nextCursor: firstReads === 1 ? 'old-cursor' : null, dataState: state }
        },
    })
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    assert.equal(firstReads, 2)
    assert.match(text(ui), /New generation/); assert.doesNotMatch(text(ui), /Old generation/)
    assert.doesNotMatch(text(ui), /Catalog changed/)
    assert.equal(button(ui, 'Показать ещё мастеров'), undefined)
})

test('changing UI scope cancels a late master page', async t => {
    const pending = deferred(); let pageSignal
    const fixture = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => {
            if (cursor) { pageSignal = signal; return pending.promise }
            return { items: [master], hasMore: true, nextCursor: 'second', dataState: state }
        },
    }, {}, true, workspaceScope('workspace'))
    await act(async () => button(fixture.ui, 'Показать ещё мастеров').props.onClick())
    await fixture.scope(workspaceScope('other-workspace'))
    assert.equal(pageSignal.aborted, true)
    await act(async () => pending.resolve({ items: [{ ...master, id: 'late', displayName: 'Late private master' }],
        hasMore: false, nextCursor: null, dataState: state }))
    assert.doesNotMatch(text(fixture.ui), /Late private master/)
    assert.equal(button(fixture.ui, 'Показать ещё мастеров').props.disabled, false)
})

test('an invalidation refresh prevents an old page from appending to a new first page', async t => {
    const pending = deferred(); let refresh, generation = 'Old'
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => cursor ? pending.promise :
            { items: [{ ...master, displayName: generation + ' first master' }], hasMore: true, nextCursor: generation, dataState: state },
    }, {}, true, null, {
        './InvalidationContext': { useUiInvalidation: (key, enabled, _matches, read) => { if (enabled && key.startsWith('crm-masters:')) refresh = read } },
    })
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    generation = 'New'
    await act(async () => refresh())
    await act(async () => pending.resolve({ items: [{ ...master, id: 'old-more', displayName: 'Old late page master' }],
        hasMore: false, nextCursor: null, dataState: state }))
    assert.match(text(ui), /New first master/)
    assert.doesNotMatch(text(ui), /Old first master|Old late page master/)
    assert.equal(ui.root.findAllByProps({ className: 'crm-master' }).length, 1)
})

test('revoked workspace access on a master page clears already displayed masters', async t => {
    let revoked = false
    const forbidden = () => Object.assign(Error('Workspace access revoked'), { status: 403 })
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmProjects: async () => { if (revoked) throw forbidden(); return [project] },
        getCrmMasters: async (_, signal, cursor) => {
            if (cursor) { revoked = true; throw forbidden() }
            return { items: [master], hasMore: true, nextCursor: 'second', dataState: state }
        },
    }, { canManage: false }, false)
    assert.match(text(ui), /Test master/)
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    assert.doesNotMatch(text(ui), /Test master/)
    assert.equal(button(ui, 'Показать ещё мастеров'), undefined)
})

test('an old master page error cannot override a newer invalidation refresh', async t => {
    const pending = deferred(); let refresh, firstReads = 0
    const { ui } = await setup(t, 'CrmMasters', {
        getCrmMasters: async (_, signal, cursor) => {
            if (cursor) return pending.promise
            firstReads++
            return { items: [{ ...master, displayName: 'Fresh first master ' + firstReads }],
                hasMore: true, nextCursor: 'cursor' + firstReads, dataState: state }
        },
    }, {}, true, null, {
        './InvalidationContext': { useUiInvalidation: (key, enabled, _matches, read) => { if (enabled && key.startsWith('crm-masters:')) refresh = read } },
    })
    await act(async () => button(ui, 'Показать ещё мастеров').props.onClick())
    await act(async () => refresh())
    await act(async () => pending.reject(Object.assign(Error('Old page error'), { status: 409, code: 'CRM_CONTEXT_CHANGED' })))
    assert.equal(firstReads, 2)
    assert.match(text(ui), /Fresh first master 2/); assert.doesNotMatch(text(ui), /Old page error/)
    assert.equal(button(ui, 'Показать ещё мастеров').props.disabled, false)
})


