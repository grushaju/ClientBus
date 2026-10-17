const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const { MemoryRouter, Route, Routes, useLocation, useNavigate } = require('react-router-dom')
const load = require('./loadInboxModule.cjs')
const focusDom = require('./inboxFocusDom.cjs')
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

function viewport(events, initialWidth = 1024) {
    let width = initialWidth
    const queries = new Map()
    const matches = query => {
        const max = /max-width:\s*(\d+)px/.exec(query)
        const min = /min-width:\s*(\d+)px/.exec(query)
        return !!(max || min) && (!max || width <= Number(max[1])) && (!min || width >= Number(min[1]))
    }
    events.matchMedia = query => {
        if (!queries.has(query)) {
            const media = new EventTarget()
            Object.defineProperties(media, { matches: { get: () => matches(query) }, media: { value: query } })
            queries.set(query, media)
        }
        return queries.get(query)
    }
    return nextWidth => {
        const previous = new Map([...queries.keys()].map(query => [query, matches(query)]))
        width = nextWidth
        for (const [query, media] of queries) {
            if (previous.get(query) !== matches(query)) {
                media.dispatchEvent(Object.assign(new Event('change'), { matches: matches(query), media: query }))
            }
        }
    }
}

async function inbox(t, options = {}) {
    const previousWindow = global.window
    const events = new EventTarget()
    const resize = viewport(events, options.width ?? 1024)
    events.confirm = () => true
    global.window = events
    let search = options.search ?? (async () => response())
    const calls = []
    const mounts = new Map()
    const groupCalls = []
    const membershipCalls = []
    const membership = new Set(options.groupIds ?? [])
    const saved = [...(options.savedFilters ?? [])]
    const savedCalls = []
    const filterLoads = []
    const renameCalls = []
    const readCalls = [], conversationLoads = [], workspaceSelections = []
    let groups = [...(options.groups ?? [])]
    let workspace = { id: 'workspace', name: 'Workspace' }
    let renderer, location, navigate
    let threadMounts = 0

    function Thread() {
        const [draft, setDraft] = React.useState('')
        React.useEffect(() => { threadMounts++ }, [])
        return React.createElement('section', { className: 'conversation-view' },
            React.createElement('textarea', { 'aria-label': 'Черновик сообщения', value: draft,
                onChange: event => setDraft(event.target.value) }))
    }

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
        getConversation: async id => {
            conversationLoads.push(id)
            return options.loadConversation ? options.loadConversation(id) : conversation(id)
        },
        markConversationAsRead: async id => {
            readCalls.push(id)
            return options.markRead ? options.markRead(id) : conversation(id, { unreadCount: 0 })
        },
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
            if (options.renameGroup) return options.renameGroup(workspaceId, id, name, icon)
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
                currentEmployee: { id: 'employee' }, employeeWorkspaces: options.employeeWorkspaces ?? [],
                isEmployee: !options.superAdmin, isSuperAdmin: !!options.superAdmin,
            }),
        },
        '../../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: workspace,
            setCurrentWorkspaceId: id => workspaceSelections.push(id) }) },
        '../../../api/conversationApi': api,
        '../../../api/channelApi': { getWorkspaceChannels: async () => options.channels ?? [] },
        '../../../api/clientAccountApi': { getClientAccountsByIds: async ids => ids.map(id => ({ id })) },
        '../../../api/savedConversationFilterApi': {
            getSavedConversationFilters: async (id, type) => {
                filterLoads.push([id, type])
                return type === 'PRIVATE'
                    ? (options.loadPrivateFilters ? options.loadPrivateFilters(id) : [...saved])
                    : (options.loadSystemFilters ? options.loadSystemFilters(id) : options.systemFilters ?? [])
            },
            createSavedConversationFilter: async (workspaceId, request) => {
                savedCalls.push({ workspaceId, ...request })
                if (options.saveFilter) return options.saveFilter(workspaceId, request)
                const filter = { id: 'created-filter', ...request }
                saved.push(filter)
                return filter
            },
        },
        ...(options.realThread ? {
            '../../api/conversationApi': api,
            '../../api/clientAccountApi': { getClientAccount: async id => ({ id }) },
            '../../api/channelApi': { getWorkspaceChannels: async () => options.channels ?? [] },
            '../../api/employeeApi': { getWorkspaceEmployees: async () => [] },
            '../../api/messageApi': { sendOutboundMessage: async () => {} },
            './useConversationMessages': { useConversationMessages: () => ({ messages: [], loading: false,
                loadingOlder: false, hasMore: false, historyStartReached: true, error: null }) },
            './ConversationHeader': { default: () => null },
            './ConversationClientPanel': { default: () => null },
            './MessageList': { default: () => null },
        } : { './ConversationView': { default: Thread } }),
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
    }, React.createElement(Routes, null,
        React.createElement(Route, { path: '/inbox/:conversationId?', element: React.createElement(Workspace) })),
    React.createElement(RouteProbe))
    await act(async () => { renderer = create(tree(), { createNodeMock: options.createNodeMock }) })
    const button = label => renderer.root.findAllByType('button').find(node => text(node) === label)
    return {
        renderer, calls, mounts, groupCalls, membershipCalls, renameCalls, savedCalls, filterLoads, readCalls, conversationLoads, workspaceSelections,
        setSearch: handler => { search = handler },
        button,
        click: async label => {
            assert.ok(button(label), `Missing button: ${label}`)
            await act(async () => {
                const target = button(label)
                if (target.props.onClick) return target.props.onClick()
                assert.equal(target.props.type, 'submit')
                let form = target.parent
                while (form && form.type !== 'form') form = form.parent
                assert.ok(form, 'Submit button must belong to a form')
                return form.props.onSubmit({ preventDefault() {} })
            })
        },
        submit: async () => act(async () => {
            const editors = renderer.root.findAllByType('form').filter(node => node.props.className === 'conversation-list-group-form')
            assert.equal(editors.length, 1, 'Expected one open group or saved filter editor')
            return editors[0].props.onSubmit({ preventDefault() {} })
        }),
        emit: async (updated = conversation()) => act(async () => {
            const event = new Event('clientbus:conversation-updated')
            event.detail = updated
            events.dispatchEvent(event)
        }),
        navigate: async value => act(async () => navigate(value)),
        switchWorkspace: async next => act(async () => { workspace = next; renderer.update(tree()) }),
        resize: async width => act(async () => resize(width)),
        get location() { return location },
        get threadMounts() { return threadMounts },
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
    assert.equal(text(ui.renderer.root.findByProps({ className: 'conversation-list-saved-filter active' })), 'Created filter')
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

test('combined filter draft waits for Apply, saves all conditions and resets without changing representation', async t => {
    const ui = await inbox(t, { channels: [
        { id: 'telegram', name: 'Telegram', account: { id: 'account-a' } },
        { id: 'duplicate', name: 'Same account', account: { id: 'account-a' } },
        { id: 'whatsapp', name: 'WhatsApp', account: { id: 'account-b' } },
        { id: 'unconnected', name: 'No account', account: null },
    ] })
    await ui.click('Свободные')
    await ui.click('Условия фильтра ▼')
    const field = label => ui.renderer.root.findAllByType('label')
        .find(node => text(node).startsWith(label)).findByType('select')
    const channels = ui.renderer.root.findByProps({ 'aria-label': 'Каналы' })
    assert.deepEqual(channels.findAllByType('option').map(node => node.props.value), ['account-a', 'account-b'])
    const before = ui.calls.length
    await act(async () => {
        ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: '  alice  ' } })
        channels.props.onChange({ target: { selectedOptions: [{ value: 'account-a' }, { value: 'account-b' }] } })
        field('Связь с клиентом').props.onChange({ target: { value: 'WITHOUT_CLIENT' } })
        field('Сообщения').props.onChange({ target: { value: 'HAS_UNREAD' } })
        field('Сортировка').props.onChange({ target: { value: 'LAST_MESSAGE_ASC' } })
        const dates = ui.renderer.root.findAllByProps({ type: 'date' })
        dates[0].props.onChange({ target: { value: '2026-10-16' } })
        dates[1].props.onChange({ target: { value: '2026-10-17' } })
    })
    assert.equal(ui.calls.length, before)
    await ui.click('Применить')
    const expected = {
        view: 'UNASSIGNED', groupId: null, search: 'alice', channelAccountIds: ['account-a', 'account-b'],
        clientAccountIds: [], clientLink: 'WITHOUT_CLIENT', unread: 'HAS_UNREAD',
        activityFrom: '2026-10-15T21:00:00.000Z', activityTo: '2026-10-17T20:59:59.999999Z',
        sort: 'LAST_MESSAGE_ASC',
    }
    assert.deepEqual(ui.calls.at(-1), { workspaceId: 'workspace', ...expected, page: 0, size: 10 })
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' })
        .props.onChange({ target: { value: '  Combined  ' } }))
    await ui.submit()
    assert.deepEqual(ui.savedCalls, [{ workspaceId: 'workspace', name: 'Combined', filterType: 'PRIVATE', filterDefinition: expected }])
    await ui.click('Сбросить')
    assert.deepEqual(ui.calls.at(-1), {
        workspaceId: 'workspace', view: 'UNASSIGNED', groupId: null, search: null,
        channelAccountIds: [], clientAccountIds: [], clientLink: 'ANY', unread: 'ANY',
        activityFrom: null, activityTo: null, sort: 'LAST_MESSAGE_DESC', page: 0, size: 10,
    })
    await ui.click('Combined')
    assert.deepEqual(ui.calls.at(-1), { workspaceId: 'workspace', ...expected, page: 0, size: 10 })
    assert.equal(field('Сообщения').props.value, 'HAS_UNREAD')
    assert.equal(field('Сортировка').props.value, 'LAST_MESSAGE_ASC')
})

test('employee can apply a system filter and save a personal copy without changing the system definition', async t => {
    const definition = { view: 'UNASSIGNED', search: 'shared', unread: 'READ_ONLY', page: 5, size: 80 }
    const original = structuredClone(definition)
    const ui = await inbox(t, { systemFilters: [{ id: 'system', name: 'Shared filter', filterType: 'SYSTEM', filterDefinition: definition }] })
    await ui.click('Shared filter')
    assert.equal(ui.calls.at(-1).view, 'UNASSIGNED')
    assert.equal(ui.calls.at(-1).page, 0)
    assert.equal(ui.calls.at(-1).size, 10)
    await ui.click('Условия фильтра ▼')
    await act(async () => ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: 'my copy' } }))
    await ui.click('Применить')
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'My copy' } }))
    await ui.submit()
    assert.equal(ui.savedCalls[0].filterType, 'PRIVATE')
    assert.equal(ui.savedCalls[0].filterDefinition.search, 'my copy')
    assert.deepEqual(definition, original)
})

test('SuperAdmin loads system filters only and creates a system definition in the current Workspace', async t => {
    const ui = await inbox(t, { superAdmin: true })
    assert.deepEqual(ui.filterLoads, [['workspace', 'SYSTEM']])
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'For everyone' } }))
    await ui.submit()
    assert.equal(ui.savedCalls[0].filterType, 'SYSTEM')
    assert.equal(ui.savedCalls[0].filterDefinition.view, 'ALL')
})

test('failure to load system filters does not hide personal filters and permits a retry', async t => {
    let fail = true
    const ui = await inbox(t, {
        savedFilters: [{ id: 'personal', name: 'Still available', filterDefinition: { view: 'MINE', unread: 'HAS_UNREAD' } }],
        loadSystemFilters: async () => {
            if (fail) throw Error('Shared filters unavailable')
            return [{ id: 'shared', name: 'Recovered shared', filterDefinition: { view: 'UNASSIGNED' } }]
        },
    })
    assert.ok(ui.button('Still available'))
    await ui.click('Still available')
    assert.equal(ui.calls.at(-1).unread, 'HAS_UNREAD')
    fail = false
    await ui.click('Повторить загрузку фильтров')
    assert.ok(ui.button('Recovered shared'))
    assert.ok(ui.button('Still available'))
})

test('Inbox navigation occupies its own column; search, sort and conditions belong to the list', async t => {
    const ui = await inbox(t, {
        groups: [{ id: 'g', name: 'Priority group', icon: 'heart.svg' }],
        savedFilters: [{ id: 'f', name: 'My unread', filterDefinition: { view: 'MINE', unread: 'HAS_UNREAD' } }],
    })
    const browser = ui.renderer.root.findByProps({ className: 'inbox-browser' })
    const navigation = browser.findByType('nav')
    const list = browser.findByType('aside')
    assert.equal(navigation.props['aria-label'], 'Навигация Inbox')
    assert.ok(text(navigation).includes('Мои диалоги'))
    assert.ok(text(navigation).includes('Priority group'))
    assert.ok(text(navigation).includes('My unread'))
    assert.equal(list.findAllByProps({ className: 'conversation-list-saved-filters' }).length, 0)
    assert.equal(list.findByProps({ type: 'search' }).props['aria-label'], 'Поиск по диалогам')
    assert.ok(list.findByProps({ 'aria-label': 'Сортировка' }))
    assert.equal(list.findAllByProps({ type: 'date' }).length, 0)
    await ui.click('Настроить фильтры')
    assert.equal(list.findAllByProps({ type: 'date' }).length, 2)
    assert.equal(list.findByProps({ className: 'conversation-filter-date-range' }).findAllByProps({ type: 'date' }).length, 2)
    await ui.click('Priority group')
    assert.equal(list.findByType('h2').children.join(''), 'Priority group')
    await ui.click('My unread')
    assert.equal(list.findByType('h2').children.join(''), 'My unread')
})

test('navigation disclosure and representation changes keep the conversation draft mounted', async t => {
    const ui = await inbox(t, { groups: [{ id: 'g', name: 'My group', icon: 'users.svg' }] })
    const composer = () => ui.renderer.root.findByProps({ 'aria-label': 'Черновик сообщения' })
    const navigation = () => ui.renderer.root.findByProps({ className: 'inbox-navigation' })
    await act(async () => composer().props.onChange({ target: { value: 'Unsent message' } }))
    await ui.click('Меню Inbox')
    assert.equal(navigation().props['data-open'], true)
    await ui.click('My group')
    assert.equal(navigation().props['data-open'], false)
    assert.equal(ui.calls.at(-1).groupId, 'g')
    await ui.click('Свободные')
    await ui.switchWorkspace({ id: 'workspace-2', name: 'Another Workspace' })
    assert.equal(composer().props.value, 'Unsent message')
    assert.equal(ui.threadMounts, 1)
})

test('search submits with collapsed conditions and saved account restrictions can be cleared explicitly', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'f', name: 'Restricted', filterDefinition: {
        view: 'MINE', clientAccountIds: ['account-a', 'account-b'],
    } }] })
    await act(async () => ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: '  find me  ' } }))
    await act(async () => ui.renderer.root.findByProps({ className: 'conversation-filters' }).props.onSubmit({ preventDefault() {} }))
    assert.equal(ui.calls.at(-1).search, 'find me')
    await ui.click('Restricted')
    assert.ok(text(ui.renderer.root.findByProps({ 'aria-label': 'Применённые условия' })).includes('Аккаунты: 2'))
    await ui.click('Настроить фильтры')
    const before = ui.calls.length
    await ui.click('Снять ограничение по аккаунтам')
    assert.equal(ui.calls.length, before)
    await ui.click('Применить')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, [])
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 0)
})

test('mobile return to the list keeps filters and the open thread; selecting the same dialogue restores it', async t => {
    const ui = await inbox(t, { realRows: true })
    const layout = () => ui.renderer.root.findByProps({ className: 'inbox-layout' })
    await act(async () => ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: 'filter text' } }))
    await ui.click('Применить')
    const before = ui.calls.length
    await ui.click('К списку диалогов')
    assert.equal(layout().props['data-mobile-list'], true)
    await ui.click('Меню Inbox')
    await ui.click('Закрыть меню')
    assert.equal(ui.renderer.root.findByProps({ type: 'search' }).props.value, 'filter text')
    const link = ui.renderer.root.findAllByType('a').find(node => node.props.className.includes('conversation-list-item'))
    await act(async () => link.props.onClick({ button: 0, defaultPrevented: false,
        preventDefault() {}, metaKey: false, altKey: false, ctrlKey: false, shiftKey: false }))
    assert.equal(layout().props['data-mobile-list'], false)
    assert.equal(ui.calls.length, before)
    assert.equal(ui.threadMounts, 1)
})

test('invalid saved dates open the editor and close navigation without replacing the applied search', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'bad', name: 'Invalid period', filterDefinition: {
        view: 'UNASSIGNED', activityFrom: '2026-10-18', activityTo: '2026-10-17',
    } }] })
    const before = ui.calls.length
    await ui.click('Меню Inbox')
    await ui.click('Invalid period')
    assert.equal(ui.calls.length, before)
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
    assert.equal(ui.renderer.root.findAllByProps({ type: 'date' }).length, 2)
    assert.ok(text(ui.renderer.root.findByProps({ role: 'alert' })).includes('Начало периода'))
})

test('a late real conversation GET stays unread in the mobile list; reopening marks it without remounting the composer', async t => {
    const pending = deferred(), read = deferred()
    const ui = await inbox(t, { width: 600, realThread: true, realRows: true,
        loadConversation: () => pending.promise, markRead: () => read.promise })
    await ui.click('К списку диалогов')
    await act(async () => pending.resolve(conversation()))
    assert.deepEqual(ui.readCalls, [])
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-layout' }).props['data-mobile-list'], true)
    const link = ui.renderer.root.findAllByType('a').find(node => node.props.className.includes('conversation-list-item'))
    await act(async () => link.props.onClick({ button: 0, defaultPrevented: false, preventDefault() {} }))
    assert.deepEqual(ui.readCalls, ['conversation-1'])
    const composer = ui.renderer.root.findByProps({ placeholder: 'Введите сообщение...' })
    await act(async () => composer.props.onChange({ target: { value: 'Unsent reply' } }))
    await act(async () => read.resolve(conversation('conversation-1', { unreadCount: 0 })))
    assert.equal(ui.renderer.root.findByProps({ placeholder: 'Введите сообщение...' }), composer)
    assert.equal(composer.props.value, 'Unsent reply')
    assert.deepEqual(ui.conversationLoads, ['conversation-1'])
})

test('resizing the mobile list to desktop marks the now visible real thread as read', async t => {
    const pending = deferred()
    const ui = await inbox(t, { width: 600, realThread: true, loadConversation: () => pending.promise })
    await ui.click('К списку диалогов')
    await act(async () => pending.resolve(conversation()))
    assert.deepEqual(ui.readCalls, [])
    await ui.resize(1366)
    assert.deepEqual(ui.readCalls, ['conversation-1'])
    assert.deepEqual(ui.conversationLoads, ['conversation-1'])
})

test('an obsolete real conversation GET cannot switch Workspace or mark the previous route read', async t => {
    const previous = deferred()
    const ui = await inbox(t, { realThread: true, loadConversation: id => id === 'conversation-1'
        ? previous.promise : conversation(id) })
    await ui.navigate('/inbox/conversation-2?tab=mine')
    assert.deepEqual(ui.readCalls, ['conversation-2'])
    await act(async () => previous.resolve(conversation('conversation-1', { workspaceId: 'obsolete-workspace' })))
    assert.deepEqual(ui.readCalls, ['conversation-2'])
    assert.ok(!ui.workspaceSelections.includes('obsolete-workspace'))
})

test('a saved period keeps its exact bounds in another zone until the dates themselves change', async t => {
    const previousZone = process.env.TZ
    process.env.TZ = 'America/New_York'
    t.after(() => { process.env.TZ = previousZone })
    const from = '2026-10-17T21:00:00.000Z', to = '2026-10-18T20:59:59.999999Z'
    const ui = await inbox(t, { savedFilters: [{ id: 'period', name: 'Moscow day', filterDefinition: {
        view: 'MINE', activityFrom: from, activityTo: to,
    } }] })
    await ui.click('Moscow day')
    assert.equal(ui.calls.at(-1).activityFrom, from)
    assert.equal(ui.calls.at(-1).activityTo, to)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-filter-hint' }).length, 0)
    await ui.click('Настроить фильтры')
    const dates = () => ui.renderer.root.findAllByProps({ type: 'date' })
    assert.deepEqual(dates().map(node => node.props.value), ['2026-10-17', '2026-10-18'])
    await act(async () => ui.renderer.root.findByProps({ type: 'search' }).props.onChange({ target: { value: 'changed text' } }))
    await ui.click('Применить')
    assert.equal(ui.calls.at(-1).activityFrom, from)
    assert.equal(ui.calls.at(-1).activityTo, to)
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ type: 'text' }).props.onChange({ target: { value: 'Exact copy' } }))
    await ui.submit()
    assert.equal(ui.savedCalls[0].filterDefinition.activityFrom, from)
    assert.equal(ui.savedCalls[0].filterDefinition.activityTo, to)
    await act(async () => dates()[0].props.onChange({ target: { value: '2026-10-18' } }))
    await ui.click('Применить')
    assert.equal(ui.calls.at(-1).activityFrom, '2026-10-18T04:00:00.000Z')
    assert.equal(ui.calls.at(-1).activityTo, '2026-10-19T03:59:59.999999Z')
})

for (const returnToFirst of [false, true]) test(`late saving respects a more recent preset choice${returnToFirst ? ', including A-B-A' : ''}`, async t => {
    const pending = deferred()
    let sent
    const ui = await inbox(t, { savedFilters: [
        { id: 'a', name: 'Filter A', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } },
        { id: 'b', name: 'Filter B', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } },
    ], saveFilter: async (workspaceId, request) => { sent = request; return pending.promise } })
    await ui.click('Filter A')
    await ui.click('+ Сохранить фильтр')
    await act(async () => ui.renderer.root.findByProps({ type: 'text' }).props.onChange({ target: { value: 'New copy' } }))
    await ui.submit()
    await ui.click('Filter B')
    if (returnToFirst) await ui.click('Filter A')
    await act(async () => pending.resolve({ id: 'created-filter', ...sent }))
    assert.ok(ui.button('New copy'))
    assert.equal(text(ui.renderer.root.findByProps({ className: 'conversation-list-saved-filter active' })), returnToFirst ? 'Filter A' : 'Filter B')
})

for (const width of [1024, 1366]) test(`a late rename error survives group navigation at width ${width}`, async t => {
    const pending = deferred()
    const ui = await inbox(t, { width, groups: [
        { id: 'a', name: 'Group A', icon: 'users.svg' }, { id: 'b', name: 'Group B', icon: 'heart.svg' },
    ], renameGroup: () => pending.promise })
    await ui.click('Group A')
    if (width <= 1100) await ui.click('Меню Inbox')
    await ui.click('Переименовать')
    await act(async () => ui.renderer.root.findByProps({ type: 'text' }).props.onChange({ target: { value: 'Renamed A' } }))
    await ui.submit()
    await ui.click('Group B')
    await act(async () => pending.reject(Error('Rename conflict')))
    const list = ui.renderer.root.findByProps({ className: 'conversation-list-header' })
    assert.ok(text(list.findByProps({ role: 'alert' })).includes('Rename conflict'))
    assert.ok(text(list.findByProps({ role: 'alert' })).includes('Renamed A'))
    if (width <= 1100) {
        await ui.click('Меню Inbox')
        const navigation = ui.renderer.root.findByProps({ className: 'inbox-navigation' })
        assert.ok(text(navigation.findByProps({ role: 'alert' })).includes('Rename conflict'))
        assert.equal(list.findAllByProps({ role: 'alert' }).length, 0)
    }
    await ui.click('Скрыть')
    assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 0)
    assert.equal(ui.calls.at(-1).groupId, 'b')
})

test('drawer contains focus, makes the background inert and handles Escape outside the panel', async t => {
    const dom = focusDom()
    const ui = await inbox(t, { createNodeMock: dom.createNodeMock })
    await ui.click('Меню Inbox')
    assert.equal(dom.document.activeElement, dom.close)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props.role, 'dialog')
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['aria-modal'], true)
    for (const element of [dom.list, dom.thread, dom.chrome]) assert.equal(element.hasAttribute('inert'), true)
    assert.equal(dom.backdrop.hasAttribute('inert'), false)
    dom.last.focus()
    assert.equal(dom.key('Tab').defaultPrevented, true)
    assert.equal(dom.document.activeElement, dom.close)
    assert.equal(dom.key('Tab', true).defaultPrevented, true)
    assert.equal(dom.document.activeElement, dom.last)
    dom.search.focus()
    assert.equal(dom.document.activeElement, dom.close)
    // Even an externally moved focus must not make Escape stop working.
    dom.document.activeElement = dom.search
    await act(async () => dom.key('Escape'))
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
    assert.equal(dom.document.activeElement, dom.opener)
    for (const element of [dom.list, dom.thread, dom.chrome]) assert.equal(element.hasAttribute('inert'), false)
    assert.equal(dom.existing.getAttribute('inert'), 'existing')
    await ui.click('Меню Inbox')
    await ui.click('Настроить фильтры')
    assert.equal(dom.document.activeElement, dom.search)
})

test('drawer cleanup releases focus and inert when resizing to a permanent navigation column', async t => {
    const dom = focusDom()
    const ui = await inbox(t, { createNodeMock: dom.createNodeMock })
    await ui.click('Меню Inbox')
    dom.close.visible = false
    dom.opener.visible = false
    await ui.resize(1366)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
    assert.equal(dom.list.hasAttribute('inert'), false)
    assert.equal(dom.document.activeElement, dom.first)
})

test('automatic mobile conversation selection closes a hidden drawer and releases its background', async t => {
    const dom = focusDom(), pending = deferred()
    const ui = await inbox(t, { width: 600, initialEntries: ['/inbox?tab=mine'],
        createNodeMock: dom.createNodeMock, search: () => pending.promise })
    await ui.click('Меню Inbox')
    assert.equal(dom.thread.hasAttribute('inert'), true)
    dom.panel.visible = false
    dom.opener.visible = false
    await act(async () => pending.resolve(response()))
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
    assert.equal(dom.thread.hasAttribute('inert'), false)
    assert.equal(dom.list.hasAttribute('inert'), false)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-layout' }).props['data-has-conversation'], true)
})
