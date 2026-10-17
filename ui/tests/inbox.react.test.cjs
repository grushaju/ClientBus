const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const { MemoryRouter, useLocation, useNavigate } = require('react-router-dom')
const load = require('./loadInboxModule.cjs')
process.env.TZ = 'Europe/Moscow'

function deferred() {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
}

function conversation(id = 'conversation-1', extra = {}) {
    return {
        id, workspaceId: 'workspace', channelAccountId: 'channel-account', clientAccountId: 'client-account',
        assignedEmployeeId: 'employee', lastMessageAt: '2026-10-17T10:00:00Z',
        unreadCount: 1, ...extra,
    }
}

function response(conversations = [conversation()]) {
    return { content: conversations, page: 0, hasNext: false }
}

function text(node) {
    if (node == null || typeof node === 'boolean') return ''
    if (typeof node === 'string' || typeof node === 'number') return String(node)
    if (Array.isArray(node)) return node.map(text).join('')
    return text(node.children)
}

async function inbox(t, options = {}) {
    const previousWindow = global.window
    const events = new EventTarget()
    events.confirm = () => true
    global.window = events
    let search = options.search ?? (async () => response())
    const calls = []
    const mounts = new Map()
    const groupCalls = []
    const membershipCalls = []
    const membership = new Set(options.groupIds ?? [])
    const saved = [...(options.savedFilters ?? [])]
    const renameCalls = []
    let groups = [...(options.groups ?? [])]
    let workspace = { id: 'workspace', name: 'Workspace' }
    let renderer, location, navigate

    function Row({ item }) {
        const id = item.conversation.id
        const [open, setOpen] = React.useState(false)
        React.useEffect(() => { mounts.set(id, (mounts.get(id) ?? 0) + 1) }, [id])
        return React.createElement('article', { 'data-id': id },
            React.createElement('button', { onClick: () => setOpen(current => !current) }, 'toggle-row-menu'),
            open ? 'menu-open' : 'menu-closed')
    }
    function RouteProbe() {
        location = useLocation()
        navigate = useNavigate()
        return null
    }
    const api = {
        searchInbox: async (workspaceId, request) => {
            calls.push({ workspaceId, ...request })
            return search(workspaceId, request)
        },
        getGroups: async workspaceId => {
            groupCalls.push('get')
            return options.loadGroups ? options.loadGroups(workspaceId) : [...groups]
        },
        createGroup: async (workspaceId, name, icon) => {
            const group = { id: 'created-group', name, icon }
            groups.push(group)
            return group
        },
        renameGroup: async (workspaceId, id, name, icon) => {
            renameCalls.push({ id, name, icon })
            groups = groups.map(group => group.id === id ? { ...group, name, icon } : group)
            return groups.find(group => group.id === id)
        },
        deleteGroup: async (workspaceId, id) => { groups = groups.filter(group => group.id !== id) },
        getConversationGroupIds: async () => [...membership],
        addConversationToGroup: async (workspaceId, groupId, conversationId) => {
            membershipCalls.push(['add', workspaceId, groupId, conversationId])
            membership.add(groupId)
        },
        removeConversationFromGroup: async (workspaceId, groupId, conversationId) => {
            membershipCalls.push(['remove', workspaceId, groupId, conversationId])
            membership.delete(groupId)
        },
    }
    const Workspace = load('InboxWorkspace.tsx', {
        '../../auth/AuthContext': {
            useAuth: () => ({
                currentEmployee: { id: 'employee' }, employeeWorkspaces: [],
                isEmployee: !options.superAdmin, isSuperAdmin: !!options.superAdmin,
            }),
        },
        '../../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: workspace }) },
        '../../../api/conversationApi': api,
        '../../../api/channelApi': { getWorkspaceChannels: async () => [] },
        '../../../api/clientAccountApi': { getClientAccountsByIds: async ids => ids.map(id => ({ id })) },
        '../../../api/savedConversationFilterApi': {
            getSavedConversationFilters: async (id, type) => type === 'PRIVATE'
                ? (options.loadPrivateFilters ? options.loadPrivateFilters(id) : [...saved]) : [],
            createSavedConversationFilter: async (workspaceId, request) => {
                const filter = { id: 'created-filter', ...request }
                saved.push(filter)
                return filter
            },
        },
        './ConversationView': { default: () => React.createElement('section', null, 'thread') },
        './NewConversationDialog': { default: () => null },
        '../clients/ClientAccountAvatar': { default: () => null },
        ...(options.realRows ? {} : { '../ConversationListItem': { default: Row } }),
    }).default
    t.after(async () => {
        if (renderer) await act(async () => renderer.unmount())
        global.window = previousWindow
    })
    const tree = () => React.createElement(MemoryRouter, {
        initialEntries: options.initialEntries ?? ['/inbox/conversation-1?tab=mine'],
    }, React.createElement(Workspace), React.createElement(RouteProbe))
    await act(async () => { renderer = create(tree()) })
    const button = label => renderer.root.findAllByType('button').find(node => text(node) === label)
    return {
        renderer, calls, mounts, groupCalls, membershipCalls, renameCalls,
        setSearch: handler => { search = handler },
        button,
        click: async label => {
            assert.ok(button(label), `Missing button: ${label}`)
            await act(async () => button(label).props.onClick())
        },
        submit: async () => act(async () => renderer.root.findByType('form').props.onSubmit({ preventDefault() {} })),
        emit: async (updated = conversation()) => act(async () => {
            const event = new Event('clientbus:conversation-updated')
            event.detail = updated
            events.dispatchEvent(event)
        }),
        navigate: async value => act(async () => navigate(value)),
        switchWorkspace: async next => act(async () => { workspace = next; renderer.update(tree()) }),
        get location() { return location },
        row: (id = 'conversation-1') => renderer.root.findByProps({ 'data-id': id }),
        list: () => renderer.root.findByProps({ className: 'conversation-list-items' }),
    }
}

test('Apply repeats an identical failed search; Reset also reloads unchanged conditions', async t => {
    const ui = await inbox(t, { search: async () => { throw Error('Temporary failure') } })
    assert.equal(ui.calls.length, 1)
    assert.ok(ui.button('Повторить'))
    ui.setSearch(async () => response())
    await ui.click('Условия фильтра ▼')
    await ui.click('Применить')
    assert.equal(ui.calls.length, 2)
    assert.equal(ui.row().props['data-id'], 'conversation-1')
    await ui.click('Сбросить')
    assert.equal(ui.calls.length, 3)
})

test('Retry button recovers an initial failed search without changing conditions', async t => {
    const ui = await inbox(t, { search: async () => { throw Error('Offline') } })
    ui.setSearch(async () => response())
    await ui.click('Повторить')
    assert.equal(ui.calls.length, 2)
    assert.ok(ui.row())
    assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 0)
})

test('read event refreshes in background without remounting list or losing row state', async t => {
    const ui = await inbox(t)
    const list = ui.list()
    await act(async () => ui.row().findByType('button').props.onClick())
    const refresh = deferred()
    ui.setSearch(() => refresh.promise)
    await ui.emit(conversation('conversation-1', { unreadCount: 0 }))
    assert.equal(ui.list(), list)
    assert.equal(ui.list().props['aria-busy'], true)
    assert.ok(text(ui.row()).includes('menu-open'))
    assert.equal(ui.mounts.get('conversation-1'), 1)
    await act(async () => refresh.resolve(response([conversation('conversation-1', { unreadCount: 0 })])))
    assert.equal(ui.list(), list)
    assert.equal(ui.list().props['aria-busy'], false)
    assert.ok(text(ui.row()).includes('menu-open'))
    assert.equal(ui.mounts.get('conversation-1'), 1)
})

test('background failure keeps cards mounted; Retry replaces data in place', async t => {
    const ui = await inbox(t)
    const list = ui.list()
    ui.setSearch(async () => { throw Error('Refresh failed') })
    await ui.emit()
    assert.equal(ui.list(), list)
    assert.ok(ui.row())
    assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 1)
    ui.setSearch(async () => response())
    await ui.click('Повторить')
    assert.equal(ui.list(), list)
    assert.equal(ui.mounts.get('conversation-1'), 1)
    assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 0)
})

test('changing conditions hides previous cards and ignores an obsolete refresh result', async t => {
    const ui = await inbox(t)
    const obsolete = deferred()
    ui.setSearch(() => obsolete.promise)
    await ui.emit()
    const current = deferred()
    ui.setSearch(() => current.promise)
    await ui.click('Условия фильтра ▼')
    await act(async () => ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: 'new query' } }))
    await ui.click('Применить')
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
    assert.equal(ui.calls.at(-1).search, 'new query')
    await act(async () => obsolete.resolve(response([conversation('obsolete')])) )
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
    await act(async () => current.resolve(response([conversation('current')])) )
    assert.ok(ui.row('current'))
    assert.equal(ui.renderer.root.findAllByProps({ 'data-id': 'obsolete' }).length, 0)
})

test('switching from a saved filter removes hidden accounts but keeps visible criteria', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'saved', name: 'Saved accounts', filterDefinition: {
        view: 'MINE', clientAccountIds: ['restricted-account'], search: 'visible search',
        channelAccountIds: ['channel-account'], sort: 'LAST_MESSAGE_ASC',
    } }] })
    await ui.click('Saved accounts')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['restricted-account'])
    await ui.click('Свободные')
    const request = ui.calls.at(-1)
    assert.equal(request.view, 'UNASSIGNED')
    assert.deepEqual(request.clientAccountIds, [])
    assert.equal(request.search, 'visible search')
    assert.deepEqual(request.channelAccountIds, ['channel-account'])
    assert.equal(request.sort, 'LAST_MESSAGE_ASC')
})

test('employee groups render after loading; group create, rename, select and delete stay connected', async t => {
    const ui = await inbox(t, { groups: [{ id: 'existing-group', name: 'Existing group', icon: 'users.svg' }] })
    assert.ok(ui.button('Existing group'))
    assert.ok(ui.button('+ Группа'))
    await ui.click('Existing group')
    assert.equal(ui.calls.at(-1).view, 'GROUP')
    assert.equal(ui.calls.at(-1).groupId, 'existing-group')
    await ui.click('+ Группа')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'New group' } }))
    await ui.submit()
    assert.ok(ui.button('New group'))
    await ui.click('New group')
    assert.equal(ui.calls.at(-1).groupId, 'created-group')
    await ui.click('Переименовать')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Renamed group' } }))
    await ui.submit()
    assert.ok(ui.button('Renamed group'))
    await ui.click('Удалить')
    assert.equal(ui.button('Renamed group'), undefined)
    assert.equal(new URLSearchParams(ui.location.search).get('tab'), 'mine')
})

test('SuperAdmin has All view and does not load employee personal groups', async t => {
    const ui = await inbox(t, { superAdmin: true, groups: [{ id: 'g', name: 'Personal', icon: 'users.svg' }] })
    assert.equal(ui.calls.at(-1).view, 'ALL')
    assert.equal(ui.groupCalls.length, 0)
    assert.equal(ui.button('+ Группа'), undefined)
    assert.equal(ui.button('Personal'), undefined)
})

test('real membership menu keeps selection and stays mounted after add and remove', async t => {
    const ui = await inbox(t, { realRows: true, groups: [{ id: 'g', name: 'My group', icon: 'heart.svg' }] })
    await ui.click('В группу')
    const menu = ui.renderer.root.findByProps({ className: 'conversation-list-item-group-menu' })
    const checkbox = () => ui.renderer.root.findByProps({ type: 'checkbox' })
    assert.equal(checkbox().props.checked, false)
    await act(async () => checkbox().props.onChange({ target: { checked: true } }))
    assert.equal(ui.renderer.root.findByProps({ className: 'conversation-list-item-group-menu' }), menu)
    assert.equal(checkbox().props.checked, true)
    await act(async () => checkbox().props.onChange({ target: { checked: false } }))
    assert.equal(ui.renderer.root.findByProps({ className: 'conversation-list-item-group-menu' }), menu)
    assert.equal(checkbox().props.checked, false)
    assert.deepEqual(ui.membershipCalls, [
        ['add', 'workspace', 'g', 'conversation-1'],
        ['remove', 'workspace', 'g', 'conversation-1'],
    ])
})

test('removing from the active group removes the row when the server excludes it', async t => {
    const ui = await inbox(t, {
        realRows: true, groups: [{ id: 'g', name: 'My group', icon: 'users.svg' }], groupIds: ['g'],
    })
    await ui.click('My group')
    await ui.click('В группу')
    const checkbox = ui.renderer.root.findByProps({ type: 'checkbox' })
    assert.equal(checkbox.props.checked, true)
    ui.setSearch(async () => response([]))
    await act(async () => checkbox.props.onChange({ target: { checked: false } }))
    assert.equal(ui.calls.at(-1).view, 'GROUP')
    assert.equal(ui.calls.at(-1).groupId, 'g')
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-item-row' }).length, 0)
})

test('a late initial GET cannot erase a group created while loading', async t => {
    const initial = deferred()
    const ui = await inbox(t, { loadGroups: () => initial.promise })
    await ui.click('+ Группа')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Created while loading' } }))
    await ui.submit()
    assert.ok(ui.button('Created while loading'))
    await act(async () => initial.resolve([{ id: 'old', name: 'Existing on server', icon: 'users.svg' }]))
    assert.ok(ui.button('Created while loading'))
    assert.ok(ui.button('Existing on server'))
})

test('a late initial GET merges a saved filter without erasing or duplicating it', async t => {
    const initial = deferred()
    const ui = await inbox(t, { loadPrivateFilters: () => initial.promise })
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'Created filter' } }))
    await ui.submit()
    assert.ok(ui.button('Created filter'))
    await act(async () => initial.resolve([
        { id: 'old', name: 'Old filter', filterDefinition: { view: 'MINE' } },
        { id: 'created-filter', name: 'Stale filter name', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } },
    ]))
    assert.ok(ui.button('Created filter'))
    assert.ok(ui.button('Old filter'))
    assert.equal(ui.button('Stale filter name'), undefined)
    assert.equal(ui.renderer.root.findAllByType('button').filter(node => text(node) === 'Created filter').length, 1)
})

test('Back and Forward change representation without retaining hidden saved conditions', async t => {
    const ui = await inbox(t, {
        initialEntries: ['/inbox/conversation-1?tab=unassigned', '/inbox/conversation-1?tab=mine'],
        savedFilters: [{ id: 'saved', name: 'Saved restriction', filterDefinition: {
            view: 'MINE', clientAccountIds: ['hidden-account'], search: 'visible condition',
        } }],
    })
    await ui.click('Saved restriction')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['hidden-account'])
    await ui.navigate(-1)
    assert.equal(ui.calls.at(-1).view, 'UNASSIGNED')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, [])
    assert.equal(ui.calls.at(-1).search, 'visible condition')
    assert.ok(ui.calls.filter(call => call.view === 'UNASSIGNED').every(call => call.clientAccountIds.length === 0))
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 0)
    await ui.navigate(1)
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, [])
})

test('selecting a saved filter in another representation preserves its own restrictions', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'saved', name: 'Saved free', filterDefinition: {
        view: 'UNASSIGNED', clientAccountIds: ['saved-account'],
    } }] })
    await ui.click('Saved free')
    assert.equal(ui.calls.at(-1).view, 'UNASSIGNED')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['saved-account'])
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 1)
})

test('selecting another group closes the editor instead of saving the previous group', async t => {
    const ui = await inbox(t, { groups: [
        { id: 'a', name: 'Group A', icon: 'users.svg' }, { id: 'b', name: 'Group B', icon: 'heart.svg' },
    ] })
    await ui.click('Group A')
    await ui.click('Переименовать')
    await ui.click('Group B')
    assert.equal(ui.renderer.root.findAllByProps({ placeholder: 'Название группы' }).length, 0)
    await ui.click('Переименовать')
    assert.equal(ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.value, 'Group B')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Renamed B' } }))
    await ui.submit()
    assert.deepEqual(ui.renameCalls.map(call => [call.id, call.name]), [['b', 'Renamed B']])
})

test('date-only editor restores local dates and includes the entire selected day', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'dates', name: 'Saved dates', filterDefinition: {
        view: 'MINE', activityFrom: '2026-10-17T09:30:12.123Z',
    } }] })
    await ui.click('Saved dates')
    await ui.click('Условия фильтра ▼')
    const fields = () => ui.renderer.root.findAllByProps({ type: 'date' })
    assert.equal(fields().length, 2)
    assert.equal(ui.renderer.root.findAllByProps({ type: 'datetime-local' }).length, 0)
    assert.equal(fields()[0].props.value, '2026-10-17')
    await act(async () => {
        fields()[0].props.onChange({ target: { value: '2026-10-18' } })
        fields()[1].props.onChange({ target: { value: '2026-10-18' } })
    })
    await ui.click('Применить')
    assert.equal(ui.calls.at(-1).activityFrom, '2026-10-17T21:00:00.000Z')
    assert.equal(ui.calls.at(-1).activityTo, '2026-10-18T20:59:59.999999Z')
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'One day' } }))
    await ui.submit()
    await ui.click('Сбросить')
    await ui.click('One day')
    assert.equal(fields()[0].props.value, '2026-10-18')
    assert.equal(fields()[1].props.value, '2026-10-18')
    assert.equal(ui.calls.at(-1).activityFrom, '2026-10-17T21:00:00.000Z')
    assert.equal(ui.calls.at(-1).activityTo, '2026-10-18T20:59:59.999999Z')
})

test('invalid date range does not replace the applied filter or issue an API call', async t => {
    const ui = await inbox(t)
    await ui.click('Условия фильтра ▼')
    const fields = () => ui.renderer.root.findAllByProps({ type: 'date' })
    await act(async () => {
        fields()[0].props.onChange({ target: { value: '2026-10-18' } })
        fields()[1].props.onChange({ target: { value: '2026-10-17' } })
    })
    const before = ui.calls.length
    await ui.click('Применить')
    assert.equal(ui.calls.length, before)
    assert.ok(ui.renderer.root.findAllByProps({ role: 'alert' }).some(node => text(node).includes('Начало периода')))
})

test('a late initial GET does not undo renaming or resurrect a deleted group', async t => {
    const initial = deferred()
    const ui = await inbox(t, { loadGroups: () => initial.promise })
    await ui.click('+ Группа')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Initial name' } }))
    await ui.submit()
    await ui.click('Initial name')
    await ui.click('Переименовать')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Final name' } }))
    await ui.submit()
    assert.ok(ui.button('Final name'))
    await ui.click('Удалить')
    await act(async () => initial.resolve([{ id: 'created-group', name: 'Initial name', icon: 'users.svg' }]))
    assert.equal(ui.button('Initial name'), undefined)
    assert.equal(ui.button('Final name'), undefined)
})

test('a saved filter without an explicit view follows the backend MINE default', async t => {
    const ui = await inbox(t, {
        initialEntries: ['/inbox/conversation-1?tab=unassigned'],
        savedFilters: [{ id: 'legacy', name: 'Default view', filterDefinition: { clientLink: 'WITHOUT_CLIENT' } }],
    })
    await ui.click('Default view')
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.equal(ui.calls.at(-1).clientLink, 'WITHOUT_CLIENT')
})

test('late search, group and filter responses from a previous Workspace cannot replace the current scope', async t => {
    const previousGroups = deferred(), previousFilters = deferred(), previousSearch = deferred()
    const ui = await inbox(t, {
        loadGroups: id => id === 'workspace' ? previousGroups.promise : [{ id: 'new-group', name: 'Current group', icon: 'users.svg' }],
        loadPrivateFilters: id => id === 'workspace' ? previousFilters.promise : [{ id: 'new-filter', name: 'Current filter', filterDefinition: { view: 'MINE' } }],
        search: async id => id === 'workspace' ? previousSearch.promise : response([conversation('current-conversation', { workspaceId: id })]),
    })
    await ui.switchWorkspace({ id: 'workspace-2', name: 'Next Workspace' })
    assert.ok(ui.button('Current group'))
    assert.ok(ui.button('Current filter'))
    assert.ok(ui.row('current-conversation'))
    await act(async () => {
        previousGroups.resolve([{ id: 'old-group', name: 'Obsolete group', icon: 'users.svg' }])
        previousFilters.resolve([{ id: 'old-filter', name: 'Obsolete filter', filterDefinition: { view: 'MINE' } }])
        previousSearch.resolve(response([conversation('obsolete-conversation')]))
    })
    assert.equal(ui.button('Obsolete group'), undefined)
    assert.equal(ui.button('Obsolete filter'), undefined)
    assert.equal(ui.renderer.root.findAllByProps({ 'data-id': 'obsolete-conversation' }).length, 0)
    assert.ok(ui.row('current-conversation'))
    assert.equal(ui.calls.at(-1).workspaceId, 'workspace-2')
})
