const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { create, act } = require('react-test-renderer')
const { MemoryRouter, Routes, Route } = require('react-router-dom')
const load = require('./loadInboxModule.cjs')
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const text = value => value == null ? '' : typeof value === 'string' ? value : Array.isArray(value)
    ? value.map(text).join('') : text(value.children)
const empty = () => null

async function fixture(t, page, api = {}, route = '/page') {
    const previousWindow = global.window
    global.window = Object.assign(new EventTarget(), { setTimeout, clearTimeout })
    const invalidation = load('../../api/events/InvalidationContext.tsx')
    const scopeModule = load('../../api/events/uiScope.ts')
    const { InvalidationCoordinator } = load('../../api/events/invalidationCoordinator.ts')
    const coordinator = new InvalidationCoordinator(() => true, 0, 0)
    const resource = load('../../api/events/useUiResource.ts', { './InvalidationContext': invalidation, './uiScope': scopeModule })
    const auth = { currentEmployee: { id: 'admin', organizationId: 'org', role: 'SUPER_ADMIN' }, sessionEpoch: 1, isSuperAdmin: true }
    const workspace = { currentWorkspace: { id: 'workspace', name: 'Workspace' }, workspaces: [{ id: 'workspace', name: 'Workspace' }],
        addWorkspace() {}, updateWorkspace() {}, removeWorkspace() {}, setCurrentWorkspaceId() {} }
    let owner = {}, renderer
    let scope = { owner, isCurrent: () => true, workspaceId: 'workspace', requestBudget: api.budget }
    const mocks = {
        '../api/events/useUiResource': resource, '../api/events/uiScope': scopeModule,
        '../auth/AuthContext': { useAuth: () => auth }, '../workspace/WorkspaceContext': { useWorkspace: () => workspace },
        '../api/clientApi': { getClients: async () => [], searchClients: async () => [], ...api.clients },
        '../api/channelApi': { getWorkspaceChannels: async () => [], ...api.channels },
        '../api/employeeApi': { getEmployeesByOrganization: async () => [], getEmployeeWorkspaces: async () => [], getWorkspaceEmployees: async () => [], ...api.employees },
        '../api/employeeWorkspaceApi': { getOrganizationMemberships: async () => [], getWorkspaceMemberships: async () => [], ...api.memberships },
        '../api/workspaceApi': { getAllWorkspaces: async () => [], getWorkspaceStatistics: async () => [], ...api.workspaces },
        '../api/conversationApi': { getWorkspaceConversations: async () => [], ...api.conversations },
        '../api/telegramChannelApi': {},
        '../components/clients/ClientCreateForm': { default: empty },
        '../components/clients/ClientList': { default: ({ clients }) => React.createElement('div', { id: 'directory-data' }, JSON.stringify(clients)) },
        '../components/clients/ClientAccountsSection': { default: empty },
        '../components/clients/ClientAvatar': { default: empty },
        '../components/clients/ClientAccountAvatar': { default: empty },
        '../components/employees/EmployeeAvatar': { default: empty },
        '../components/common/platform/PlatformIcon': { default: empty },
        '../components/workspaces/WorkspaceAvatar': { default: empty },
        '../components/channels/channelLabels': { getChannelStatusLabel: value => value, getChannelTypeLabel: value => value },
        '../components/channels/ChannelAccountAvatar': { ChannelAccountAvatar: empty },
    }
    for (const name of ['ChannelConnectionDialog', 'ChannelDetailsDialog', 'CreateChannelDialog', 'DeleteChannelDialog', 'EditChannelDialog'])
        mocks['../components/channels/' + name] = { [name]: empty }
    for (const name of ['EmployeeCreateDialog', 'EmployeeDetailsDialog']) mocks['../components/employees/' + name] = { default: empty }
    for (const name of ['CreateWorkspaceDialog', 'EditWorkspaceDialog', 'DeleteWorkspaceDialog']) mocks['../components/workspaces/' + name] = { [name]: empty }
    const Page = load('../../pages/' + page + '.tsx', mocks).default
    const tree = () => React.createElement(scopeModule.UiScopeContext.Provider, { value: scope },
        React.createElement(invalidation.InvalidationContext.Provider, { value: coordinator },
            React.createElement(MemoryRouter, { initialEntries: [route] }, React.createElement(Routes, null,
                React.createElement(Route, { path: route.includes('client-1') ? '/clients/:clientId' : '*', element: React.createElement(Page) })))))
    t.after(async () => { if (renderer) await act(async () => renderer.unmount()); coordinator.close(); global.window = previousWindow })
    await act(async () => { renderer = create(tree()); await flush() })
    return {
        renderer, text: () => text(renderer.toJSON()),
        invalidate: async type => act(async () => { coordinator.invalidate({ type }); await coordinator.reconcileAvailable(); await flush() }),
        beginInvalidation: async type => act(async () => { coordinator.invalidate({ type }); void coordinator.reconcileAvailable(); await flush() }),
        switchAccount: async () => act(async () => {
            auth.currentEmployee = { id: 'second', organizationId: 'other-org', role: 'SUPER_ADMIN' }; auth.sessionEpoch++
            owner = {}; scope = { owner, isCurrent: () => true, workspaceId: 'workspace' }; renderer.update(tree()); await flush()
        }),
    }
}

test('client directory refreshes on metadata and cannot apply the previous accounts delayed response', async t => {
    let rows = [{ id: 'client', firstName: 'Initial' }], calls = 0
    const held = deferred(), signals = []
    const ui = await fixture(t, 'ClientsPage', { clients: { getClients: async signal => {
        signals.push(signal); return ++calls === 2 ? held.promise : rows
    } } })
    assert.match(ui.text(), /Initial/)
    await act(async () => { ui.renderer.root.findByType('input').props.onChange({ target: { value: '' } }); await flush() })
    // Keep the old request pending while the wrapper changes account and starts a new owner.
    await ui.beginInvalidation('clients.invalidated')
    rows = [{ id: 'new-client', firstName: 'New account' }]
    await ui.switchAccount()
    assert.equal(signals[1].aborted, true)
    await act(async () => { held.resolve([{ id: 'private', firstName: 'Old private data' }]); await flush() })
    assert.match(ui.text(), /New account/); assert.doesNotMatch(ui.text(), /Old private data/)
    rows = [{ id: 'new-client', firstName: 'Remote update' }]
    await ui.invalidate('clients.invalidated'); assert.match(ui.text(), /Remote update/)
})

test('channel directory and its open read-only detail update remotely and close a deleted target', async t => {
    let status = 'CONNECTED', calls = 0, deleted = false
    const ui = await fixture(t, 'ChannelsPage', { channels: { getWorkspaceChannels: async (_, signal) => {
        assert.equal(signal.aborted, false); calls++
        return deleted ? [] : [{ id: 'channel', workspaceId: 'workspace', name: 'Shared channel', type: 'TELEGRAM', status, account: null }]
    } } })
    assert.match(ui.text(), /CONNECTED/)
    await act(async () => { ui.renderer.root.findAllByType('button').find(button => button.children.join('').trim() === 'Подробнее').props.onClick(); await flush() })
    const detail = () => ui.renderer.root.find(node => 'workspaceName' in node.props && 'channel' in node.props)
    assert.equal(detail().props.channel.status, 'CONNECTED')
    status = 'ERROR'; await ui.invalidate('channels.invalidated')
    assert.match(ui.text(), /ERROR/); assert.equal(calls, 2)
    assert.equal(detail().props.channel.status, 'ERROR')
    deleted = true; await ui.invalidate('channels.invalidated')
    assert.equal(detail().props.channel, null)
})

test('a 320 employee directory uses two requests within the production HTTP budget and refreshes memberships', async t => {
    const { RequestBudget } = load('../../api/events/requestBudget.ts')
    t.mock.timers.enable({ apis: ['setTimeout'] })
    let now = 1_000_000, calls = 0, supervisor = false
    t.mock.method(Date, 'now', () => now)
    const budget = new RequestBudget(4, 128, 100)
    t.after(() => budget.close())
    const get = async (data, signal) => (await budget.fetch('/directory', { signal }, async () => { calls++; return new Response(JSON.stringify(data)) })).json()
    const people = Array.from({ length: 320 }, (_, index) => ({ id: 'employee-' + index, firstName: 'Employee ' + index,
        lastName: '', role: 'EMPLOYEE', email: 'employee-' + index + '@test.local', enabled: true }))
    const ui = await fixture(t, 'EmployeesPage', { budget, employees: {
        getEmployeesByOrganization: async (_, signal) => get(people, signal),
        getEmployeeWorkspaces: async () => { throw Error('Per-employee fanout is forbidden') },
    }, memberships: {
        getOrganizationMemberships: async (_, signal) => get(people.map(employee => ({ employeeId: employee.id, workspaceId: 'workspace', isSupervisor: supervisor })), signal),
    } })
    await act(async () => { for (let i = 0; i < 5; i++) { now += 100; t.mock.timers.tick(100); await flush() } })
    assert.match(ui.text(), /Employee 319/); assert.equal(calls, 2)
    const supervisorTags = () => ui.renderer.root.findAll(node => node.props.className?.split(' ').includes('employee-workspace-tag-supervisor'))
    assert.equal(supervisorTags().length, 0)
    supervisor = true
    await ui.beginInvalidation('employees.invalidated')
    await act(async () => { for (let i = 0; i < 5; i++) { now += 100; t.mock.timers.tick(100); await flush() } })
    assert.match(ui.text(), /Employee 319/); assert.equal(calls, 4)
    assert.equal(supervisorTags().length, 320)
})

test('a 320 workspace directory reads one list and one aggregate and reconciles remote message statistics', async t => {
    let calls = 0, unread = 7
    const rows = Array.from({ length: 320 }, (_, index) => ({ id: 'workspace-' + index, name: 'Workspace ' + index }))
    const ui = await fixture(t, 'WorkspacesPage', { workspaces: {
        getAllWorkspaces: async () => { calls++; return rows },
        getWorkspaceStatistics: async () => { calls++; return rows.map(row => ({ workspaceId: row.id,
            employees: 3, channels: 2, conversations: 4, unread, lastActivity: null })) },
    }, employees: { getWorkspaceEmployees: async () => { throw Error('Per-workspace fanout is forbidden') } } })
    assert.match(ui.text(), /Workspace 319/); assert.equal(calls, 2)
    const displayedUnread = () => ui.renderer.root.findAllByProps({ className: 'workspace-stats' })[0].findAllByType('strong')[3].children.join('')
    assert.equal(displayedUnread(), '7')
    unread = 13; await ui.invalidate('messages.invalidated')
    assert.match(ui.text(), /Workspace 319/); assert.equal(displayedUnread(), '13'); assert.equal(calls, 4)
})

test('client details keep the unsaved edit while background account metadata reconciles', async t => {
    let firstName = 'Server name'
    const ui = await fixture(t, 'ClientDetailsPage', { clients: {
        getClient: async () => ({ id: 'client-1', firstName, lastName: 'Contact', phoneList: [], enabled: true, avatarUrl: null,
            createdAt: '2026-10-26T10:00:00Z', updatedAt: '2026-10-26T10:00:00Z' }),
        getClientAccountsByClient: async () => [], getClientConversations: async () => [],
    } }, '/clients/client-1')
    const button = label => ui.renderer.root.findAllByType('button').find(node => text(node.toJSON ? node.toJSON() : { children: node.children }) === label)
    await act(async () => { button('Редактировать').props.onClick(); await flush() })
    const input = ui.renderer.root.findAllByType('input').find(node => node.props.value === 'Server name')
    await act(async () => input.props.onChange({ target: { value: 'Unsaved employee draft' } }))
    firstName = 'Remote server name'; await ui.invalidate('clients.invalidated')
    assert.ok(ui.renderer.root.findAllByType('input').some(node => node.props.value === 'Unsaved employee draft'))
    await act(async () => { button('Отмена').props.onClick(); await flush() })
    assert.match(ui.text(), /Remote server name/)
})
