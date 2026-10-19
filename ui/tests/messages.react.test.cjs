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
function message(id, extra = {}) {
    return { id, conversationId: 'a', externalId: `external-${id}`, createdAt: `2026-10-18T12:00:${String(id).padStart(2, '0')}Z`,
        sentAt: null, content: `Message ${id}`, type: 'TEXT', direction: 'OUTBOUND', processingStatus: 'PROCESSED', deliveryStatus: 'SENT', ...extra }
}
const page = (content, last = true) => ({ content, last })

async function history(t, api, id = 'a', visible = false) {
    const { useConversationMessages } = load('useConversationMessages.ts', { '../../api/messageApi': api })
    let model, renderer
    function Probe({ id, visible }) { model = useConversationMessages(id, visible); return null }
    await act(async () => { renderer = create(React.createElement(Probe, { id, visible })) })
    t.after(() => act(() => renderer.unmount()))
    return { get model() { return model },
        switch: async (id, active = visible) => act(async () => renderer.update(React.createElement(Probe, { id, visible: active }))) }
}

test('message API normalizes Spring PagedModel and legacy Page metadata', async () => {
    for (const json of [
        { content: [message(1)], page: { number: 1, size: 100, totalPages: 2, totalElements: 101 } },
        { content: [message(1)], number: 1, size: 100, totalPages: 2, totalElements: 101, last: true, first: false },
    ]) {
        const api = load('../../api/messageApi.ts', { './apiClient': { apiFetch: async () => ({ json: async () => json }) } })
        const result = await api.getConversationMessages('a', 1, 100)
        assert.equal(result.last, true)
        assert.equal(result.first, false)
        assert.equal(result.number, 1)
        assert.equal(result.numberOfElements, 1)
    }
})

test('history exhausts local pages before requesting the platform and merges without duplicates', async t => {
    const calls = []
    const ui = await history(t, {
        getConversationMessages: async (_, index) => { calls.push(['local', index]); return index ? page([message(2), message(1)]) : page([message(3), message(2)], false) },
        syncConversationHistory: async (_, cursor) => { calls.push(['remote', cursor]); return { messages: [message(1), message(0)], historyStartReached: true } },
    })
    await act(async () => ui.model.loadOlderMessages())
    assert.deepEqual(ui.model.messages.map(m => m.id), [1, 2, 3])
    assert.deepEqual(calls, [['local', 0], ['local', 1]])
    await act(async () => ui.model.loadOlderMessages())
    assert.deepEqual(calls.at(-1), ['remote', 'external-1'])
    assert.deepEqual(ui.model.messages.map(m => m.id), [0, 1, 2, 3])
    assert.equal(ui.model.historyStartReached, true)
})

test('empty local history syncs immediately; duplicate remote pages stop automatic loading', async t => {
    let syncs = 0
    const ui = await history(t, { getConversationMessages: async () => page([]),
        syncConversationHistory: async () => { syncs++; return { messages: [message(1)], historyStartReached: false } } })
    assert.equal(syncs, 1)
    await act(async () => ui.model.loadOlderMessages())
    assert.equal(ui.model.hasMore, false)
    await act(async () => ui.model.loadOlderMessages())
    assert.equal(syncs, 2)
    await act(async () => ui.model.retryOlderMessages())
    assert.equal(syncs, 3)
})

test('a failed history request keeps messages and requires an explicit retry', async t => {
    let attempts = 0
    const ui = await history(t, { getConversationMessages: async () => page([message(2)]),
        syncConversationHistory: async () => { if (++attempts === 1) throw Error('History timeout'); return { messages: [message(1)], historyStartReached: true } } })
    await act(async () => ui.model.loadOlderMessages())
    assert.equal(ui.model.error, 'History timeout')
    assert.deepEqual(ui.model.messages.map(m => m.id), [2])
    await act(async () => ui.model.loadOlderMessages())
    assert.equal(attempts, 1)
    await act(async () => ui.model.retryOlderMessages())
    assert.equal(ui.model.error, null)
    assert.equal(ui.model.messages.length, 2)
})

test('late initial responses cannot replace another conversation, including A-B-A', async t => {
    const first = deferred()
    let requests = 0
    const ui = await history(t, { getConversationMessages: async id => {
        if (++requests === 1) return first.promise
        return page([message(requests, { conversationId: id })])
    } })
    await ui.switch('b')
    await ui.switch('a')
    await act(async () => first.resolve(page([message(1)])))
    assert.deepEqual(ui.model.messages.map(m => m.id), [3])
    assert.equal(ui.model.loading, false)
})

test('late history responses and duplicate load requests cannot cross conversation boundaries', async t => {
    const old = deferred()
    let syncs = 0
    const ui = await history(t, { getConversationMessages: async id => page([message(2, { conversationId: id })]),
        syncConversationHistory: async () => { syncs++; return old.promise } })
    await act(async () => { void ui.model.loadOlderMessages(); void ui.model.loadOlderMessages() })
    assert.equal(syncs, 1)
    await ui.switch('b')
    await act(async () => old.resolve({ messages: [message(1)], historyStartReached: true }))
    assert.deepEqual(ui.model.messages.map(m => m.conversationId), ['b'])
    assert.equal(ui.model.historyStartReached, false)
})

test('refresh updates delivery states and merges new messages without dropping loaded history', async t => {
    let latest = [message(2, { deliveryStatus: 'PENDING' })]
    const ui = await history(t, { getConversationMessages: async () => page(latest),
        getMessage: async id => message(id),
        syncConversationHistory: async () => ({ messages: [message(1)], historyStartReached: true }) })
    await act(async () => ui.model.loadOlderMessages())
    latest = [message(3), message(2, { deliveryStatus: 'READ' })]
    await act(async () => ui.model.refreshMessages())
    assert.deepEqual(ui.model.messages.map(m => m.id), [1, 2, 3])
    assert.equal(ui.model.messages[1].deliveryStatus, 'READ')
    assert.equal(ui.model.loading, false)
    assert.equal(ui.model.historyStartReached, true)
    assert.equal(ui.model.refreshError, null)
})

test('retry sends one request per message and refreshes pending statuses outside the newest page', async t => {
    const pending = deferred()
    let retries = 0, statusLoads = 0
    const ui = await history(t, { getConversationMessages: async () => page([message(3)]),
        retryOutboundMessage: async () => { retries++; return pending.promise },
        getMessage: async () => { statusLoads++; return message(1, { deliveryStatus: 'READ' }) } })
    await act(async () => ui.model.acceptMessage(message(1, { deliveryStatus: 'FAILED' })))
    await act(async () => { void ui.model.retryMessage(1); void ui.model.retryMessage(1) })
    assert.equal(retries, 1)
    assert.deepEqual(ui.model.retryingIds, [1])
    await act(async () => pending.resolve(message(1, { deliveryStatus: 'PENDING' })))
    await act(async () => ui.model.refreshMessages())
    assert.equal(statusLoads, 1)
    assert.equal(ui.model.messages[0].deliveryStatus, 'READ')
    assert.deepEqual(ui.model.retryingIds, [])
})

test('a refresh error retains the visible messages and clears on the next successful refresh', async t => {
    let fail = false
    const ui = await history(t, { getConversationMessages: async () => { if (fail) throw Error('Offline'); return page([message(1)]) } })
    fail = true
    await act(async () => ui.model.refreshMessages())
    assert.equal(ui.model.messages.length, 1)
    assert.equal(ui.model.refreshError, 'Offline')
    assert.equal(ui.model.error, null)
    fail = false
    await act(async () => ui.model.refreshMessages())
    assert.equal(ui.model.refreshError, null)
})

for (const source of ['initial', 'refresh', 'local history', 'remote history', 'status', 'retry']) {
    test(`access denial from ${source} clears cached messages and invalidates the message session`, async t => {
        const denial = Object.assign(Error('No access'), { status: 403 })
        let reads = 0, notifications = 0
        const api = {
            getConversationMessages: async () => {
                reads++
                if ((source === 'initial' && reads === 1) || (source === 'refresh' && reads === 2)
                    || (source === 'local history' && reads === 2)) throw denial
                return page([message(reads)], source !== 'local history')
            },
            syncConversationHistory: async () => { throw denial },
            getMessage: async () => { throw denial },
            retryOutboundMessage: async () => { throw denial },
        }
        const { useConversationMessages } = load('useConversationMessages.ts', { '../../api/messageApi': api })
        let state, renderer
        function Probe() {
            state = useConversationMessages('a', false, failure => { assert.equal(failure, denial); notifications++ })
            return null
        }
        await act(async () => { renderer = create(React.createElement(Probe)) })
        t.after(() => act(() => renderer.unmount()))
        if (source === 'status') {
            await act(async () => state.acceptMessage(message(0)))
        }
        if (['refresh', 'status'].includes(source)) await act(async () => state.refreshMessages())
        if (source.includes('history')) await act(async () => state.loadOlderMessages())
        if (source === 'retry') await act(async () => assert.rejects(state.retryMessage(1), denial))
        assert.equal(notifications, 1)
        assert.deepEqual(state.messages, [])
        assert.equal(state.loading, false)
        assert.equal(state.loadingOlder, false)
        assert.equal(state.hasMore, false)
        const before = reads
        await act(async () => { await state.refreshMessages(); await state.retryOlderMessages(); await state.retryMessage(1) })
        assert.equal(reads, before)
        assert.equal(notifications, 1)
    })
}

async function composer(t, send, onSent = () => {}) {
    const Composer = load('MessageComposer.tsx', { '../../api/messageApi': { sendOutboundMessage: send } }).default
    let renderer
    const tree = id => React.createElement(Composer, { key: id, conversationId: id, onSent })
    await act(async () => { renderer = create(tree('a')) })
    t.after(() => act(() => renderer.unmount()))
    return { renderer,
        text: value => act(async () => renderer.root.findByType('textarea').props.onChange({ target: { value } })),
        submit: () => renderer.root.findByType('form').props.onSubmit({ preventDefault() {} }),
        switch: id => act(async () => renderer.update(tree(id))) }
}

test('composer prevents double submits and retains the draft when sending fails', async t => {
    const pending = deferred()
    let sends = 0
    const ui = await composer(t, () => { sends++; return pending.promise })
    await ui.text('Draft')
    await act(async () => { ui.submit(); ui.submit() })
    assert.equal(sends, 1)
    await act(async () => pending.reject(Error('Network error')))
    assert.equal(ui.renderer.root.findByType('textarea').props.value, 'Draft')
    assert.equal(ui.renderer.root.findByProps({ className: 'message-composer-error' }).children.join(''), 'Network error')
})

test('a successful send clears the draft even if the subsequent refresh fails', async t => {
    const ui = await composer(t, async () => message(1), async () => { throw Error('Refresh failed') })
    await ui.text('Sent')
    await act(async () => ui.submit())
    assert.equal(ui.renderer.root.findByType('textarea').props.value, '')
    assert.equal(ui.renderer.root.findAllByProps({ className: 'message-composer-error' }).length, 0)
})

test('a late send cannot clear another conversation draft or call its refresh', async t => {
    const pending = deferred()
    let callbacks = 0
    const ui = await composer(t, () => pending.promise, () => { callbacks++ })
    await ui.text('A')
    await act(async () => ui.submit())
    await ui.switch('b')
    await ui.text('B draft')
    await act(async () => pending.resolve(message(1)))
    assert.equal(ui.renderer.root.findByType('textarea').props.value, 'B draft')
    assert.equal(callbacks, 0)
})

test('IME Enter and Shift+Enter do not send a message', async t => {
    let sends = 0
    const ui = await composer(t, async () => { sends++; return message(1) })
    await ui.text('Text')
    for (const extra of [{ nativeEvent: { isComposing: true } }, { shiftKey: true }, { keyCode: 229 }]) {
        await act(async () => ui.renderer.root.findByType('textarea').props.onKeyDown({ key: 'Enter', preventDefault() {}, ...extra }))
    }
    assert.equal(sends, 0)
})

test('retry control is offered only for delivery failures supported by the backend', async t => {
    const Bubble = load('MessageBubble.tsx', { '../../api/messageApi': {}, './ImageViewer': { default: () => null } }).default
    let renderer
    const tree = (processingStatus, onRetry) => React.createElement(Bubble, { message: message(1, { deliveryStatus: 'FAILED', processingStatus }), onRetry })
    await act(async () => { renderer = create(tree('PROCESSED', async () => {})) })
    t.after(() => act(() => renderer.unmount()))
    assert.equal(renderer.root.findAllByType('button').length, 1)
    await act(async () => renderer.update(tree('FAILED', async () => {})))
    assert.equal(renderer.root.findAllByType('button').length, 0)
    await act(async () => renderer.update(tree('PROCESSED', undefined)))
    assert.equal(renderer.root.findAllByType('button').length, 0)
})

test('polling pauses for a hidden conversation or browser document', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const previousDocument = global.document
    global.document = { hidden: false }
    t.after(() => { global.document = previousDocument })
    let requests = 0
    const ui = await history(t, { getConversationMessages: async () => { requests++; return page([message(1)]) } }, 'a', true)
    await act(async () => t.mock.timers.tick(5000))
    assert.equal(requests, 2)
    global.document.hidden = true
    await act(async () => t.mock.timers.tick(5000))
    assert.equal(requests, 2)
    global.document.hidden = false
    await ui.switch('a', false)
    await act(async () => t.mock.timers.tick(10000))
    assert.equal(requests, 2)
})

test('a refresh started before retry cannot restore the old failed state', async t => {
    const pending = deferred()
    let requests = 0
    const ui = await history(t, { getConversationMessages: async () => ++requests === 1 ? page([message(1, { deliveryStatus: 'FAILED' })]) : pending.promise,
        retryOutboundMessage: async () => message(1, { deliveryStatus: 'PENDING' }) })
    await act(async () => { void ui.model.refreshMessages() })
    await act(async () => ui.model.retryMessage(1))
    await act(async () => pending.resolve(page([message(1, { deliveryStatus: 'FAILED' })])))
    assert.equal(ui.model.messages[0].deliveryStatus, 'PENDING')
})

async function thread(t, operations = {}, visible = false, messageApi) {
    let id = 'a', renderer
    const reloadMessages = () => {}
    const navigations = []
    const updates = []
    const unavailable = []
    const originalWindow = global.window
    global.window = new EventTarget()
    global.window.addEventListener('clientbus:conversation-updated', event => updates.push(event.detail))
    global.window.addEventListener('clientbus:conversation-unavailable', event => unavailable.push(event.detail))
    const view = load('ConversationView.tsx', {
        'react-router-dom': { useParams: () => ({ conversationId: id }), useNavigate: () => path => navigations.push(path) },
        '../../auth/AuthContext': { useAuth: () => ({ currentEmployee: { id: 'employee' }, isEmployee: true, isSuperAdmin: false, employeeWorkspaces: [] }) },
        '../../workspace/WorkspaceContext': { useWorkspace: () => ({ setCurrentWorkspaceId() {} }) },
        '../../api/conversationApi': {
            getConversation: async id => ({ id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: null }),
            ...operations,
        },
        '../../api/clientAccountApi': { getClientAccount: async () => ({ id: 'account' }) },
        '../../api/channelApi': { getWorkspaceChannels: async () => [] },
        '../../api/employeeApi': { getWorkspaceEmployees: async () => [] },
        ...(messageApi ? { '../../api/messageApi': messageApi } : {
            './useConversationMessages': { useConversationMessages: () => ({ messages: [], loading: false, hasMore: false,
                error: null, acceptMessage() {}, reloadMessages }) },
        }),
        './ConversationHeader': { default: props => React.createElement('header', props) },
        './ConversationClientPanel': { default: () => null },
        './MessageList': { default: () => null },
        './MessageComposer': { default: props => React.createElement('textarea', { onSent: props.onSent }) },
    }).default
    await act(async () => { renderer = create(React.createElement(view, { visible })) })
    t.after(() => { act(() => renderer.unmount()); global.window = originalWindow })
    return { renderer, navigations, updates, unavailable, get header() { return renderer.root.findByType('header').props },
        switch: next => act(async () => { id = next; renderer.update(React.createElement(view, { visible })) }) }
}

test('late assignment cannot navigate away from a newly opened conversation', async t => {
    const pending = deferred()
    const ui = await thread(t, { assignConversationToMe: () => pending.promise })
    await act(async () => { void ui.header.onTake() })
    await ui.switch('b')
    await act(async () => pending.resolve({ id: 'a', assignedEmployeeId: 'employee' }))
    assert.equal(ui.header.conversation.id, 'b')
    assert.deepEqual(ui.navigations, [])
})

test('concurrent take is submitted once; a conflict is displayed and server ownership is restored', async t => {
    const pending = deferred()
    let takes = 0, reads = 0
    const ui = await thread(t, {
        getConversation: async id => ({ id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0,
            assignedEmployeeId: ++reads === 1 ? null : 'other' }),
        assignConversationToMe: () => { takes++; return pending.promise },
    })
    await act(async () => { void ui.header.onTake(); void ui.header.onTake() })
    assert.equal(takes, 1)
    await act(async () => pending.reject(Error('Already assigned')))
    assert.equal(ui.header.actionError, 'Already assigned')
    assert.equal(ui.header.conversation.assignedEmployeeId, 'other')
    assert.equal(ui.updates[ui.updates.length - 1].assignedEmployeeId, 'other')
    assert.deepEqual(ui.navigations, [])
})

test('successful take and release update ownership and navigate to the corresponding scope', async t => {
    const ui = await thread(t, {
        assignConversationToMe: async id => ({ id, assignedEmployeeId: 'employee' }),
        unassignConversationFromMe: async id => ({ id, assignedEmployeeId: null }),
    })
    await act(async () => ui.header.onTake())
    assert.equal(ui.header.conversation.assignedEmployeeId, 'employee')
    await act(async () => ui.header.onRelease())
    assert.equal(ui.header.conversation.assignedEmployeeId, null)
    assert.deepEqual(ui.navigations, ['/inbox/a?tab=mine', '/inbox/a?tab=unassigned'])
})

test('a take conflict refreshes the list even when the winner makes the conversation inaccessible', async t => {
    let reads = 0
    const ui = await thread(t, {
        getConversation: async id => {
            if (++reads > 1) throw Object.assign(Error('Assigned to another employee'), { status: 403 })
            return { id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: null }
        },
        assignConversationToMe: async () => { throw Object.assign(Error('Already assigned'), { status: 409, code: 'CONVERSATION_ASSIGNMENT_CONFLICT' }) },
    })
    const before = ui.updates.length
    await act(async () => ui.header.onTake())
    assert.ok(ui.updates.length > before)
    assert.equal(ui.updates.at(-1).workspaceId, 'workspace')
    assert.equal(ui.renderer.root.findAllByType('header').length, 0)
    assert.equal(ui.renderer.root.findAllByType('textarea').length, 0)
    assert.match(ui.renderer.root.findByProps({ className: 'conversation-state conversation-error' }).children.join(''), /Assigned to another employee/)
    assert.deepEqual(ui.navigations, [])
})

for (const status of [403, 404]) {
    test(`metadata polling hides a previously visible thread after HTTP ${status}`, async t => {
        t.mock.timers.enable({ apis: ['setTimeout'] })
        let reads = 0
        const ui = await thread(t, { getConversation: async id => {
            if (++reads > 1) throw Object.assign(Error('Conversation unavailable'), { status })
            return { id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: 'employee' }
        } }, true)
        assert.equal(ui.renderer.root.findAllByType('textarea').length, 1)
        await act(async () => t.mock.timers.tick(10000))
        assert.equal(ui.renderer.root.findAllByType('header').length, 0)
        assert.equal(ui.renderer.root.findAllByType('textarea').length, 0)
        assert.match(ui.renderer.root.findByProps({ className: 'conversation-state conversation-error' }).children.join(''), /Conversation unavailable/)
        assert.deepEqual(ui.unavailable, [{ id: 'a' }])
        await act(async () => t.mock.timers.tick(30000))
        assert.equal(reads, 2)
    })
}

for (const status of [403, 404]) {
    test(`message polling HTTP ${status} closes the thread, stops both polls and supports recovery`, async t => {
        t.mock.timers.enable({ apis: ['setTimeout'] })
        let messageReads = 0, metadataReads = 0, restored = false
        const ui = await thread(t, {
            getConversation: async id => {
                metadataReads++
                return { id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: 'employee' }
            },
        }, true, { getConversationMessages: async () => {
            if (++messageReads > 1 && !restored) throw Object.assign(Error('Access revoked'), { status })
            return page([message(1)])
        } })
        assert.equal(ui.renderer.root.findAllByType('textarea').length, 1)
        await act(async () => t.mock.timers.tick(5000))
        assert.equal(ui.renderer.root.findAllByType('header').length, 0)
        assert.equal(ui.renderer.root.findAllByType('textarea').length, 0)
        assert.deepEqual(ui.unavailable, [{ id: 'a' }])
        await act(async () => t.mock.timers.tick(30000))
        assert.equal(messageReads, 2)
        assert.equal(metadataReads, 1)
        restored = true
        await act(async () => ui.renderer.root.findByType('button').props.onClick())
        assert.equal(messageReads, 3)
        assert.equal(metadataReads, 2)
        assert.equal(ui.renderer.root.findAllByType('textarea').length, 1)
        await act(async () => t.mock.timers.tick(5000))
        assert.equal(messageReads, 4)
    })
}

test('message access denial invalidates a pending metadata response', async t => {
    const metadata = deferred()
    const ui = await thread(t, { getConversation: () => metadata.promise }, true, {
        getConversationMessages: async () => { throw Object.assign(Error('No access'), { status: 403 }) },
    })
    await act(async () => metadata.resolve({ id: 'a', workspaceId: 'workspace', assignedEmployeeId: 'employee' }))
    assert.equal(ui.renderer.root.findAllByType('header').length, 0)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-state conversation-error' }).length, 1)
    assert.deepEqual(ui.unavailable, [{ id: 'a' }])
})

test('a late message denial cannot close a newer conversation', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const obsolete = deferred()
    let reads = 0
    const ui = await thread(t, {}, true, { getConversationMessages: async () => {
        if (++reads === 2) return obsolete.promise
        return page([message(1)])
    } })
    await act(async () => t.mock.timers.tick(5000))
    await ui.switch('b')
    await act(async () => obsolete.reject(Object.assign(Error('Old denial'), { status: 403 })))
    assert.equal(ui.header.conversation.id, 'b')
    assert.deepEqual(ui.unavailable, [])
})

test('access loss during initial metadata loading shows an error and ignores the late initial response', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const initial = deferred()
    let reads = 0
    const ui = await thread(t, { getConversation: async id => {
        if (++reads === 1) return initial.promise
        throw Object.assign(Error('Access revoked'), { status: 403 })
    } }, true)
    await act(async () => t.mock.timers.tick(10000))
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-state conversation-error' }).length, 1)
    await act(async () => initial.resolve({ id: 'a', workspaceId: 'workspace', clientAccountId: 'account', assignedEmployeeId: 'employee' }))
    assert.equal(ui.renderer.root.findAllByType('header').length, 0)
    assert.equal(ui.renderer.root.findAllByProps({ className: 'conversation-state conversation-error' }).length, 1)
})

test('temporary metadata errors keep the thread and sending controls available', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    let reads = 0
    const ui = await thread(t, { getConversation: async id => {
        if (++reads > 1) throw Object.assign(Error('Server unavailable'), { status: 503 })
        return { id, workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: 'employee' }
    } }, true)
    await act(async () => t.mock.timers.tick(10000))
    assert.equal(ui.renderer.root.findAllByType('textarea').length, 1)
    assert.equal(ui.header.actionError, 'Server unavailable')
})

test('message list follows appended messages only while the operator is at the bottom', async t => {
    const List = load('MessageList.tsx', { './MessageBubble': { default: () => null } }).default
    const container = { scrollTop: 0, scrollHeight: 1000, clientHeight: 300 }
    let renderer
    const tree = messages => React.createElement(List, { messages, loading: false, loadingOlder: false,
        hasMore: false, historyStartReached: true, error: null, onLoadOlder: async () => {} })
    await act(async () => { renderer = create(tree([message(1)]), { createNodeMock: element => element.props.className === 'message-list' ? container : null }) })
    t.after(() => act(() => renderer.unmount()))
    assert.equal(container.scrollTop, 1000)
    container.scrollTop = 200
    await act(async () => renderer.root.findByProps({ className: 'message-list' }).props.onScroll())
    container.scrollHeight = 1100
    await act(async () => renderer.update(tree([message(1), message(2)])))
    assert.equal(container.scrollTop, 200)
    container.scrollTop = 800
    await act(async () => renderer.root.findByProps({ className: 'message-list' }).props.onScroll())
    container.scrollHeight = 1200
    await act(async () => renderer.update(tree([message(1), message(2), message(3)])))
    assert.equal(container.scrollTop, 1200)
})

test('initial history arriving after a send cannot erase the newly accepted message', async t => {
    const pending = deferred()
    const ui = await history(t, { getConversationMessages: () => pending.promise })
    await act(async () => ui.model.acceptMessage(message(2)))
    await act(async () => pending.resolve(page([message(1)])))
    assert.deepEqual(ui.model.messages.map(m => m.id), [1, 2])
})

test('cancelling the attachment picker does not prevent sending existing text', async t => {
    const requests = []
    const ui = await composer(t, async (...args) => { requests.push(args); return message(1) })
    await ui.text('Text')
    await act(async () => ui.renderer.root.findByProps({ className: 'ui-button ui-button-sm ui-button-secondary message-composer-add-button' }).props.onClick())
    const photo = ui.renderer.root.findAllByType('button').find(node => node.children.join('') === 'Фото')
    await act(async () => photo.props.onClick())
    await act(async () => ui.submit())
    assert.equal(requests[0][1], 'TEXT')
    assert.equal(requests[0][2], 'Text')
})

test('late assignment is ignored after reopening the same conversation', async t => {
    const pending = deferred()
    const ui = await thread(t, { assignConversationToMe: () => pending.promise })
    await act(async () => { void ui.header.onTake() })
    await ui.switch('b')
    await ui.switch('a')
    await act(async () => pending.resolve({ id: 'a', assignedEmployeeId: 'employee' }))
    assert.equal(ui.header.conversation.assignedEmployeeId, null)
    assert.deepEqual(ui.navigations, [])
})

test('refresh recovers an initial local-load failure and restores the next local page cursor', async t => {
    const pages = []
    const ui = await history(t, { getConversationMessages: async (_, index) => {
        pages.push(index)
        if (pages.length === 1) throw Error('Offline')
        return index ? page([message(1)]) : page([message(2)], false)
    } })
    assert.equal(ui.model.error, 'Offline')
    await act(async () => ui.model.refreshMessages())
    assert.equal(ui.model.error, null)
    await act(async () => ui.model.loadOlderMessages())
    assert.deepEqual(pages, [0, 0, 1])
    assert.deepEqual(ui.model.messages.map(m => m.id), [1, 2])
})

function orderedMessage(id, extra = {}) {
    const time = new Date(Date.UTC(2026, 9, 18) + id * 1000).toISOString()
    return message(String(id), { createdAt: time, sentAt: time, direction: 'INBOUND', ...extra })
}

test('refresh fills a multi-page gap after a pause even with locally accepted messages above the old frontier', async t => {
    let source = Array.from({ length: 100 }, (_, index) => orderedMessage(100 - index))
    const pages = []
    const ui = await history(t, {
        getConversationMessages: async (_, index, size) => { pages.push(index); return page(source.slice(index * size, (index + 1) * size), (index + 1) * size >= source.length) },
        syncConversationHistory: async () => ({ messages: [], historyStartReached: true }),
    })
    await act(async () => ui.model.loadOlderMessages())
    source = Array.from({ length: 251 }, (_, index) => orderedMessage(251 - index))
    await act(async () => ui.model.acceptMessage(source[0]))
    await act(async () => ui.model.refreshMessages())
    assert.deepEqual(pages, [0, 0, 1])
    assert.deepEqual(ui.model.messages.map(m => m.id), Array.from({ length: 251 }, (_, i) => String(i + 1)))
    assert.equal(ui.model.historyStartReached, true)
    assert.equal(ui.model.refreshError, null)
})

test('refresh fills every local page when a previously empty conversation receives more than 100 messages', async t => {
    let source = []
    const ui = await history(t, { getConversationMessages: async (_, index, size) => page(source.slice(index * size, (index + 1) * size), (index + 1) * size >= source.length),
        syncConversationHistory: async () => ({ messages: [], historyStartReached: true }) })
    source = Array.from({ length: 150 }, (_, index) => orderedMessage(150 - index))
    await act(async () => ui.model.refreshMessages())
    assert.equal(ui.model.messages.length, 150)
    assert.equal(ui.model.messages[0].id, '1')
})

test('old outgoing statuses advance through SENT and DELIVERED to READ and then stop polling', async t => {
    const statuses = ['SENT', 'DELIVERED', 'READ']
    let loads = 0
    const ui = await history(t, { getConversationMessages: async () => page([orderedMessage(200)]),
        getMessage: async () => orderedMessage(1, { direction: 'OUTBOUND', deliveryStatus: statuses[loads++] }) })
    await act(async () => ui.model.acceptMessage(orderedMessage(1, { direction: 'OUTBOUND', deliveryStatus: 'PENDING' })))
    for (const status of statuses) {
        await act(async () => ui.model.refreshMessages())
        assert.equal(ui.model.messages[0].deliveryStatus, status)
    }
    await act(async () => ui.model.refreshMessages())
    assert.equal(loads, 3)
    assert.equal(ui.model.refreshError, null)
})

test('skipped history loading leaves no scroll snapshot to corrupt the next live update', async t => {
    const List = load('MessageList.tsx', { './MessageBubble': { default: () => null } }).default
    const container = { scrollTop: 0, scrollHeight: 1000, clientHeight: 300 }
    let renderer
    const tree = messages => React.createElement(List, { messages, loading: false, loadingOlder: false,
        hasMore: true, historyStartReached: false, error: null, onLoadOlder: async () => {} })
    await act(async () => { renderer = create(tree([message(1)]), { createNodeMock: element => element.props.className === 'message-list' ? container : null }) })
    t.after(() => act(() => renderer.unmount()))
    container.scrollTop = 100
    await act(async () => renderer.root.findByProps({ className: 'message-list' }).props.onScroll())
    container.scrollTop = 700
    await act(async () => renderer.root.findByProps({ className: 'message-list' }).props.onScroll())
    container.scrollHeight = 1100
    await act(async () => renderer.update(tree([message(1), message(2)])))
    assert.equal(container.scrollTop, 1100)
})

test('history reports a start only when it actually issues a request', async t => {
    const refreshing = deferred()
    let requests = 0, starts = 0
    const ui = await history(t, { getConversationMessages: async () => ++requests === 1 ? page([orderedMessage(2)]) : refreshing.promise,
        syncConversationHistory: async () => ({ messages: [orderedMessage(1)], historyStartReached: true }) })
    await act(async () => { void ui.model.refreshMessages() })
    await act(async () => ui.model.loadOlderMessages(() => { starts++ }))
    assert.equal(starts, 0)
    await act(async () => refreshing.resolve(page([orderedMessage(2)])))
    await act(async () => ui.model.loadOlderMessages(() => { starts++ }))
    assert.equal(starts, 1)
})

test('a late conversation poll cannot restore old ownership after a newer post-send refresh', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const older = deferred(), newer = deferred()
    let reads = 0
    const base = { id: 'a', workspaceId: 'workspace', clientAccountId: 'account', unreadCount: 0, assignedEmployeeId: 'employee' }
    const ui = await thread(t, { getConversation: async () => ++reads === 1 ? base : reads === 2 ? older.promise : newer.promise }, true)
    await act(async () => t.mock.timers.tick(10000))
    await act(async () => { void ui.renderer.root.findByType('textarea').props.onSent({ conversationId: 'a' }) })
    await act(async () => newer.resolve({ ...base, assignedEmployeeId: 'other' }))
    await act(async () => older.resolve(base))
    assert.equal(ui.header.conversation.assignedEmployeeId, 'other')
    assert.equal(ui.renderer.root.findAllByType('textarea').length, 0)
    assert.equal(ui.updates[ui.updates.length - 1].assignedEmployeeId, 'other')
})

test('a pending message pinned above sent messages cannot hide a new multi-page range', async t => {
    const pending = orderedMessage(10000, { sentAt: null, direction: 'OUTBOUND', deliveryStatus: 'PENDING' })
    let source = [pending, ...Array.from({ length: 99 }, (_, index) => orderedMessage(99 - index))]
    const ui = await history(t, {
        getConversationMessages: async (_, index, size) => page(source.slice(index * size, (index + 1) * size), (index + 1) * size >= source.length),
    })
    source = [pending, ...Array.from({ length: 249 }, (_, index) => orderedMessage(249 - index))]
    await act(async () => ui.model.refreshMessages())
    assert.equal(ui.model.messages.length, 250)
    assert.equal(ui.model.refreshError, null)
})

test('starting a real older-history request preserves position when its messages are prepended', async t => {
    const pending = deferred()
    const container = { scrollTop: 0, scrollHeight: 1000, clientHeight: 300 }
    const { useConversationMessages } = load('useConversationMessages.ts', { '../../api/messageApi': {
        getConversationMessages: async () => page([orderedMessage(2)]), syncConversationHistory: () => pending.promise,
    } })
    const List = load('MessageList.tsx', { './MessageBubble': { default: () => null } }).default
    function Probe() {
        const state = useConversationMessages('a', false)
        return React.createElement(List, { ...state, onLoadOlder: state.loadOlderMessages })
    }
    let renderer
    await act(async () => { renderer = create(React.createElement(Probe), { createNodeMock: element => element.props.className === 'message-list' ? container : null }) })
    t.after(() => act(() => renderer.unmount()))
    container.scrollTop = 100
    await act(async () => renderer.root.findByProps({ className: 'message-list' }).props.onScroll())
    container.scrollHeight = 1300
    await act(async () => pending.resolve({ messages: [orderedMessage(1)], historyStartReached: true }))
    assert.equal(container.scrollTop, 400)
})
