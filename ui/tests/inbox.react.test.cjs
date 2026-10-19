const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const { MemoryRouter, Route, Routes, useLocation, useNavigate } = require('react-router-dom')
const load = require('./loadInboxModule.cjs')
const focusDom = require('./inboxFocusDom.cjs')
process.env.TZ = 'Europe/Moscow'

test('conversation cards show the current client link and omit it when account data is unavailable', async t => {
    const Row = load('ConversationListItem.tsx').default
    let renderer
    t.after(async () => { if (renderer) await act(async () => renderer.unmount()) })
    const render = account => React.createElement(MemoryRouter, null, React.createElement(Row, {
        item: { conversation: conversation(), clientAccount: account, channel: null },
        active: false, tab: 'mine', groups: [], workspaceId: 'workspace', canManageGroups: false,
    }))
    const account = { id: 'account', displayName: 'Contact', clientId: null, avatarUrl: null }
    await act(async () => { renderer = create(render(account)) })
    assert.equal(text(renderer.root.findByProps({ className: 'conversation-list-client-link' })), 'Без клиента')
    await act(async () => renderer.update(render({ ...account, clientId: 'client' })))
    assert.equal(text(renderer.root.findByProps({ className: 'conversation-list-client-link' })), 'Клиент есть')
    await act(async () => renderer.update(render(account)))
    assert.equal(text(renderer.root.findByProps({ className: 'conversation-list-client-link' })), 'Без клиента')
    await act(async () => renderer.update(render(null)))
    assert.equal(renderer.root.findAllByProps({ className: 'conversation-list-client-link' }).length, 0)
})

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
    const savedCalls = [], updatedFilterCalls = [], deletedFilterCalls = []
    const filterLoads = [], accountOptionLoads = [], clientOptionLoads = []
    const renameCalls = []
    const readCalls = [], conversationLoads = [], workspaceSelections = []
    let groups = [...(options.groups ?? [])]
    let workspace = { id: 'workspace', name: 'Workspace' }
    let renderer, location, navigate
    let threadMounts = 0
    const reloadMessages = () => {}

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
        getConversationGroupIds: async (...args) => options.loadMembership ? options.loadMembership(...args) : [...membership],
        addConversationToGroup: async (workspaceId, groupId, conversationId) => {
            membershipCalls.push(['add', workspaceId, groupId, conversationId])
            if (options.addMembership) return options.addMembership(workspaceId, groupId, conversationId)
            membership.add(groupId)
        },
        removeConversationFromGroup: async (workspaceId, groupId, conversationId) => {
            membershipCalls.push(['remove', workspaceId, groupId, conversationId])
            if (options.removeMembership) return options.removeMembership(workspaceId, groupId, conversationId)
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
        '../../../api/clientApi': { getClients: async () => { clientOptionLoads.push(true); return options.clients ?? [] } },
        '../../../api/clientAccountApi': {
            getClientAccountsByIds: async ids => ids.map(id => ({ id })),
            getWorkspaceClientAccounts: async (workspaceId, channelIds = []) => {
                accountOptionLoads.push({ workspaceId, channelIds })
                if (options.loadFilterAccounts) return options.loadFilterAccounts(workspaceId, channelIds)
                return (options.filterAccounts ?? []).filter(account => !channelIds.length
                    || account.channelAccountIds?.some(id => channelIds.includes(id)))
            },
        },
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
            updateSavedConversationFilter: async (workspaceId, id, request) => {
                updatedFilterCalls.push({ workspaceId, id, ...request })
                if (options.updateFilter) return options.updateFilter(workspaceId, id, request)
                const filter = { id, ...request }
                const index = saved.findIndex(item => item.id === id)
                if (index >= 0) saved[index] = filter
                return filter
            },
            deleteSavedConversationFilter: async (workspaceId, id) => {
                deletedFilterCalls.push({ workspaceId, id })
                if (options.deleteFilter) await options.deleteFilter(workspaceId, id)
                const index = saved.findIndex(item => item.id === id)
                if (index >= 0) saved.splice(index, 1)
            },
        },
        ...(options.realThread ? {
            '../../api/conversationApi': api,
            '../../api/clientAccountApi': { getClientAccount: async id => ({ id }) },
            '../../api/channelApi': { getWorkspaceChannels: async () => options.channels ?? [] },
            '../../api/employeeApi': { getWorkspaceEmployees: async () => [] },
            '../../api/messageApi': { sendOutboundMessage: async () => {} },
            './useConversationMessages': { useConversationMessages: () => ({ messages: [], loading: false,
                loadingOlder: false, hasMore: false, historyStartReached: true, error: null, reloadMessages }) },
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
    const pickerButton = label => renderer.root.findAllByType('button').find(node => node.props['aria-label'] === label)
    const chooseGroup = async (picker, name) => {
        await act(async () => pickerButton(picker).props.onClick())
        const panel = renderer.root.findAllByType('div').find(node => node.props.role === 'group' && node.props['aria-label'] === picker)
        const option = panel.findAllByType('button').find(node => text(node) === name)
        assert.ok(option, `Missing group ${name}`)
        await act(async () => option.props.onClick())
    }
    return {
        renderer, calls, mounts, groupCalls, membershipCalls, renameCalls, savedCalls, updatedFilterCalls, deletedFilterCalls, filterLoads, accountOptionLoads, clientOptionLoads, readCalls, conversationLoads, workspaceSelections,
        setSearch: handler => { search = handler },
        button,
        deleteFilter: async () => { const row=renderer.root.findByProps({ className: 'inbox-filter-save-slot' }).findAllByType('button').find(node=>text(node)==='Удалить'); assert.ok(row); await act(async()=>row.props.onClick()); },
        scope: async label => { const panel=renderer.root.findByProps({ className: 'conversation-list-tabs' }); const row=panel.findAllByType('button').find(node=>text(node)===label); assert.ok(row); await act(async()=>row.props.onClick()); },
        get groupNames() { return renderer.root.findByProps({ label: 'Выбрать группу диалогов' }).props.options.map(option => option.label) },
        manageGroup: name => chooseGroup('Управлять группой', name),
        click: async label => {
            if(label === 'Очистить фильтр') return act(async()=>renderer.root.findAllByType('button').find(node=>node.props['aria-label']===label).props.onClick());
            if ((renderer.root.findAllByProps({ label: 'Выбрать группу диалогов' })[0]?.props.options ?? []).some(option=>option.label===label)) return chooseGroup('Выбрать группу диалогов', label);
            if (!button(label)) { const tabs=renderer.root.findAllByProps({ 'aria-label': 'Тип фильтров' }); for(const tab of tabs.flatMap(node=>node.findAllByType('button'))) { await act(async()=>tab.props.onClick()); if(button(label)) break; } }
            if (!button(label) && pickerButton('Выбрать группу диалогов')) {
                await act(async () => pickerButton('Выбрать группу диалогов').props.onClick())
            }
            assert.ok(button(label), `Missing button: ${label}`)
            await act(async () => {
                const target = button(label)
                if (target.props.onClick) return target.props.onClick({ preventDefault() {} })
                assert.equal(target.props.type, 'submit')
                let form = target.parent
                while (form && form.type !== 'form') form = form.parent
                assert.ok(form, 'Submit button must belong to a form')
                return form.props.onSubmit({ preventDefault() {} })
            })
        },
        submit: async () => act(async () => {
            const forms = renderer.root.findAllByType('form'); const groupForms = forms.filter(node => node.props.className === 'conversation-list-group-form'); const editors = groupForms.length ? groupForms : forms.filter(node => node.props.className === 'conversation-list-group-form inbox-filter-save-form')
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

for (const status of [403, 404]) {
    test(`initial conversation HTTP ${status} refreshes a stale list without a loaded DTO`, async t => {
        let searches = 0
        const ui = await inbox(t, {
            realThread: true,
            loadConversation: async () => { throw Object.assign(Error('Conversation unavailable'), { status }) },
            search: async () => ++searches === 1 ? response() : response([]),
        })
        assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-state conversation-error' }).length, 1)
        assert.equal(ui.renderer.root.findAllByType('article').length, 0)
        assert.ok(searches >= 2)
    })
}

test('initial access denial invalidates a still pending stale list response', async t => {
    const firstSearch = deferred()
    let searches = 0
    const ui = await inbox(t, {
        realThread: true,
        loadConversation: async () => { throw Object.assign(Error('No access'), { status: 403 }) },
        search: () => ++searches === 1 ? firstSearch.promise : Promise.resolve(response([])),
    })
    await act(async () => firstSearch.resolve(response()))
    assert.ok(searches >= 2)
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
})

for (const status of [403, 404]) {
    test(`conversation HTTP ${status} evicts only its card before a failing list refresh`, async t => {
        const pending = deferred(), refresh = deferred()
        let searches = 0, reads = 0
        const ui = await inbox(t, {
            realThread: true,
            loadConversation: id => ++reads === 1 ? pending.promise : Promise.resolve(conversation(id)),
            search: () => ++searches === 1
                ? Promise.resolve(response([conversation(), conversation('conversation-2')])) : refresh.promise,
        })
        assert.ok(ui.row())
        await act(async () => pending.reject(Object.assign(Error('Access revoked'), { status })))
        assert.ok(searches >= 2)
        assert.deepEqual(ui.renderer.root.findAllByType('article').map(row => row.props['data-id']), ['conversation-2'])
        await act(async () => refresh.reject(Object.assign(Error('Search temporarily unavailable'), { status: 503 })))
        assert.deepEqual(ui.renderer.root.findAllByType('article').map(row => row.props['data-id']), ['conversation-2'])
        // A later successful server response can restore access to the card.
        ui.setSearch(async () => response())
        await ui.click('Повторить загрузку диалога')
        assert.ok(ui.row())
    })
}

test('access denial plus a failed reconciliation cannot revive a pending stale list response', async t => {
    const initial = deferred()
    let searches = 0
    const ui = await inbox(t, {
        realThread: true,
        loadConversation: async () => { throw Object.assign(Error('No access'), { status: 403 }) },
        search: () => {
            if (++searches === 1) return initial.promise
            return Promise.reject(Object.assign(Error('Search unavailable'), { status: 503 }))
        },
    })
    await act(async () => initial.resolve(response()))
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
})

test('an initial transient conversation failure preserves the accessible list', async t => {
    const ui = await inbox(t, {
        realThread: true,
        loadConversation: async () => { throw Object.assign(Error('Temporary failure'), { status: 503 }) },
    })
    assert.ok(ui.row())
    assert.equal(ui.calls.length, 1)
    assert.ok(text(ui.renderer.toJSON()).includes('Temporary failure'))
})

test('late initial access denial cannot invalidate a newer conversation', async t => {
    const firstConversation = deferred()
    const ui = await inbox(t, {
        realThread: true,
        loadConversation: id => id === 'conversation-1' ? firstConversation.promise : Promise.resolve(conversation(id)),
    })
    await ui.navigate('/inbox/conversation-2?tab=mine')
    const searches = ui.calls.length
    await act(async () => firstConversation.reject(Object.assign(Error('Old denial'), { status: 403 })))
    assert.equal(ui.calls.length, searches)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-state conversation-error' }).length, 0)
    assert.ok(!text(ui.renderer.toJSON()).includes('Old denial'))
})

for (const status of [403, 404]) {
    test(`HTTP ${status} during refresh clears previously visible conversation cards`, async t => {
        const ui = await inbox(t)
        assert.ok(ui.row())
        ui.setSearch(async () => { throw Object.assign(Error('Workspace or view is unavailable'), { status }) })
        await ui.emit()
        assert.equal(ui.renderer.root.findAllByType('article').length, 0)
        assert.equal(ui.renderer.root.findAllByProps({ role: 'alert' }).length, 1)
        ui.setSearch(async () => response())
        await ui.click('Повторить')
        assert.ok(ui.row())
    })
}

test('changing conditions hides previous cards and ignores an obsolete refresh result', async t => {
    const ui = await inbox(t)
    const obsolete = deferred()
    ui.setSearch(() => obsolete.promise)
    await ui.emit()
    const current = deferred()
    ui.setSearch(() => current.promise)
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'new query' } }))
    await ui.click('Применить')
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
    assert.equal(ui.calls.at(-1).search, 'new query')
    await act(async () => obsolete.resolve(response([conversation('obsolete')])) )
    assert.equal(ui.renderer.root.findAllByType('article').length, 0)
    await act(async () => current.resolve(response([conversation('current')])) )
    assert.ok(ui.row('current'))
    assert.equal(ui.renderer.root.findAllByProps({ 'data-id': 'obsolete' }).length, 0)
})

test('switching scope preserves the selected filter and every applied criterion', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'saved', name: 'Saved accounts', filterDefinition: {
        view: 'MINE', clientAccountIds: ['restricted-account'], search: 'visible search',
        channelAccountIds: ['channel-account'], sort: 'LAST_MESSAGE_ASC',
    } }] })
    await ui.click('Saved accounts')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['restricted-account'])
    await ui.click('Другие')
    const request = ui.calls.at(-1)
    assert.equal(request.view, 'UNASSIGNED')
    assert.deepEqual(request.clientAccountIds, ['restricted-account'])
    assert.equal(request.search, 'visible search')
    assert.deepEqual(request.channelAccountIds, ['channel-account'])
    assert.equal(request.sort, 'LAST_MESSAGE_ASC')
})

test('one personal filter overlays every scope and scope controls retain SVG icons', async t => {
    const definition = { view: 'GROUP', groupId: 'legacy-group', search: 'saved search',
        channelAccountIds: ['channel'], clientIds: ['client'], clientAccountIds: ['account'],
        clientLink: 'WITH_CLIENT', unread: 'HAS_UNREAD', sort: 'LAST_MESSAGE_ASC',
        activityFrom: '2026-10-17T00:00:00Z', activityTo: '2026-10-18T00:00:00Z' }
    const ui = await inbox(t, {
        employeeWorkspaces: [{ workspaceId: 'workspace', isSupervisor: true }],
        groups: [{ id: 'a', name: 'Group A', icon: 'heart.svg' }, { id: 'b', name: 'Group B', icon: 'users.svg' }],
        savedFilters: [{ id: 'personal', name: 'Personal overlay', filterType: 'PRIVATE', filterDefinition: definition }],
    })
    const scopeRow = () => ui.renderer.root.findByProps({ 'aria-label': 'Область диалогов' })
    const filterRow = () => ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })
    assert.equal(ui.button('Мои').props['aria-pressed'], true)
    assert.equal(text(filterRow()), 'Без фильтра')
    assert.equal(filterRow().findByType('img').props.src, '/assets/filter/nofilter.svg')
    await ui.click('Personal overlay')
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.equal(text(filterRow()), 'Personal overlay')
    assert.equal(filterRow().findByType('img').props.src, '/assets/filter/private.svg')
    for (const [label, view, groupId, icon] of [
        ['Другие', 'UNASSIGNED', null, 'free.svg'], ['Group A', 'GROUP', 'a', 'heart.svg'],
        ['Group B', 'GROUP', 'b', 'users.svg'], ['Все', 'ALL', null, 'all.svg'],
        ['Мои', 'MINE', null, 'my.svg'],
    ]) {
        const before = ui.calls.length
        if (['Мои', 'Другие', 'Все'].includes(label)) await ui.scope(label); else await ui.click(label)
        assert.ok(ui.calls.length > before)
        for (const call of ui.calls.slice(before)) {
            assert.equal(call.view, view)
            assert.equal(call.groupId, groupId)
            for (const key of ['search', 'channelAccountIds', 'clientIds', 'clientAccountIds', 'clientLink', 'unread', 'sort', 'activityFrom', 'activityTo']) {
                assert.deepEqual(call[key], definition[key])
            }
        }
        const active = groupId ? scopeRow().findAllByType('button').find(node => node.props['aria-label'] === 'Выбрать группу диалогов') : ui.renderer.root.findByProps({ className: 'conversation-list-tabs' }).findAllByType('button').find(node => text(node) === label)
        if (!groupId) assert.equal(active.props['aria-pressed'], true)
        assert.equal(active.findByType('img').props.src, `/assets/group/${icon}`)
        assert.equal(text(filterRow()), 'Personal overlay')
        assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 1)
    }
    await ui.click('Сбросить')
    assert.equal(ui.button('Мои').props['aria-pressed'], true)
    assert.equal(text(filterRow()), 'Без фильтра')
    assert.equal(filterRow().findByType('img').props.src, '/assets/filter/nofilter.svg')
})

test('system filter keeps its icon and current group while draft and applied criteria survive scope navigation', async t => {
    const ui = await inbox(t, {
        groups: [{ id: 'g', name: 'My group', icon: 'heart.svg' }],
        systemFilters: [{ id: 'system', name: 'Shared overlay', filterType: 'SYSTEM', filterDefinition: { view: 'ALL', search: 'applied' } }],
    })
    await ui.click('My group')
    await ui.click('Shared overlay')
    assert.equal(ui.calls.at(-1).groupId, 'g')
    const filter = () => ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })
    assert.equal(filter().findByType('img').props.src, '/assets/filter/system.svg')
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'draft' } }))
    await ui.click('Другие')
    assert.equal(ui.calls.at(-1).search, 'applied')
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.value, 'draft')
    assert.equal(text(filter()), 'Shared overlay')
    await ui.click('Применить')
    assert.equal(ui.calls.at(-1).search, 'draft')
    await ui.click('My group')
    assert.equal(ui.calls.at(-1).groupId, 'g')
    assert.equal(ui.calls.at(-1).search, 'draft')
    assert.equal(text(filter()), 'Shared overlay')
    assert.equal(filter().findByType('img').props.src, '/assets/filter/system.svg')
})

test('employee groups render after loading; group create, rename, select and delete stay connected', async t => {
    const ui = await inbox(t, { groups: [{ id: 'existing-group', name: 'Existing group', icon: 'users.svg' }] })
    assert.ok(ui.groupNames.includes('Existing group'))
    assert.ok(ui.button('+ Группа'))
    await ui.click('Existing group')
    assert.equal(ui.calls.at(-1).view, 'GROUP')
    assert.equal(ui.calls.at(-1).groupId, 'existing-group')
    await ui.click('+ Группа')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'New group' } }))
    await ui.submit()
    assert.ok(ui.groupNames.includes('New group'))
    assert.equal(ui.calls.at(-1).groupId, 'existing-group')
    await ui.click('New group')
    assert.equal(ui.calls.at(-1).groupId, 'created-group')
    await ui.click('Изменить')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Renamed group' } }))
    await ui.submit()
    assert.ok(ui.groupNames.includes('Renamed group'))
    await ui.click('Удалить')
    assert.equal(ui.groupNames.includes('Renamed group'), false)
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

for (const status of [403, 404, 409]) for (const add of [false, true]) {
    test(`membership ${add ? 'add' : 'remove'} HTTP ${status} reconciles the list and preserves the error`, async t => {
        const deny = async () => { throw Object.assign(Error('Membership rejected'), { status }) }
        const ui = await inbox(t, {
            realRows: true, initialEntries: ['/inbox?tab=mine'],
            groups: [{ id: 'g', name: 'Group', icon: 'users.svg' }], groupIds: add ? [] : ['g'],
            addMembership: deny, removeMembership: deny,
        })
        await ui.click('В группу')
        const checkbox = ui.renderer.root.findByProps({ type: 'checkbox' })
        assert.equal(checkbox.props.checked, !add)
        ui.setSearch(async () => response([]))
        await act(async () => checkbox.props.onChange({ target: { checked: add } }))
        assert.equal(ui.membershipCalls.length, 1)
        assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-item-row' }).length, 0)
        const header = ui.renderer.root.findByProps({ className: 'conversation-list-header' })
        assert.ok(header.findAllByProps({ role: 'alert' }).some(node => text(node).includes('Membership rejected')))
    })
}

test('a rejected membership closes stale checkboxes and reopening reads current membership', async t => {
    let groupIds = ['g']
    const ui = await inbox(t, {
        realRows: true, groups: [{ id: 'g', name: 'Group', icon: 'users.svg' }],
        loadMembership: async () => groupIds,
        removeMembership: async () => { throw Object.assign(Error('Membership changed'), { status: 409 }) },
    })
    await ui.click('В группу')
    const checkbox = ui.renderer.root.findByProps({ type: 'checkbox' })
    assert.equal(checkbox.props.checked, true)
    groupIds = []
    await act(async () => checkbox.props.onChange({ target: { checked: false } }))
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-item-group-menu' }).length, 0)
    await ui.click('В группу')
    assert.equal(ui.renderer.root.findByProps({ type: 'checkbox' }).props.checked, false)
})

test('late membership rejection does not refresh or show an error in a different Workspace', async t => {
    const command = deferred()
    const ui = await inbox(t, {
        realRows: true, groups: [{ id: 'g', name: 'Group', icon: 'users.svg' }], groupIds: ['g'],
        removeMembership: () => command.promise,
    })
    await ui.click('В группу')
    await act(async () => ui.renderer.root.findByProps({ type: 'checkbox' }).props.onChange({ target: { checked: false } }))
    await ui.switchWorkspace({ id: 'workspace-2', name: 'Next Workspace' })
    const searches = ui.calls.length
    await act(async () => command.reject(Object.assign(Error('Old membership denial'), { status: 403 })))
    assert.equal(ui.calls.length, searches)
    assert.ok(!text(ui.renderer.toJSON()).includes('Old membership denial'))
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
    assert.ok(ui.groupNames.includes('Created while loading'))
    await act(async () => initial.resolve([{ id: 'old', name: 'Existing on server', icon: 'users.svg' }]))
    assert.ok(ui.groupNames.includes('Created while loading'))
    assert.ok(ui.groupNames.includes('Existing on server'))
})

test('a late initial GET merges a saved filter without erasing or duplicating it', async t => {
    const initial = deferred()
    const ui = await inbox(t, { loadPrivateFilters: () => initial.promise })
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

test('Back and Forward change scope while preserving the selected filter and its restrictions', async t => {
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
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['hidden-account'])
    assert.equal(ui.calls.at(-1).search, 'visible condition')
    assert.ok(ui.calls.filter(call => call.view === 'UNASSIGNED').every(call => call.clientAccountIds.includes('hidden-account')))
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 1)
    await ui.navigate(1)
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['hidden-account'])
})

test('selecting a saved filter ignores its legacy view and preserves the current scope', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'saved', name: 'Saved free', filterDefinition: {
        view: 'UNASSIGNED', clientAccountIds: ['saved-account'],
    } }] })
    await ui.click('Saved free')
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['saved-account'])
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 1)
})

test('changing the managed group closes its editor without changing the dialogue scope', async t => {
    const ui = await inbox(t, { groups: [
        { id: 'a', name: 'Group A', icon: 'users.svg' }, { id: 'b', name: 'Group B', icon: 'heart.svg' },
    ] })
    await ui.click('Group A')
    await ui.click('Изменить')
    await ui.manageGroup('Group B')
    assert.equal(ui.renderer.root.findAllByProps({ placeholder: 'Название группы' }).length, 0)
    await ui.click('Изменить')
    assert.equal(ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.value, 'Group B')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Renamed B' } }))
    await ui.submit()
    assert.deepEqual(ui.renameCalls.map(call => [call.id, call.name]), [['b', 'Renamed B']])
    assert.equal(ui.calls.at(-1).groupId, 'a')
})

test('date-only editor restores local dates and includes the entire selected day', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'dates', name: 'Saved dates', filterDefinition: {
        view: 'MINE', activityFrom: '2026-10-17T09:30:12.123Z',
    } }] })
    await ui.click('Saved dates')
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
    await ui.click('Изменить')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Final name' } }))
    await ui.submit()
    assert.ok(ui.groupNames.includes('Final name'))
    await ui.click('Удалить')
    await act(async () => initial.resolve([{ id: 'created-group', name: 'Initial name', icon: 'users.svg' }]))
    assert.equal(ui.button('Initial name'), undefined)
    assert.equal(ui.groupNames.includes('Final name'), false)
})

test('a saved filter without an explicit view applies to the current scope', async t => {
    const ui = await inbox(t, {
        initialEntries: ['/inbox/conversation-1?tab=unassigned'],
        savedFilters: [{ id: 'legacy', name: 'Default view', filterDefinition: { clientLink: 'WITHOUT_CLIENT' } }],
    })
    await ui.click('Default view')
    assert.equal(ui.calls.at(-1).view, 'UNASSIGNED')
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
    assert.ok(ui.groupNames.includes('Current group'))
    assert.ok(ui.button('Current filter'))
    assert.ok(ui.row('current-conversation'))
    await act(async () => {
        previousGroups.resolve([{ id: 'old-group', name: 'Obsolete group', icon: 'users.svg' }])
        previousFilters.resolve([{ id: 'old-filter', name: 'Obsolete filter', filterDefinition: { view: 'MINE' } }])
        previousSearch.resolve(response([conversation('obsolete-conversation')]))
    })
    assert.equal(ui.groupNames.includes('Obsolete group'), false)
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
    await ui.click('Другие')
    const field = label => ui.renderer.root.findAllByType('label')
        .find(node => text(node).startsWith(label)).findByType('select')
    const channels = ui.renderer.root.findByProps({ 'aria-label': 'Каналы' })
    assert.equal(channels.findAllByProps({ type: 'checkbox' }).length, 2)
    const before = ui.calls.length
    await act(async () => {
        ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: '  alice  ' } })
        channels.findAllByProps({ type: 'checkbox' }).forEach(node => node.props.onChange())
        field('Связь с клиентом').props.onChange({ target: { value: 'WITHOUT_CLIENT' } })
        field('Сообщения').props.onChange({ target: { value: 'HAS_UNREAD' } })
        const dates = ui.renderer.root.findAllByProps({ type: 'date' })
        dates[0].props.onChange({ target: { value: '2026-10-16' } })
        dates[1].props.onChange({ target: { value: '2026-10-17' } })
    })
    assert.equal(ui.calls.length, before)
    await act(async () => field('Сортировка').props.onChange({ target: { value: 'LAST_MESSAGE_ASC' } }))
    assert.equal(ui.calls.at(-1).sort, 'LAST_MESSAGE_ASC')
    assert.equal(ui.calls.at(-1).search, null)
    await ui.click('Применить')
    const expected = {
        view: 'UNASSIGNED', groupId: null, search: 'alice', channelAccountIds: ['account-a', 'account-b'],
        clientAccountIds: [], clientLink: 'WITHOUT_CLIENT', unread: 'HAS_UNREAD',
        activityFrom: '2026-10-15T21:00:00.000Z', activityTo: '2026-10-17T20:59:59.999999Z',
        sort: 'LAST_MESSAGE_ASC',
    }
    assert.deepEqual(ui.calls.at(-1), { workspaceId: 'workspace', ...expected, page: 0, size: 10 })
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
    await ui.click('Отмена')
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.equal(ui.calls.at(-1).page, 0)
    assert.equal(ui.calls.at(-1).size, 10)
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'my copy' } }))
    await ui.click('Применить')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'My copy' } }))
    await ui.submit()
    assert.equal(ui.savedCalls[0].filterType, 'PRIVATE')
    assert.equal(ui.savedCalls[0].filterDefinition.search, 'my copy')
    assert.deepEqual(definition, original)
})

test('client and account selections are searchable, wait for Apply and survive saving and restoring', async t => {
    const ui = await inbox(t, {
        clients: [{ id: 'client-a', firstName: 'Alice', lastName: 'Smith', phoneList: ['123'] },
            { id: 'client-b', firstName: 'Bob', lastName: 'Jones', phoneList: ['456'] }],
        filterAccounts: [{ id: 'account-a', clientId: 'client-a', displayName: 'Alice Telegram', channelType: 'TELEGRAM' },
            { id: 'account-b', clientId: 'client-b', displayName: 'Bob Telegram', channelType: 'TELEGRAM' }],
    })
    const fieldset = name => ui.renderer.root.findAllByType('fieldset').find(node => text(node.findByType('legend')) === name)
    const checkbox = (section, name) => fieldset(section).findAllByType('label').find(node => text(node).includes(name)).findByType('input')
    const before = ui.calls.length
    await act(async () => checkbox('Клиенты', 'Alice').props.onChange())
    await act(async () => checkbox('Аккаунты клиентов', 'Alice Telegram').props.onChange())
    assert.equal(ui.calls.length, before)
    assert.equal(fieldset('Аккаунты клиентов').findAllByType('label').length, 1)
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск клиентов' }).props.onChange({ target: { value: '456' } }))
    assert.equal(text(fieldset('Клиенты')).includes('Smith Alice'), false)
    assert.equal(text(fieldset('Клиенты')).includes('Jones Bob'), true)
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск аккаунтов клиентов' }).props.onChange({ target: { value: 'missing' } }))
    assert.equal(fieldset('Аккаунты клиентов').findAllByType('label').length, 0)
    await ui.click('Применить')
    assert.deepEqual(ui.calls.at(-1).clientIds, ['client-a'])
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, ['account-a'])
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'Client filter' } }))
    await ui.submit()
    assert.deepEqual(ui.savedCalls[0].filterDefinition.clientIds, ['client-a'])
    assert.deepEqual(ui.savedCalls[0].filterDefinition.clientAccountIds, ['account-a'])
    await ui.click('Сбросить')
    assert.equal(ui.calls.at(-1).clientIds, undefined)
    await ui.click('Client filter')
    assert.deepEqual(ui.calls.at(-1).clientIds, ['client-a'])
})

test('client link modes and channel checkboxes restrict available clients and accounts together', async t => {
    const ui = await inbox(t, {
        channels: [{ id: 'a', name: 'Telegram A', type: 'TELEGRAM', account: { id: 'channel-a' } },
            { id: 'b', name: 'Telegram B', type: 'TELEGRAM', account: { id: 'channel-b' } }],
        clients: [{ id: 'alice', firstName: 'Alice', lastName: 'Smith', phoneList: [] },
            { id: 'bob', firstName: 'Bob', lastName: 'Jones', phoneList: [] },
            { id: 'empty', firstName: 'No accounts', lastName: '', phoneList: [] }],
        filterAccounts: [
            { id: 'a', displayName: 'Alice account', clientId: 'alice', channelType: 'TELEGRAM', channelAccountIds: ['channel-a'] },
            { id: 'b', displayName: 'Bob account', clientId: 'bob', channelType: 'TELEGRAM', channelAccountIds: ['channel-b'] },
            { id: 'orphan-a', displayName: 'Orphan A', clientId: null, channelType: 'TELEGRAM', channelAccountIds: ['channel-a'] },
            { id: 'orphan-b', displayName: 'Orphan B', clientId: null, channelType: 'TELEGRAM', channelAccountIds: ['channel-b'] },
        ],
    })
    assert.equal(ui.button('Настроить фильтры'), undefined)
    const fieldset = name => ui.renderer.root.findAllByType('fieldset').find(node => text(node.findByType('legend')) === name)
    const labels = name => fieldset(name).findAllByType('label').map(text)
    const toggle = async (name, label) => act(async () => fieldset(name).findAllByType('label')
        .find(node => text(node).includes(label)).findByType('input').props.onChange())
    const mode = async value => act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Связь с клиентом' }).props.onChange({ target: { value } }))
    assert.equal(labels('Клиенты').length, 3)
    assert.equal(labels('Аккаунты клиентов').length, 4)
    await mode('WITH_CLIENT')
    assert.equal(labels('Клиенты').length, 2)
    assert.equal(labels('Аккаунты клиентов').length, 2)
    assert.ok(labels('Аккаунты клиентов').every(label => !label.includes('Orphan')))
    await toggle('Клиенты', 'Alice')
    assert.equal(labels('Аккаунты клиентов').length, 1)
    await toggle('Аккаунты клиентов', 'Alice account')
    await toggle('Каналы', 'Telegram A')
    assert.deepEqual(ui.accountOptionLoads.at(-1).channelIds, ['channel-a'])
    assert.equal(labels('Клиенты').length, 1)
    assert.equal(labels('Аккаунты клиентов').length, 1)
    await ui.click('Применить')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, [])
    assert.equal(ui.calls.at(-1).clientIds, undefined)
    const beforeWithout = ui.clientOptionLoads.length
    await mode('WITHOUT_CLIENT')
    assert.equal(ui.clientOptionLoads.length, beforeWithout)
    assert.equal(fieldset('Клиенты'), undefined)
    assert.deepEqual(labels('Аккаунты клиентов'), ['Orphan A · TELEGRAM · Без клиента'])
    await ui.click('Очистить каналы')
    assert.equal(labels('Аккаунты клиентов').length, 2)
    await mode('ANY')
    assert.equal(labels('Клиенты').length, 3)
    assert.equal(labels('Аккаунты клиентов').length, 4)
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск каналов' }).props.onChange({ target: { value: 'Telegram B' } }))
    assert.equal(labels('Каналы').length, 1)
    await toggle('Каналы', 'Telegram B')
    assert.deepEqual(ui.accountOptionLoads.at(-1).channelIds, ['channel-b'])
    assert.equal(labels('Клиенты').length, 1)
    assert.ok(labels('Клиенты')[0].includes('Bob'))
    assert.equal(labels('Аккаунты клиентов').length, 2)
})

test('late account options from a previous channel cannot replace the current channel catalogue', async t => {
    const previous = deferred()
    const ui = await inbox(t, {
        channels: [{ id: 'a', name: 'Channel A', account: { id: 'channel-a' } }],
        loadFilterAccounts: async (workspaceId, ids) => ids.length
            ? [{ id: 'new', displayName: 'Current account', channelType: 'TELEGRAM', clientId: null }]
            : previous.promise,
    })
    const channels = ui.renderer.root.findByProps({ 'aria-label': 'Каналы' })
    await act(async () => channels.findByProps({ type: 'checkbox' }).props.onChange())
    const accounts = () => ui.renderer.root.findAllByType('fieldset').find(node => text(node.findByType('legend')) === 'Аккаунты клиентов')
    assert.ok(text(accounts()).includes('Current account'))
    await act(async () => previous.resolve([{ id: 'old', displayName: 'Old account', channelType: 'TELEGRAM', clientId: null }]))
    assert.equal(text(accounts()).includes('Old account'), false)
    assert.ok(text(accounts()).includes('Current account'))
})

test('personal filter can be renamed, updated and deleted from its header actions', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'personal', name: 'Personal', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } }] })
    await ui.click('Personal')
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'changed draft' } }))
    const name = ui.renderer.root.findByProps({ placeholder: 'Название фильтра' })
    assert.equal(name.props.value, 'Personal')
    await act(async () => name.props.onChange({ target: { value: 'Renamed' } }))
    await ui.submit()
    assert.equal(ui.updatedFilterCalls[0].id, 'personal')
    assert.equal(ui.updatedFilterCalls[0].name, 'Renamed')
    assert.equal(ui.updatedFilterCalls[0].filterDefinition.search, 'changed draft')
    assert.equal(ui.calls.at(-1).search, 'changed draft')
    assert.ok(ui.button('Renamed'))
    await ui.deleteFilter()
    assert.deepEqual(ui.deletedFilterCalls, [{ workspaceId: 'workspace', id: 'personal' }])
    assert.equal(ui.button('Renamed'), undefined)
})

test('late deletion of A preserves a newer selected filter B', async t => {
    const pending = deferred()
    const ui = await inbox(t, { deleteFilter: () => pending.promise, savedFilters: [
        { id: 'a', name: 'Filter A', filterType: 'PRIVATE', filterDefinition: { view: 'MINE', search: 'a' } },
        { id: 'b', name: 'Filter B', filterType: 'PRIVATE', filterDefinition: { view: 'MINE', search: 'b' } },
    ] })
    await ui.click('Filter A')
    await ui.deleteFilter()
    await ui.click('Filter B')
    await act(async () => pending.resolve())
    assert.equal(text(ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })), 'Filter B')
    assert.equal(ui.calls.at(-1).search, 'b')
    assert.equal(ui.button('Filter A'), undefined)
    assert.equal(text(ui.renderer.root.findByProps({ className: 'conversation-list-saved-filter active' })), 'Filter B')
})

test('an old save cannot close a reopened editor or replace its current selection', async t => {
    const pending = deferred()
    const ui = await inbox(t, { saveFilter: () => pending.promise })
    const name = () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' })
    await act(async () => name().props.onChange({ target: { value: 'First filter' } }))
    await ui.submit()
    await ui.click('Отмена')
    await act(async () => name().props.onChange({ target: { value: 'New unfinished filter' } }))
    await act(async () => pending.resolve({ id: 'first', name: 'First filter', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } }))
    assert.equal(name().props.value, 'New unfinished filter')
    assert.equal(text(ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })), 'Без фильтра')
    assert.ok(ui.button('First filter'))
})

test('an update response arriving after deletion and reload cannot resurrect the deleted filter', async t => {
    const pending = deferred()
    const filter = { id: 'a', name: 'Filter A', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } }
    const ui = await inbox(t, { savedFilters: [filter], updateFilter: () => pending.promise })
    await ui.click('Filter A')
    await act(async()=>ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({target:{value:'Changed A'}}))
    await ui.submit()
    await ui.deleteFilter()
    assert.equal(ui.button('Filter A'), undefined)
    await act(async () => pending.resolve({ ...filter, name: 'Updated deleted filter' }))
    assert.equal(ui.button('Filter A'), undefined)
    assert.equal(ui.button('Updated deleted filter'), undefined)
    assert.equal(text(ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })), 'Без фильтра')
})

test('system filter exposes only a personal copy in its header actions for an employee', async t => {
    const ui = await inbox(t, { systemFilters: [{ id: 'system', name: 'System', filterType: 'SYSTEM', filterDefinition: { view: 'MINE' } }] })
    await ui.click('System')
    const applyActions = ui.renderer.root.findByProps({ className: 'inbox-filter-editor' }).findByProps({ className: 'conversation-filter-actions' })
    assert.deepEqual(applyActions.findAllByType('button').map(text), ['Применить', 'Сбросить'])
    const actions = ui.renderer.root.findByProps({ className: 'inbox-filter-save-slot' })
    const copy = actions.findAllByType('button').find(node => text(node) === 'Сохранить себе')
    assert.ok(copy)
    assert.equal(ui.button('Сохранить изменения'), undefined)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-filter-save-slot' }).findAllByType('button').some(node => text(node) === 'Удалить'), false)

    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'My system copy' } }))
    await ui.submit()
    assert.equal(ui.savedCalls[0].filterType, 'PRIVATE')
    assert.equal(ui.updatedFilterCalls.length, 0)
})

test('SuperAdmin loads system filters only and creates a system definition in the current Workspace', async t => {
    const ui = await inbox(t, { superAdmin: true })
    assert.deepEqual(ui.filterLoads, [['workspace', 'SYSTEM']])
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
    await ui.click('Системные')
    assert.ok(ui.button('Recovered shared'))
    await ui.click('Мои')
    assert.ok(ui.button('Still available'))
})

test('conditions stay in navigation while search and sort belong to the dialogue list', async t => {
    const ui = await inbox(t, {
        groups: [{ id: 'g', name: 'Priority group', icon: 'heart.svg' }],
        savedFilters: [{ id: 'f', name: 'My unread', filterDefinition: { view: 'MINE', unread: 'HAS_UNREAD' } }],
    })
    const browser = ui.renderer.root.findByProps({ className: 'inbox-browser' })
    const navigation = browser.findByProps({ className: 'inbox-navigation' })
    const list = browser.findByType('aside')
    assert.ok(text(list.findByProps({ 'aria-label': 'Область диалогов' })).includes('Мои'))
    assert.equal(navigation.findByProps({ label: 'Управлять группой' }).props.options[0].label, 'Priority group')
    assert.ok(text(navigation).includes('My unread'))
    assert.equal(list.findAllByProps({ className: 'conversation-list-saved-filters' }).length, 0)
    assert.equal(list.findByProps({ 'aria-label': 'Поиск по диалогам' }).props['aria-label'], 'Поиск по диалогам')
    assert.ok(list.findByProps({ 'aria-label': 'Сортировка' }))
    assert.equal(list.findAllByProps({ type: 'date' }).length, 0)
    assert.equal(list.findAllByProps({ type: 'date' }).length, 0)
    assert.equal(browser.findByProps({ className: 'inbox-navigation' }).findByProps({ className: 'conversation-filter-date-range' }).findAllByProps({ type: 'date' }).length, 2)
    await ui.click('Priority group')
    assert.equal(list.findByType('h2').children.join(''), 'Диалоги')
    await ui.click('My unread')
    assert.equal(list.findByType('h2').children.join(''), 'Диалоги')
    assert.ok(text(list.findByProps({ 'aria-label': 'Выбранный фильтр' })).includes('My unread'))
})

for (const width of [600, 1366]) test(`group management and the filter editor stay independent of dialogue scopes at width ${width}`, async t => {
    const ui = await inbox(t, { width,
        groups: [{ id: 'a', name: 'Alpha group', icon: 'heart.svg' }, { id: 'b', name: 'Beta group', icon: 'users.svg' }],
        systemFilters: [{ id: 's1', name: 'Shared Alpha', filterType: 'SYSTEM', filterDefinition: { view: 'MINE' } }],
        savedFilters: [{ id: 'p1', name: 'Private Beta', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } }],
    })
    const left = ui.renderer.root.findByProps({ className: 'inbox-navigation' })
    const list = ui.renderer.root.findByType('aside')
    const before = ui.calls.length
    const loads = ui.filterLoads.length
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск фильтров' }).props.onChange({ target: { value: 'alpha' } }))
    await ui.click('Системные')
    assert.ok(ui.button('Shared Alpha'))
    assert.equal(ui.button('Private Beta'), undefined)
    assert.equal(ui.calls.length, before)
    assert.equal(ui.filterLoads.length, loads)
    await ui.manageGroup('Beta group')
    assert.equal(ui.calls.length, before)
    assert.equal(ui.calls.at(-1).view, 'MINE')
    const manager = () => left.findAllByType('button').find(node => node.props['aria-label'] === 'Управлять группой')
    assert.ok(text(manager()).includes('Beta group'))
    assert.equal(manager().findByType('img').props.src, '/assets/group/users.svg')
    await ui.click('Alpha group')
    assert.equal(ui.calls.at(-1).groupId, 'a')
    assert.ok(text(manager()).includes('Beta group'))
    assert.equal(left.findAllByProps({ type: 'date' }).length, 2)
    assert.equal(list.findAllByProps({ type: 'date' }).length, 0)
    const editor = left.findByProps({ className: 'inbox-filter-editor' })
    assert.ok(editor.findByProps({ className: 'inbox-filter-editor-scroll' }))
    assert.deepEqual(editor.findByProps({ className: 'conversation-filter-actions' }).findAllByType('button').map(text), ['Применить', 'Сбросить'])
    assert.equal(ui.button('⋯'), undefined)
    if (width === 600) await ui.click('Меню Inbox')
    await ui.click('Применить')
    if (width === 600) assert.equal(left.props['data-open'], false)
    await ui.click('Очистить фильтр')
    assert.equal(text(list.findByProps({ 'aria-label': 'Выбранный фильтр' })), 'Без фильтра')
    await ui.click('Удалить')
    assert.equal(ui.groupNames.includes('Beta group'), false)
    assert.equal(ui.calls.at(-1).groupId, 'a')
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
    await ui.click('Другие')
    await ui.switchWorkspace({ id: 'workspace-2', name: 'Another Workspace' })
    assert.equal(composer().props.value, 'Unsent message')
    assert.equal(ui.threadMounts, 1)
})

test('search submits with collapsed conditions and saved account restrictions can be cleared explicitly', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'f', name: 'Restricted', filterDefinition: {
        view: 'MINE', clientAccountIds: ['account-a', 'account-b'],
    } }] })
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: '  find me  ' } }))
    await act(async () => ui.renderer.root.findByProps({ className: 'conversation-filters' }).props.onSubmit({ preventDefault() {} }))
    assert.equal(ui.calls.at(-1).search, 'find me')
    await ui.click('Restricted')
    assert.equal(ui.renderer.root.findAllByProps({ 'aria-label': 'Применённые условия' }).length, 0)
    const clear = ui.renderer.root.findAllByType('button').find(node => node.props['aria-label'] === 'Очистить фильтр')
    assert.equal(clear.props.title, 'Очистить фильтр')
    assert.equal(clear.findByType('img').props.src, '/assets/filter/clear.svg')
    const before = ui.calls.length
    await ui.click('Снять ограничение по аккаунтам')
    assert.equal(ui.calls.length, before)
    await ui.click('Применить')
    assert.deepEqual(ui.calls.at(-1).clientAccountIds, [])
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-list-saved-filter active' }).length, 1)
})

test('mobile return to the list keeps filters and the open thread; selecting the same dialogue restores it', async t => {
    const ui = await inbox(t, { realRows: true })
    const layout = () => ui.renderer.root.findByProps({ className: 'inbox-layout' })
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'filter text' } }))
    await ui.click('Применить')
    const before = ui.calls.length
    await ui.click('К списку диалогов')
    assert.equal(layout().props['data-mobile-list'], true)
    await ui.click('Меню Inbox')
    await ui.click('Закрыть меню')
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.value, 'filter text')
    const link = ui.renderer.root.findAllByType('a').find(node => node.props.className.includes('conversation-list-item'))
    await act(async () => link.props.onClick({ button: 0, defaultPrevented: false,
        preventDefault() {}, metaKey: false, altKey: false, ctrlKey: false, shiftKey: false }))
    assert.equal(layout().props['data-mobile-list'], false)
    assert.equal(ui.calls.length, before)
    assert.equal(ui.threadMounts, 1)
})

test('invalid saved dates keep navigation open at the conditions without replacing the applied search', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'bad', name: 'Invalid period', filterDefinition: {
        view: 'UNASSIGNED', activityFrom: '2026-10-18', activityTo: '2026-10-17',
    } }] })
    const before = ui.calls.length
    await ui.click('Меню Inbox')
    await ui.click('Invalid period')
    assert.equal(ui.calls.length, before)
    assert.equal(ui.calls.at(-1).view, 'MINE')
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], true)
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
    const dates = () => ui.renderer.root.findAllByProps({ type: 'date' })
    assert.deepEqual(dates().map(node => node.props.value), ['2026-10-17', '2026-10-18'])
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'changed text' } }))
    await ui.click('Применить')
    assert.equal(ui.calls.at(-1).activityFrom, from)
    assert.equal(ui.calls.at(-1).activityTo, to)
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'Exact copy' } }))
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
    ], updateFilter: async (workspaceId, id, request) => { sent = request; return pending.promise } })
    await ui.click('Filter A')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' }).props.onChange({ target: { value: 'New copy' } }))
    await ui.submit()
    await ui.click('Filter B')
    if (returnToFirst) await ui.click('Filter A')
    await act(async () => pending.resolve({ id: 'a', ...sent }))
    assert.ok(ui.button('New copy'))
    assert.equal(text(ui.renderer.root.findByProps({ className: 'conversation-list-saved-filter active' })), returnToFirst ? 'New copy' : 'Filter B')
})

for (const width of [1024, 1366]) test(`a late rename error survives group navigation at width ${width}`, async t => {
    const pending = deferred()
    const ui = await inbox(t, { width, groups: [
        { id: 'a', name: 'Group A', icon: 'users.svg' }, { id: 'b', name: 'Group B', icon: 'heart.svg' },
    ], renameGroup: () => pending.promise })
    await ui.click('Group A')
    await ui.manageGroup('Group A')
    if (width <= 1100) await ui.click('Меню Inbox')
    await ui.click('Изменить')
    await act(async () => ui.renderer.root.findByProps({ placeholder: 'Название группы' }).props.onChange({ target: { value: 'Renamed A' } }))
    await ui.submit()
    await ui.click('Group B')
    await act(async () => pending.reject(Error('Rename conflict')))
    const list = ui.renderer.root.findByProps({ className: 'conversation-list-header' })
    const left = ui.renderer.root.findByProps({ className: 'inbox-navigation' })
    assert.ok(text(left.findByProps({ role: 'alert' })).includes('Rename conflict'))
    assert.ok(text(left.findByProps({ role: 'alert' })).includes('Renamed A'))
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

test('sorting in the middle column applies immediately without applying unfinished conditions', async t => {
    const ui = await inbox(t, { width: 600, savedFilters: [{ id: 'f', name: 'Saved', filterType: 'PRIVATE',
        filterDefinition: { view: 'MINE', search: 'applied' } }] })
    await ui.click('Saved')
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.onChange({ target: { value: 'unfinished' } }))
    const before = ui.calls.length
    await act(async () => ui.renderer.root.findByProps({ 'aria-label': 'Сортировка' }).props.onChange({ target: { value: 'LAST_MESSAGE_ASC' } }))
    assert.equal(ui.calls.length, before + 1)
    assert.equal(ui.calls.at(-1).sort, 'LAST_MESSAGE_ASC')
    assert.equal(ui.calls.at(-1).search, 'applied')
    assert.equal(ui.renderer.root.findByProps({ 'aria-label': 'Поиск по диалогам' }).props.value, 'unfinished')
    assert.equal(text(ui.renderer.root.findByProps({ 'aria-label': 'Выбранный фильтр' })), 'Saved')
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
})

test('Escape closes a nested group picker before the drawer', async t => {
    const dom = focusDom()
    const ui = await inbox(t, { createNodeMock: dom.createNodeMock,
        groups: [{ id: 'a', name: 'Group A', icon: 'heart.svg' }] })
    await ui.click('Меню Inbox')
    await ui.click('+ Группа')
    const manager = () => ui.renderer.root.findAllByType('button').find(node => node.props['aria-label'] === 'Значок группы')
    await act(async () => manager().props.onClick())
    const picker = ui.renderer.root.findAllByProps({ className: 'inbox-icon-picker' }).find(node=>node.props['data-inbox-escape-layer']==='true')
    assert.equal(picker.props['data-inbox-escape-layer'], 'true')
    await act(async () => {
        dom.key('Escape', false, { closest: selector => selector === '[data-inbox-escape-layer="true"]' ? {} : null })
        picker.props.onKeyDown({ key: 'Escape', preventDefault() {}, stopPropagation() {} })
    })
    assert.equal(manager().props['aria-expanded'], false)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], true)
    await act(async () => dom.key('Escape'))
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-navigation' }).props['data-open'], false)
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
    assert.equal(ui.button('Настроить фильтры'), undefined)
    await ui.click('Закрыть меню')
    assert.equal(dom.document.activeElement, dom.opener)
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

test('group management requires selection and icon choices contain only SVG buttons', async t => {
    const ui = await inbox(t, { groups: [{ id: 'g', name: 'Group', icon: 'heart.svg' }] })
    assert.equal(ui.button('Изменить').props.disabled, true)
    assert.equal(ui.button('Удалить').props.disabled, true)
    await ui.manageGroup('Group')
    assert.equal(ui.button('Изменить').props.disabled, false)
    await ui.click('Изменить')
    assert.equal(ui.button('Изменить'), undefined)
    assert.equal(ui.button('Удалить'), undefined)
    const iconTrigger = ui.renderer.root.findAllByType('button').find(node => node.props['aria-label'] === 'Значок группы')
    await act(async () => iconTrigger.props.onClick())
    const options = ui.renderer.root.findByProps({ className: 'inbox-icon-picker-options inbox-icon-options-grid' })
    assert.ok(options.findAllByType('button').length > 3)
    assert.equal(options.findAllByType('span').length, 0)
    assert.equal(ui.renderer.root.findAllByProps({ 'aria-label': 'Поиск: Значок группы' }).length, 0)
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-group-editor-slot' }).findAllByType('form').length, 1)
    await ui.click('Отмена')
    assert.equal(ui.renderer.root.findByProps({ className: 'inbox-group-editor-slot' }).findAllByType('form').length, 0)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'inbox-filter-save-slot' }).length, 1)
})

test('filter tabs search locally and the persistent save form renames an existing personal filter', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'p', name: 'Personal', filterType: 'PRIVATE', filterDefinition: { view: 'MINE' } }],
        systemFilters: [{ id: 's', name: 'Shared', filterType: 'SYSTEM', filterDefinition: { view: 'MINE' } }] })
    const tabs = ui.renderer.root.findByProps({ 'aria-label': 'Тип фильтров' })
    assert.deepEqual(tabs.findAllByType('button').map(text), ['Мои', 'Системные'])
    assert.ok(ui.button('Personal'))
    assert.equal(ui.button('Shared'), undefined)
    await ui.click('Системные')
    assert.ok(ui.button('Shared'))
    assert.equal(ui.button('Personal'), undefined)
    await ui.click('Shared')
    assert.ok(ui.button('Сохранить себе'))
    const name = () => ui.renderer.root.findByProps({ placeholder: 'Название фильтра' })
    assert.equal(name().props.value, 'Shared')
    await ui.click('Мои')
    await ui.click('Personal')
    await act(async () => name().props.onChange({ target: { value: 'Renamed' } }))
    await ui.click('Сохранить')
    assert.equal(ui.updatedFilterCalls.length, 1)
    assert.equal(ui.savedCalls.length, 0)
    assert.ok(ui.button('Renamed'))
    assert.ok(ui.button('+ Фильтр').props.className.includes('ui-button-primary'))
    await ui.click('Очистить фильтр')
    assert.equal(name().props.value, '')
    await act(async () => name().props.onChange({ target: { value: 'Unfinished' } }))
    await ui.click('Отмена')
    assert.equal(name().props.value, '')
})
test('Groups heading collapses its panel and creation reopens it', async t => {
    const ui = await inbox(t, { groups: [{ id: 'g', name: 'Group', icon: 'heart.svg' }] })
    const toggle = () => ui.renderer.root.findAllByType('button').find(node => node.props.className === 'inbox-section-toggle')
    const panel = () => ui.renderer.root.findByProps({ className: 'inbox-groups-panel' })
    assert.equal(toggle().props['aria-controls'], panel().props.id)
    assert.equal(toggle().props['aria-expanded'], true)
    await act(async () => toggle().props.onClick())
    assert.equal(panel().props.hidden, true)
    assert.equal(toggle().props['aria-expanded'], false)
    await act(async () => toggle().props.onClick())
    assert.equal(panel().props.hidden, false)
    await act(async () => toggle().props.onClick())
    await ui.click('+ Группа')
    assert.equal(panel().props.hidden, false)
    assert.ok(ui.renderer.root.findByProps({ placeholder: 'Название группы' }))
})
test('saving a personal filter requires changes and reverting them disables saving again', async t => {
    const ui = await inbox(t, { savedFilters: [{ id: 'p', name: 'Personal', filterType: 'PRIVATE', filterDefinition: { view: 'MINE', channelAccountIds: ['b', 'a'] } }] })
    await ui.click('Personal')
    assert.equal(ui.button('Сохранить').props.disabled, true)
    await ui.submit()
    assert.equal(ui.updatedFilterCalls.length, 0)
    const name=()=>ui.renderer.root.findByProps({ placeholder:'Название фильтра' })
    await act(async()=>name().props.onChange({target:{value:' Renamed '}}))
    assert.equal(ui.button('Сохранить').props.disabled, false)
    await act(async()=>name().props.onChange({target:{value:' Personal '}}))
    assert.equal(ui.button('Сохранить').props.disabled, true)
    const search=()=>ui.renderer.root.findByProps({'aria-label':'Поиск по диалогам'})
    await act(async()=>search().props.onChange({target:{value:'changed'}}))
    assert.equal(ui.button('Сохранить').props.disabled, false)
    await act(async()=>search().props.onChange({target:{value:''}}))
    assert.equal(ui.button('Сохранить').props.disabled, true)
    await act(async()=>search().props.onChange({target:{value:'saved'}}))
    await ui.click('Сохранить')
    assert.equal(ui.updatedFilterCalls.length, 1)
    assert.equal(ui.button('Сохранить').props.disabled, true)
})
test('New filter clears the current preset and criteria while retaining the conversation scope', async t => {
    const ui=await inbox(t, { groups:[{id:'g',name:'Group',icon:'heart.svg'}], savedFilters:[{id:'p',name:'Personal',filterType:'PRIVATE',filterDefinition:{view:'MINE',search:'old',clientAccountIds:['account'],unread:'HAS_UNREAD'}}] })
    await ui.click('Group')
    await ui.click('Personal')
    await ui.click('+ Фильтр')
    assert.equal(ui.calls.at(-1).view,'GROUP')
    assert.equal(ui.calls.at(-1).groupId,'g')
    assert.equal(ui.calls.at(-1).search,null)
    assert.deepEqual(ui.calls.at(-1).clientAccountIds,[])
    assert.equal(ui.calls.at(-1).unread,'ANY')
    assert.equal(ui.renderer.root.findByProps({'aria-label':'Поиск по диалогам'}).props.value,'')
    assert.equal(ui.renderer.root.findByProps({placeholder:'Название фильтра'}).props.value,'')
    assert.equal(text(ui.renderer.root.findByProps({className:'inbox-filter-editor-heading'})),'Новый фильтр')
    assert.equal(text(ui.renderer.root.findByProps({'aria-label':'Выбранный фильтр'})),'Без фильтра')
    assert.equal(ui.renderer.root.findAllByProps({className:'conversation-list-saved-filter active'}).length,0)
})
