const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

function deferred() {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
}
const account = (extra = {}) => ({ id: 'account', clientId: null, channelType: 'TELEGRAM', externalId: '123', displayName: 'Account', username: '@account', phone: '79990000000', avatarUrl: null, ...extra })
const client = (id = 'client', extra = {}) => ({ id, firstName: id, lastName: '', phoneList: ['79991111111'], enabled: true, avatarUrl: null, ...extra })
const conversation = (id = 'a') => ({ id, clientAccountId: 'account', channelAccountId: 'channel-account', workspaceId: 'workspace', unreadCount: 0, assignedEmployeeId: 'employee' })
const channel = { id: 'channel', workspaceId: 'workspace', type: 'TELEGRAM', name: 'Channel', account: { id: 'channel-account', username: null, phone: null, displayName: null, avatarUrl: null } }

function browser(t) {
    const original = global.window
    const timers = new Map()
    const cleanups = []
    let timerId = 0
    global.window = new EventTarget()
    global.window.setTimeout = callback => { timers.set(++timerId, callback); return timerId }
    global.window.clearTimeout = id => timers.delete(id)
    t.after(() => { for (const cleanup of cleanups.reverse()) cleanup(); global.window = original })
    return { cleanup: callback => cleanups.push(callback), flush: () => act(async () => {
        const pending = [...timers.values()]
        timers.clear()
        for (const callback of pending) void callback()
    }) }
}
function content(renderer) {
    const visit = node => typeof node === 'string' ? node : Array.isArray(node) ? node.map(visit).join(' ') : node?.children?.map(visit).join(' ') ?? ''
    return visit(renderer.toJSON())
}
const buttons = (renderer, name) => renderer.root.findAllByType('button').filter(node => node.children.join('') === name)
const button = (renderer, name) => { const found = buttons(renderer, name); assert.equal(found.length, 1, `Expected one button: ${name}`); return found[0] }
const click = (renderer, name) => act(async () => button(renderer, name).props.onClick())
const field = (renderer, name) => renderer.root.findAllByType('label').find(node => node.findAllByType('span').some(span => span.children.join('') === name)).findByType('input')
const type = (renderer, name, value) => act(async () => field(renderer, name).props.onChange({ target: { value } }))
const submit = renderer => act(async () => renderer.root.findByProps({ className: 'conversation-client-form' }).props.onSubmit({ preventDefault() {} }))
const visualMocks = {
    '../common/platform/PlatformIcon': { default: () => null },
    '../common/platform/PlatformName': { default: () => 'Telegram' },
    '../channels/ChannelAccountAvatar': { ChannelAccountAvatar: () => null },
    '../clients/ClientAccountAvatar': { default: () => null },
    '../clients/ClientAccountPicker': { default: props => React.createElement('div', { 'data-picker': true, ...props }) },
}

async function panel(t, overrides = {}, initial = {}) {
    const clock = browser(t)
    const changes = []
    const api = { getClient: async id => client(id), getClientAccountsByClient: async () => [], searchClients: async () => [], ...overrides }
    const Panel = load('ConversationClientPanel.tsx', { '../../api/clientApi': api, ...visualMocks }).default
    let props = { conversation: conversation(), clientAccount: account(), channel, onRetryDetails() {}, ...initial }
    let renderer, session = 0
    const tree = () => {
        const generation = session
        return React.createElement(Panel, { ...props, onChanged: updated => {
            changes.push(updated)
            // Model the parent boundary: committed commands always notify it,
            // but a closed form session cannot write to the current panel.
            if (generation !== session) return
            props = { ...props, clientAccount: updated }
            renderer.update(tree())
        } })
    }
    await act(async () => { renderer = create(tree()) })
    clock.cleanup(() => act(() => renderer.unmount()))
    return { renderer, changes, flush: clock.flush,
        update: next => act(async () => {
            if (next.conversation && next.conversation.id !== props.conversation.id) session++
            props = { ...props, ...next }; renderer.update(tree())
        }),
        get picker() { return renderer.root.findByProps({ 'data-picker': true }).props },
    }
}

test('orphan accounts remain visible and usernames never get a second @', async t => {
    const ui = await panel(t)
    const text = content(ui.renderer)
    assert.match(text, /Клиент не создан/)
    assert.match(text, /Account @account 79990000000/)
    assert.doesNotMatch(text, /@@|undefined|👤|☎/)
    assert.equal(buttons(ui.renderer, 'Отвязать аккаунт').length, 0)
})

test('missing account metadata shows a retry instead of create/link actions', async t => {
    let retries = 0
    const ui = await panel(t, {}, { clientAccount: null, accountError: 'Account offline', onRetryDetails: () => { retries++ } })
    assert.match(content(ui.renderer), /Account offline/)
    assert.doesNotMatch(content(ui.renderer), /Клиент не создан/)
    assert.equal(buttons(ui.renderer, 'Создать клиента').length, 0)
    await click(ui.renderer, 'Обновить данные')
    assert.equal(retries, 1)
})

test('a client load error is distinct from an orphan and can be retried independently', async t => {
    let fail = true
    const ui = await panel(t, { getClient: async () => { if (fail) throw Error('Client offline'); return client() } }, { clientAccount: account({ clientId: 'client' }) })
    assert.match(content(ui.renderer), /Client offline/)
    assert.match(content(ui.renderer), /Account @account/)
    assert.doesNotMatch(content(ui.renderer), /Клиент не создан/)
    assert.equal(buttons(ui.renderer, 'Создать клиента').length, 0)
    fail = false
    await click(ui.renderer, 'Повторить загрузку клиента')
    assert.match(content(ui.renderer), /79991111111/)
    assert.doesNotMatch(content(ui.renderer), /Client offline/)
})

test('an accounts load failure retains client details and retry restores other accounts', async t => {
    let fail = true
    const ui = await panel(t, { getClientAccountsByClient: async () => { if (fail) throw Error('Accounts offline'); return [account({ id: 'other', clientId: 'client', displayName: 'Other' })] } }, { clientAccount: account({ clientId: 'client' }) })
    assert.match(content(ui.renderer), /79991111111/)
    assert.match(content(ui.renderer), /Accounts offline/)
    fail = false
    await click(ui.renderer, 'Повторить загрузку аккаунтов')
    await click(ui.renderer, 'Другие аккаунты (1)')
    assert.match(content(ui.renderer), /Other/)
})

test('creating a client uses one atomic request and synchronously rejects duplicate submits', async t => {
    const pending = deferred()
    const calls = []
    const ui = await panel(t, {
        createClientFromConversation: (...args) => { calls.push(args); return pending.promise },
        createClient: () => assert.fail('Must use the atomic endpoint'),
        assignClientAccount: () => assert.fail('Must not separately link the new client'),
    })
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', '  Ivan  ')
    await type(ui.renderer, 'Фамилия', '  Ivanov ')
    await type(ui.renderer, 'Телефон', ' +7999 ')
    await act(async () => {
        const send = ui.renderer.root.findByType('form').props.onSubmit
        send({ preventDefault() {} }); send({ preventDefault() {} })
    })
    assert.deepEqual(calls, [['a', { firstName: 'Ivan', lastName: 'Ivanov', phoneList: ['+7999'] }]])
    assert.equal(button(ui.renderer, 'Создание…').props.disabled, true)
    await act(async () => pending.resolve(client()))
    assert.equal(ui.changes.length, 1)
    assert.equal(ui.changes[0].clientId, 'client')
    assert.doesNotMatch(content(ui.renderer), /Клиент не создан/)
    assert.match(content(ui.renderer), /Отвязать аккаунт/)
})

test('failed creation preserves fields and validation prevents an empty client', async t => {
    let calls = 0
    const ui = await panel(t, { createClientFromConversation: async () => { calls++; throw Error('Create offline') } })
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', '  ')
    await submit(ui.renderer)
    assert.equal(calls, 0)
    assert.match(content(ui.renderer), /Укажите имя или фамилию/)
    await type(ui.renderer, 'Имя', 'Name')
    await type(ui.renderer, 'Телефон', '123')
    await submit(ui.renderer)
    assert.equal(field(ui.renderer, 'Имя').props.value, 'Name')
    assert.equal(field(ui.renderer, 'Телефон').props.value, '123')
    assert.match(content(ui.renderer), /Create offline/)
    assert.equal(button(ui.renderer, 'Создать и привязать').props.disabled, false)
})

test('successful creation remains linked even when the subsequent client read fails', async t => {
    const ui = await panel(t, { createClientFromConversation: async () => client(), getClient: async () => { throw Error('Read offline') } })
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', 'Name')
    await submit(ui.renderer)
    assert.equal(ui.changes[0].clientId, 'client')
    assert.match(content(ui.renderer), /Read offline/)
    assert.equal(buttons(ui.renderer, 'Создать клиента').length, 0)
    assert.equal(buttons(ui.renderer, 'Создать и привязать').length, 0)
    assert.match(content(ui.renderer), /Отвязать аккаунт/)
})

test('client search ignores obsolete responses and links only the current selection', async t => {
    const old = deferred()
    const calls = []
    const ui = await panel(t, {
        searchClients: async query => query ? [client('new')] : old.promise,
        assignClientAccount: async (...args) => { calls.push(args); return account({ clientId: args[0] }) },
    })
    await click(ui.renderer, 'Привязать к клиенту')
    await ui.flush()
    await type(ui.renderer, 'Поиск клиента', 'new')
    await ui.flush()
    await act(async () => old.resolve([client('old')]))
    assert.deepEqual(ui.renderer.root.findAllByType('option').map(node => node.props.value), ['', 'new'])
    await act(async () => ui.renderer.root.findByType('select').props.onChange({ target: { value: 'new' } }))
    await submit(ui.renderer)
    assert.deepEqual(calls, [['new', 'account']])
    assert.equal(ui.changes[0].clientId, 'new')
})

test('client search errors are retryable and a failed link preserves the selected client', async t => {
    let fail = true
    const ui = await panel(t, {
        searchClients: async () => { if (fail) throw Error('Search offline'); return [client()] },
        assignClientAccount: async () => { throw Error('Link denied') },
    })
    await click(ui.renderer, 'Привязать к клиенту')
    await ui.flush()
    assert.match(content(ui.renderer), /Search offline/)
    assert.equal(button(ui.renderer, 'Привязать').props.disabled, true)
    fail = false
    await click(ui.renderer, 'Повторить поиск')
    await ui.flush()
    await act(async () => ui.renderer.root.findByType('select').props.onChange({ target: { value: 'client' } }))
    await submit(ui.renderer)
    assert.equal(ui.renderer.root.findByType('select').props.value, 'client')
    assert.match(content(ui.renderer), /Link denied/)
    assert.equal(ui.changes.length, 0)
})

test('unlinking is guarded against double clicks and keeps the account visible', async t => {
    const pending = deferred()
    let calls = 0
    const ui = await panel(t, { unassignClientAccount: async () => { calls++; return pending.promise } }, { clientAccount: account({ clientId: 'client' }) })
    await act(async () => {
        const unlink = button(ui.renderer, 'Отвязать аккаунт').props.onClick
        unlink(); unlink()
    })
    assert.equal(calls, 1)
    await act(async () => pending.resolve(account()))
    assert.equal(ui.changes[0].clientId, null)
    assert.match(content(ui.renderer), /Клиент не создан/)
    assert.match(content(ui.renderer), /Account @account/)
    assert.equal(buttons(ui.renderer, 'Отвязать аккаунт').length, 0)
})

test('a denied unlink keeps the existing relationship and shows the API error', async t => {
    const ui = await panel(t, { unassignClientAccount: async () => { throw Error('Forbidden') } }, { clientAccount: account({ clientId: 'client' }) })
    await click(ui.renderer, 'Отвязать аккаунт')
    assert.match(content(ui.renderer), /Forbidden/)
    assert.doesNotMatch(content(ui.renderer), /Клиент не создан/)
    assert.equal(ui.changes.length, 0)
    assert.equal(button(ui.renderer, 'Отвязать аккаунт').props.disabled, false)
})

test('linking another account refreshes the list without replacing the current account', async t => {
    let linked = false, calls = 0
    const another = account({ id: 'another', displayName: 'Another' })
    const ui = await panel(t, {
        getClientAccountsByClient: async () => linked ? [{ ...another, clientId: 'client' }] : [],
        assignClientAccount: async (_, id) => { calls++; linked = true; return { ...another, id, clientId: 'client' } },
    }, { clientAccount: account({ clientId: 'client' }) })
    await click(ui.renderer, 'Привязать еще аккаунт')
    await act(async () => { ui.picker.onSelect(another); ui.picker.onSelect(another) })
    assert.equal(calls, 1)
    assert.equal(ui.changes[0].id, 'account')
    assert.equal(ui.picker.open, false)
    await click(ui.renderer, 'Другие аккаунты (1)')
    assert.match(content(ui.renderer), /Another/)
})

test('late creation notifies the parent without replacing another or reopened form', async t => {
    const pending = deferred()
    const ui = await panel(t, { createClientFromConversation: () => pending.promise })
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', 'Old name')
    await submit(ui.renderer)
    await ui.update({ conversation: conversation('b'), clientAccount: account({ id: 'b-account' }) })
    await ui.update({ conversation: conversation('a'), clientAccount: account() })
    await act(async () => pending.resolve(client('old')))
    assert.equal(ui.changes.length, 1)
    assert.equal(ui.changes[0].clientId, 'old')
    assert.match(content(ui.renderer), /Клиент не создан/)
    await click(ui.renderer, 'Создать клиента')
    assert.equal(field(ui.renderer, 'Имя').props.value, '')
})

test('an older accounts read cannot erase a successful link when its refresh fails', async t => {
    const old = deferred(), assignment = deferred()
    let reads = 0
    const another = account({ id: 'another', clientId: 'client', displayName: 'Another' })
    const ui = await panel(t, {
        getClientAccountsByClient: async () => { if (++reads === 1) return old.promise; throw Error('Refresh offline') },
        assignClientAccount: () => assignment.promise,
    }, { clientAccount: account({ clientId: 'client' }) })
    await click(ui.renderer, 'Привязать еще аккаунт')
    await act(async () => ui.picker.onSelect({ ...another, clientId: null }))
    await act(async () => { assignment.resolve(another); old.resolve([]) })
    assert.match(content(ui.renderer), /Refresh offline/)
    await click(ui.renderer, 'Другие аккаунты (1)')
    assert.match(content(ui.renderer), /Another/)
})

test('late client and accounts responses cannot replace the newly selected conversation', async t => {
    const old = deferred()
    const ui = await panel(t, { getClient: async id => id === 'old' ? old.promise : client(id), getClientAccountsByClient: async id => id === 'old' ? old.promise : [] }, { clientAccount: account({ clientId: 'old' }) })
    await ui.update({ conversation: conversation('b'), clientAccount: account({ clientId: 'new' }) })
    await act(async () => old.resolve(client('old')))
    assert.match(content(ui.renderer), /new/)
    assert.doesNotMatch(content(ui.renderer), /old/)
})

test('a late avatar response creates no object URL after switching conversations', async t => {
    const old = deferred()
    const created = [], revoked = []
    t.mock.method(URL, 'createObjectURL', () => { const url = `blob:${created.length}`; created.push(url); return url })
    t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url))
    const ui = await panel(t, { getClient: async id => client(id, { avatarUrl: 'avatar' }), getClientAvatarContent: id => id === 'old' ? old.promise : Promise.resolve(new Blob()) }, { clientAccount: account({ clientId: 'old' }) })
    await ui.update({ conversation: conversation('b'), clientAccount: account({ clientId: 'new' }) })
    await act(async () => old.resolve(new Blob()))
    assert.deepEqual(created, ['blob:0'])
    await ui.update({ conversation: conversation('c'), clientAccount: account() })
    assert.deepEqual(revoked, ['blob:0'])
})

test('channel details show only populated fields with a normalized username', async t => {
    const ui = await panel(t, {}, { channel: { ...channel, account: { ...channel.account, username: '@channel', phone: '555', displayName: 'Channel account' } } })
    assert.match(content(ui.renderer), /Channel account @channel 555/)
    assert.doesNotMatch(content(ui.renderer), /@@/)
})

test('the conversation header uses the same normalized username as account details', async t => {
    const Header = load('ConversationHeader.tsx', {
        ...visualMocks, '../employees/EmployeeAvatar': { default: () => null },
    }).default
    let renderer
    await act(async () => { renderer = create(React.createElement(Header, {
        conversation: conversation(), clientAccount: account(), channel, employees: [],
        currentEmployeeId: 'employee', isEmployee: true, canManageAssignments: false,
        onTake() {}, onRelease() {}, onAssign() {}, onUnassign() {},
    })) })
    t.after(() => act(() => renderer.unmount()))
    const name = renderer.root.findByProps({ className: 'conversation-header-username' })
    assert.equal(name.children.join(''), '@account')
    assert.equal(name.props.title, '@account')
})

test('account picker clears stale results while searching and ignores aborted results/errors', async t => {
    const clock = browser(t)
    const old = deferred()
    const next = deferred()
    const Picker = load('../clients/ClientAccountPicker.tsx', {
        '../../api/clientAccountApi': { searchUnassignedClientAccounts: query => query === 'old' ? old.promise : query === 'new' ? next.promise : Promise.resolve([account()]) },
        './ClientAccountAvatar': { default: () => null },
    }).default
    let renderer
    await act(async () => { renderer = create(React.createElement(Picker, { open: true, onClose() {}, onSelect() {} })) })
    clock.cleanup(() => act(() => renderer.unmount()))
    await clock.flush()
    assert.equal(renderer.root.findAllByProps({ className: 'client-account-picker-item' }).length, 1)
    await act(async () => renderer.root.findByType('input').props.onChange({ target: { value: 'old' } }))
    assert.equal(renderer.root.findAllByProps({ className: 'client-account-picker-item' }).length, 0)
    await clock.flush()
    await act(async () => renderer.root.findByType('input').props.onChange({ target: { value: 'new' } }))
    await clock.flush()
    await act(async () => next.resolve([account({ id: 'new', displayName: 'New account' })]))
    await act(async () => old.reject(Error('Obsolete error')))
    assert.match(content(renderer), /New account/)
    assert.doesNotMatch(content(renderer), /Obsolete error/)
})

test('busy account picker cannot be closed through its backdrop', async t => {
    const clock = browser(t)
    let closed = 0
    const Picker = load('../clients/ClientAccountPicker.tsx', { '../../api/clientAccountApi': {}, './ClientAccountAvatar': { default: () => null } }).default
    let renderer
    await act(async () => { renderer = create(React.createElement(Picker, { open: true, busy: true, onClose: () => { closed++ }, onSelect() {} })) })
    clock.cleanup(() => act(() => renderer.unmount()))
    await act(async () => { const target = {}; renderer.root.findByProps({ className: 'client-modal-overlay' }).props.onMouseDown({ target, currentTarget: target }) })
    assert.equal(closed, 0)
})

async function view(t, overrides = {}, mockedPanel = false) {
    const clock = browser(t)
    let id = 'a', renderer, conversationReads = 0
    const updates = []
    global.window.addEventListener('clientbus:conversation-updated', event => updates.push(event.detail))
    const View = load('ConversationView.tsx', {
        'react-router-dom': { useParams: () => ({ conversationId: id }), useNavigate: () => () => {} },
        '../../auth/AuthContext': { useAuth: () => ({ currentEmployee: { id: 'employee' }, isEmployee: true, isSuperAdmin: false, employeeWorkspaces: [] }) },
        '../../workspace/WorkspaceContext': { useWorkspace: () => ({ setCurrentWorkspaceId() {} }) },
        '../../api/conversationApi': { getConversation: async id => { conversationReads++; return overrides.getConversation ? overrides.getConversation(id) : conversation(id) } },
        '../../api/clientAccountApi': { getClientAccount: overrides.getClientAccount ?? (async () => account()) },
        '../../api/channelApi': { getWorkspaceChannels: overrides.getWorkspaceChannels ?? (async () => [channel]) },
        '../../api/employeeApi': {},
        '../../api/clientApi': { getClient: async id => client(id), getClientAccountsByClient: async () => [], createClientFromConversation: async () => client(), unassignClientAccount: async () => account(), ...overrides },
        './useConversationMessages': { useConversationMessages: () => ({ messages: [], loading: false }) },
        './ConversationHeader': { default: props => React.createElement('header', props) },
        './MessageList': { default: () => null },
        ...(mockedPanel ? { './ConversationClientPanel': { default: props => React.createElement('aside', props) } } : {}),
        ...visualMocks,
    }).default
    await act(async () => { renderer = create(React.createElement(View, { visible: false })) })
    clock.cleanup(() => act(() => renderer.unmount()))
    return { renderer, updates, get reads() { return conversationReads },
        get details() { return renderer.root.findByType('aside').props },
        unmount: () => act(() => renderer.unmount()),
        switch: next => act(async () => { id = next; renderer.update(React.createElement(View, { visible: false })) }),
    }
}

test('create and unlink keep the actual message composer and its draft mounted', async t => {
    const ui = await view(t)
    const composer = ui.renderer.root.findByType('textarea')
    await act(async () => composer.props.onChange({ target: { value: 'Unsent draft' } }))
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', 'Name')
    await submit(ui.renderer)
    assert.equal(ui.renderer.root.findByType('textarea'), composer)
    assert.equal(composer.props.value, 'Unsent draft')
    assert.equal(ui.renderer.root.findByType('header').props.clientAccount.clientId, 'client')
    await click(ui.renderer, 'Отвязать аккаунт')
    assert.equal(ui.renderer.root.findByType('textarea'), composer)
    assert.equal(composer.props.value, 'Unsent draft')
    assert.equal(ui.renderer.root.findByType('header').props.clientAccount.clientId, null)
    assert.equal(ui.reads, 1)
    assert.equal(ui.updates.length, 3) // initial metadata + both relationship changes
})

test('retrying unavailable account/channel metadata preserves the composer draft', async t => {
    let fail = true
    const ui = await view(t, { getClientAccount: async () => { if (fail) throw Error('Account offline'); return account() }, getWorkspaceChannels: async () => { if (fail) throw Error('Channel offline'); return [channel] } })
    const composer = ui.renderer.root.findByType('textarea')
    await act(async () => composer.props.onChange({ target: { value: 'Keep draft' } }))
    assert.match(content(ui.renderer), /Account offline/)
    assert.match(content(ui.renderer), /Channel offline/)
    fail = false
    await act(async () => buttons(ui.renderer, 'Обновить данные')[0].props.onClick())
    assert.equal(ui.renderer.root.findByType('textarea'), composer)
    assert.equal(composer.props.value, 'Keep draft')
    assert.match(content(ui.renderer), /Клиент не создан/)
    assert.doesNotMatch(content(ui.renderer), /offline/)
})

test('a metadata read started before a successful link cannot revert the relationship', async t => {
    const pending = deferred()
    let reads = 0
    const ui = await view(t, { getClientAccount: async () => ++reads === 1 ? account() : pending.promise }, true)
    await act(async () => ui.details.onRetryDetails())
    await act(async () => ui.details.onChanged(account({ clientId: 'client' })))
    await act(async () => pending.resolve(account()))
    assert.equal(ui.details.clientAccount.clientId, 'client')
    assert.equal(ui.details.refreshingDetails, false)
})

test('a late detail read or callback cannot overwrite a reopened conversation', async t => {
    const pending = deferred()
    let reads = 0
    const ui = await view(t, { getClientAccount: async () => ++reads === 2 ? pending.promise : account() }, true)
    const oldCallback = ui.details.onChanged
    await act(async () => ui.details.onRetryDetails())
    await ui.switch('b')
    await ui.switch('a')
    await act(async () => { pending.resolve(account({ clientId: 'old' })); oldCallback(account({ clientId: 'old' })) })
    assert.equal(ui.details.clientAccount.clientId, null)
})

test('a failed channel read never reuses another channel from the same workspace', async t => {
    let fail = false
    const ui = await view(t, {
        getConversation: async id => ({ ...conversation(id), channelAccountId: id === 'b' ? 'other-channel-account' : 'channel-account' }),
        getWorkspaceChannels: async () => { if (fail) throw Error('Channel read failed'); return [channel] },
    }, true)
    assert.equal(ui.details.channel.id, 'channel')
    fail = true
    await ui.switch('b')
    assert.equal(ui.details.conversation.channelAccountId, 'other-channel-account')
    assert.equal(ui.details.channel, null)
    assert.equal(ui.renderer.root.findByType('header').props.channel, null)
    assert.equal(ui.details.channelError, 'Channel read failed')
})

test('a failed channel read retains metadata only for the same workspace and channel account', async t => {
    let fail = false
    const ui = await view(t, { getWorkspaceChannels: async () => { if (fail) throw Error('Channel read failed'); return [channel] } }, true)
    fail = true
    await ui.switch('b')
    assert.equal(ui.details.channel.id, 'channel')
    assert.equal(ui.details.channelError, 'Channel read failed')
})

test('a committed creation still notifies the original workspace after navigation', async t => {
    const pending = deferred()
    let persisted = false
    const ui = await view(t, {
        getConversation: async id => ({ ...conversation(id), clientAccountId: id === 'b' ? 'b-account' : 'account', workspaceId: id === 'b' ? 'other-workspace' : 'workspace' }),
        getClientAccount: async id => account({ id, clientId: id === 'account' && persisted ? 'client' : null }),
        createClientFromConversation: async () => { await pending.promise; persisted = true; return client() },
    })
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', 'Name')
    await submit(ui.renderer)
    await ui.switch('b')
    const composer = ui.renderer.root.findByType('textarea')
    await act(async () => composer.props.onChange({ target: { value: 'Conversation B draft' } }))
    const before = ui.updates.length
    await act(async () => pending.resolve())
    assert.equal(persisted, true)
    assert.equal(ui.updates.length, before + 1)
    assert.equal(ui.updates.at(-1).id, 'a')
    assert.equal(ui.updates.at(-1).workspaceId, 'workspace')
    const header = ui.renderer.root.findByType('header').props
    assert.equal(header.conversation.id, 'b')
    assert.equal(header.clientAccount.id, 'b-account')
    assert.equal(header.clientAccount.clientId, null)
    assert.equal(ui.renderer.root.findByType('textarea'), composer)
    assert.equal(composer.props.value, 'Conversation B draft')
})

test('a committed unlink still notifies the list after the entire view is unmounted', async t => {
    const pending = deferred()
    const ui = await view(t, {
        getClientAccount: async () => account({ clientId: 'client' }),
        unassignClientAccount: () => pending.promise,
    })
    await click(ui.renderer, 'Отвязать аккаунт')
    ui.unmount()
    const before = ui.updates.length
    await act(async () => pending.resolve(account()))
    assert.equal(ui.updates.length, before + 1)
    assert.equal(ui.updates.at(-1).id, 'a')
    assert.equal(ui.reads, 1)
})

test('recovering a committed creation with a lost response also updates filtered lists', async t => {
    let persisted = false
    const ui = await view(t, {
        getClientAccount: async () => account({ clientId: persisted ? 'client' : null }),
        createClientFromConversation: async () => { persisted = true; throw Error('Response lost after commit') },
    })
    const composer = ui.renderer.root.findByType('textarea')
    await act(async () => composer.props.onChange({ target: { value: 'Keep this draft' } }))
    await click(ui.renderer, 'Создать клиента')
    await type(ui.renderer, 'Имя', 'Name')
    await submit(ui.renderer)
    const before = ui.updates.length
    assert.match(content(ui.renderer), /Response lost after commit/)
    await click(ui.renderer, 'Обновить данные')
    assert.equal(ui.renderer.root.findByType('header').props.clientAccount.clientId, 'client')
    assert.equal(ui.updates.length, before + 1)
    assert.equal(ui.updates.at(-1).id, 'a')
    assert.equal(ui.renderer.root.findByType('textarea'), composer)
    assert.equal(composer.props.value, 'Keep this draft')
})

test('failed metadata retries do not publish a successful relationship change', async t => {
    let fail = false
    const ui = await view(t, { getClientAccount: async () => { if (fail) throw Error('Offline'); return account() } }, true)
    const before = ui.updates.length
    fail = true
    await act(async () => ui.details.onRetryDetails())
    assert.equal(ui.updates.length, before)
    assert.equal(ui.details.clientAccount.clientId, null)
    assert.equal(ui.details.accountError, 'Offline')
})
