const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const uiScope = load('../../api/events/uiScope.ts')
const connection = { id: 'connection', provider: 'DIKIDI', displayName: 'Main account', loginHint: '••••0000', credentialConfigured: true, status: 'CONNECTED', version: 4 }
const project = { id: 'project', workspaceId: 'workspace', connectionId: 'connection', provider: 'DIKIDI', externalId: '100', displayName: 'Test project',
    zoneId: null, enabled: true, connectionState: 'CONNECTED', version: 3, capabilities: [{ code: 'READ_SERVICES', availability: 'AVAILABLE' }] }
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
        getCrmTypes: async () => ['DIKIDI'], getCrmConnections: async () => [connection], getOrganizationCrmProjects: async () => [project],
        createCrmConnection: async (...args) => { calls.push(['create', ...args]); return connection },
        patchCrmConnection: async (...args) => { calls.push(['patch', ...args]); return { ...connection, ...args[1], version: 5 } },
        deleteCrmConnection: async (...args) => { calls.push(['delete', ...args]) },
        verifyCrmConnection: async (...args) => { calls.push(['verify', ...args]); return connection },
        getCrmProjects: async () => [project], getCrmMasters: async () => page(), getCrmServices: async () => page([]),
        getCrmEmployeeCandidates: async () => { calls.push(['candidates']); return [candidate] },
        checkCrmProject: async (_, id) => ({ externalId: id, displayName: 'Verified project', currency: 'RUB', zoneId: null }),
        bindCrmProject: async (...args) => { calls.push(['bind', ...args]); return project },
        patchCrmProject: async (...args) => { calls.push(['patchProject', ...args]); return project },
        changeCrmProjectConnection: async (...args) => { calls.push(['rebind', ...args]); return project },
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

test('binding requires an explicit preview and changing the ID discards it', async t => {
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel', { getCrmProjects: async () => [] })
    await change(ui, 'Подключение DIKIDI', 'connection'); await change(ui, 'ID проекта DIKIDI', '100')
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.ok(button(ui, 'Привязать проект')); assert.equal(calls.length, 0)
    await act(async () => button(ui, 'Назад').props.onClick()); await change(ui, 'ID проекта DIKIDI', '200')
    assert.equal(button(ui, 'Привязать проект'), undefined)
    await act(async () => ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await change(ui, 'Часовой пояс проекта', 'Europe/Moscow')
    await act(async () => button(ui, 'Привязать проект').props.onClick())
    assert.deepEqual(calls[0].slice(0,3), ['bind', 'workspace', { connectionId: 'connection', externalProjectId: '200', zoneId: 'Europe/Moscow' }])
})
test('disabled binding occupies the workspace; disabling requires confirmation and preserves identity', async t => {
    const { ui, calls } = await setup(t, 'WorkspaceCrmPanel')
    assert.equal(ui.root.findAllByProps({ 'aria-label': 'ID проекта DIKIDI' }).length, 0)
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
    assert.equal(ui.root.findAllByProps({ 'aria-label': 'ID проекта DIKIDI' }).length, 0)
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


