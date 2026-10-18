const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

async function dialog(t, memberships, overrides = {}) {
    const calls = []
    const employee = { id: 'employee', firstName: 'Name', lastName: '', username: 'name', email: 'name@example.com', phone: '', enabled: true }
    const api = {
        getEmployeeWorkspaces: async () => memberships,
        assignEmployeeWorkspace: async (employeeId, workspaceId, isSupervisor) => {
            calls.push(['assign', workspaceId, isSupervisor])
            return { employeeId, workspaceId, isSupervisor }
        },
        setEmployeeWorkspaceSupervisor: async (employeeId, workspaceId, isSupervisor) => {
            calls.push(['supervisor', workspaceId, isSupervisor])
            return { employeeId, workspaceId, isSupervisor }
        },
        removeEmployeeWorkspace: async (_, workspaceId) => calls.push(['remove', workspaceId]),
        ...overrides,
    }
    const Dialog = load('../employees/EmployeeDetailsDialog.tsx', {
        '../../api/employeeWorkspaceApi': api,
        '../../api/employeeApi': {},
        './EmployeeAvatar': { default: () => null },
    }).default
    let updated, renderer
    await act(async () => {
        renderer = create(React.createElement(Dialog, { employee, workspaceIds: memberships.map(item => item.workspaceId),
            workspaces: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }], onClose() {}, onUpdated: (...args) => { updated = args } }))
    })
    t.after(() => act(() => renderer.unmount()))
    return { calls, renderer, get updated() { return updated },
        role: name => renderer.root.findByProps({ 'aria-label': `Супервизор: ${name}` }),
        save: async () => act(async () => renderer.root.findByProps({ className: 'ui-button ui-button-primary' }).props.onClick()),
    }
}

test('employee roles load without changing an existing supervisor when saving unchanged', async t => {
    const ui = await dialog(t, [{ employeeId: 'employee', workspaceId: 'a', isSupervisor: true }])
    assert.equal(ui.role('A').props.checked, true)
    assert.equal(ui.role('B').props.disabled, true)
    await ui.save()
    assert.deepEqual(ui.calls, [])
    assert.deepEqual(ui.updated[1], ['a'])
})

test('employee supervisor changes use PATCH and new memberships include the selected role', async t => {
    const ui = await dialog(t, [{ employeeId: 'employee', workspaceId: 'a', isSupervisor: true }])
    await act(async () => ui.role('A').props.onChange({ target: { checked: false } }))
    const membership = ui.renderer.root.findAllByProps({ className: 'employee-details-workspace-membership' })[1].findByType('input')
    await act(async () => membership.props.onChange())
    assert.equal(ui.role('B').props.disabled, false)
    await act(async () => ui.role('B').props.onChange({ target: { checked: true } }))
    await ui.save()
    assert.deepEqual(ui.calls, [['supervisor', 'a', false], ['assign', 'b', true]])
})

test('failed role loading prevents saving incomplete workspace state', async t => {
    const ui = await dialog(t, [], { getEmployeeWorkspaces: async () => { throw Error('Unavailable') } })
    assert.equal(ui.renderer.root.findByProps({ className: 'ui-button ui-button-primary' }).props.disabled, true)
    await ui.save()
    assert.deepEqual(ui.calls, [])
    assert.equal(ui.updated, undefined)
})

test('adding employees to a workspace submits the supervisor flag separately for each employee', async t => {
    const calls = []
    const Dialog = load('../workspaces/WorkspaceEmployeesDialog.tsx', {
        '../../api/employeeApi': {
            getEmployeesByOrganization: async () => ['a', 'b'].map(id => ({ id, firstName: id, email: `${id}@example.com`, role: 'EMPLOYEE' })),
            getWorkspaceEmployees: async () => [],
        },
        '../../api/employeeWorkspaceApi': {
            assignEmployeeWorkspace: async (...args) => { calls.push(args) },
        },
        '../employees/EmployeeAvatar': { default: () => null },
    }).WorkspaceEmployeesDialog
    let renderer, closed = false
    await act(async () => { renderer = create(React.createElement(Dialog, {
        open: true, workspaceId: 'workspace', organizationId: 'organization', onClose: () => { closed = true }, onUpdated() {},
    })) })
    t.after(() => act(() => renderer.unmount()))
    const role = name => renderer.root.findByProps({ 'aria-label': `Супервизор: ${name}` })
    assert.equal(role('a').props.disabled, true)
    for (const member of renderer.root.findAllByProps({ className: 'workspace-employee-picker-member' })) {
        await act(async () => member.findByType('input').props.onChange())
    }
    assert.equal(role('a').props.disabled, false)
    await act(async () => role('a').props.onChange({ target: { checked: true } }))
    assert.equal(role('b').props.checked, false)
    await act(async () => renderer.root.findByProps({ className: 'ui-button ui-button-primary' }).props.onClick())
    assert.deepEqual(calls, [['a', 'workspace', true], ['b', 'workspace', false]])
    assert.equal(closed, true)
})
