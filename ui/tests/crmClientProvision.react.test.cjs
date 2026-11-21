const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const scope = load('../../api/events/uiScope.ts')
const apiClient = load('../../api/apiClient.ts')
const project = { id: 'p', workspaceId: 'w', provider: 'DIKIDI', displayName: 'Салон на Лесной', contextVersion: 3, capabilities: [{ code: 'CREATE_CLIENT', availability: 'AVAILABLE' }] }
const client = { id: 'core', firstName: 'Анна', lastName: 'Смирнова', phoneList: ['+7 (999) 123-45-67'] }
const candidate = { id: 'crm', externalId: '42', displayName: 'Анна CRM', normalizedPhone: '79991234567', version: 7, clientId: null }
const button = (ui, name) => ui.root.findAllByType('button').find(n => n.children.join('') === name)
const click = (ui, name) => act(async () => { const b = button(ui, name); assert.ok(b, name); assert.equal(!!b.props.disabled, false); b.props.onClick() })
const text = ui => JSON.stringify(ui.toJSON())
async function fixture(t, overrides = {}, source = client) {
    const calls = [], invalidations = new Map()
    const api = { crmError: e => e.message, getLatestClientProvision: async () => null,
        searchCrmClients: async (...args) => { calls.push(['search', ...args]); return { items: [], hasMore: false } },
        provisionCrmClient: async (...args) => { calls.push(['create', ...args]); return { id: 'op', status: 'REQUESTED' } },
        linkCrmClient: async (...args) => calls.push(['link', ...args]), ...overrides }
    const invalidation = { useUiInvalidation: (key, enabled, matches, read, reset, visible, onInvalidated, reconcile) => { if (enabled) invalidations.set(key, { matches, read, reset, reconcile }) } }
    const Component = load('../crm/FindOrCreateCrmClient.tsx', { '../../api/crmApi': api, '../../api/apiClient': apiClient,
        './InvalidationContext': invalidation, '../../api/events/InvalidationContext': invalidation,
        './uiScope': scope, '../../api/events/uiScope': scope }).default
    let done = 0, closed = 0, ui
    await act(async () => { ui = create(React.createElement(Component, { project, client: source, linked: false, done: () => done++, close: () => closed++ })) })
    t.after(() => act(() => ui.unmount()))
    return { ui, calls, invalidations, get done() { return done }, get closed() { return closed },
        refresh: () => act(async () => invalidations.get([...invalidations.keys()].find(k => k.startsWith('crm-client-provision:'))).read()) }
}
test('one phone is prefilled and contacts are copied in a one-shot request', async t => {
    const f = await fixture(t)
    assert.match(text(f.ui), /Салон на Лесной/)
    assert.equal(f.calls[0][3], '79991234567')
    await click(f.ui, 'Создать нового клиента CRM')
    const inputs = f.ui.root.findAllByType('input')
    assert.ok(inputs.some(n => n.props.value === 'Анна')); assert.ok(inputs.some(n => n.props.value === 'Смирнова'))
    await act(async () => f.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    const sent = f.calls.find(c => c[0] === 'create')
    assert.deepEqual(sent[3], { clientId: 'core', firstName: 'Анна', lastName: 'Смирнова', phone: client.phoneList[0], differentPerson: false })
    assert.match(sent[2], /^[0-9a-f-]{36}$/)
})
test('multiple phones require an explicit choice and do not silently select the first', async t => {
    const f = await fixture(t, {}, { ...client, phoneList: ['79991234567', '79997654321'] })
    assert.equal(f.calls.length, 0)
    assert.equal(f.ui.root.findAllByProps({ type: 'tel' }).length, 0)
    await click(f.ui, 'Создать нового клиента CRM')
    assert.equal(f.ui.root.findByProps({ type: 'tel' }).props.value, '')
    assert.equal(button(f.ui, 'Создать и связать').props.disabled, true)
})
test('phone match needs different-person acknowledgement; linking has a separate confirmation', async t => {
    const f = await fixture(t, { searchCrmClients: async () => ({ items: [candidate], hasMore: false }) })
    await click(f.ui, 'Создать нового клиента CRM')
    assert.equal(button(f.ui, 'Создать и связать').props.disabled, true)
    await act(async () => f.ui.root.findByProps({ type: 'checkbox' }).props.onChange({ target: { checked: true } }))
    assert.equal(button(f.ui, 'Создать и связать').props.disabled, false)
    await click(f.ui, 'Выбрать'); assert.equal(f.calls.filter(c => c[0] === 'link').length, 0)
    await click(f.ui, 'Связать существующего'); assert.equal(f.calls.filter(c => c[0] === 'link').length, 1); assert.equal(f.done, 1)
})
test('uncertain server operation survives reopening and blocks another create', async t => {
    const f = await fixture(t, { getLatestClientProvision: async () => ({ id: 'op', status: 'UNKNOWN' }) })
    assert.match(text(f.ui), /Повторное создание заблокировано/)
    assert.equal(button(f.ui, 'Создать нового клиента CRM'), undefined)
    await click(f.ui, 'Закрыть'); assert.equal(f.closed, 1)
})
test('search does not subscribe to its own import invalidation', async t => {
    const f = await fixture(t)
    const search = [...f.invalidations].find(([key]) => key.startsWith('crm-client-search:'))[1]
    assert.equal(search.matches({ type: 'crm.invalidated', workspaceId: 'w' }), false)
    assert.equal(search.reconcile, false)
})
test('linking stays blocked until the server operation status has been read', async t => {
    let release
    const status = new Promise(resolve => { release = resolve })
    const f = await fixture(t, { getLatestClientProvision: () => status, searchCrmClients: async () => ({ items: [candidate], hasMore: false }) })
    assert.equal(button(f.ui, 'Выбрать').props.disabled, true)
    await act(async () => release(null))
    assert.equal(button(f.ui, 'Выбрать').props.disabled, false)
})
test('an immediately confirmed create completes the form without waiting for a pending state', async t => {
    const f = await fixture(t, { provisionCrmClient: async () => ({ id: 'fast', status: 'CONFIRMED' }) })
    await click(f.ui, 'Создать нового клиента CRM')
    await act(async () => f.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    assert.equal(f.done, 1)
})
test('an old rejection cannot discard the idempotency key of a newer uncertain request', async t => {
    const keys = []
    const f = await fixture(t, {
        getLatestClientProvision: async () => ({ id: 'old', status: 'REJECTED', updatedAt: 'old', errorCode: 'CRM_CLIENT_DUPLICATE' }),
        provisionCrmClient: async (_, key) => { keys.push(key); if (keys.length === 1) throw Error('Connection lost'); return { id: 'new', status: 'REQUESTED' } },
    })
    await click(f.ui, 'Создать нового клиента CRM')
    await act(async () => f.ui.root.findByType('form').props.onSubmit({ preventDefault() {} }))
    await click(f.ui, 'Проверить отправку')
    assert.equal(keys.length, 2); assert.equal(keys[0], keys[1])
})
test('a truncated search does not permit creation', async t => {
    const f = await fixture(t, { searchCrmClients: async () => ({ items: [candidate], hasMore: true }) })
    await click(f.ui, 'Создать нового клиента CRM')
    await act(async () => f.ui.root.findByProps({ type: 'checkbox' }).props.onChange({ target: { checked: true } }))
    assert.equal(button(f.ui, 'Создать и связать').props.disabled, true)
})
test('reopening an uncertain operation uses its original phone even if ClientBus contacts changed', async t => {
    const f = await fixture(t, { getLatestClientProvision: async () => ({ id: 'pending', status: 'UNKNOWN', phone: '79997654321', updatedAt: 'snapshot' }) })
    assert.equal(f.ui.root.findByProps({ type: 'search' }).props.value, '79997654321')
})

test('background status refresh keeps the open form and its edited values stable', async t => {
    let release, reads = 0
    const f = await fixture(t, { getLatestClientProvision: () => ++reads === 1 ? Promise.resolve(null) : new Promise(resolve => { release = resolve }) })
    await click(f.ui, 'Создать нового клиента CRM')
    const name = f.ui.root.findAllByType('input').find(n => n.props.value === 'Анна')
    await act(async () => name.props.onChange({ target: { value: 'Исправленное имя' } }))
    let refresh
    await act(async () => { refresh = [...f.invalidations].find(([key]) => key.startsWith('crm-client-provision:'))[1].read() })
    assert.equal(f.ui.root.findAllByType('input').find(n => n.props.value === 'Исправленное имя').props.disabled, false)
    assert.doesNotMatch(text(f.ui), /Проверяем предыдущие операции/)
    await act(async () => { release(null); await refresh })
    assert.ok(f.ui.root.findAllByType('input').some(n => n.props.value === 'Исправленное имя'))
})

test('Russian trunk prefix correction is explicit and leaves ClientBus contacts unchanged', async t => {
    const source = { ...client, phoneList: ['89991234567'] }
    const f = await fixture(t, {}, source)
    await click(f.ui, 'Создать нового клиента CRM')
    assert.equal(f.ui.root.findByProps({ type: 'tel' }).props.value, '89991234567')
    await click(f.ui, 'Использовать +7 вместо 8')
    assert.equal(f.ui.root.findByProps({ type: 'tel' }).props.value, '+79991234567')
    assert.deepEqual(source.phoneList, ['89991234567'])
})

test('an observation-only version conflict refreshes and retries the confirmed link once', async t => {
    const versions = []
    const f = await fixture(t, { searchCrmClients: async () => ({ items: [candidate], hasMore: false }),
        getCrmClient: async () => ({ ...candidate, version: 9, lastObservedAt: 'new observation' }),
        linkCrmClient: async row => { versions.push(row.version); if (versions.length === 1) throw new apiClient.ApiError(409, 'Состояние CRM изменилось.', 'CRM_VERSION_CONFLICT') },
    })
    await click(f.ui, 'Выбрать'); await click(f.ui, 'Связать существующего')
    assert.deepEqual(versions, [7, 9]); assert.equal(f.done, 1)
})

test('changed contact details require a new confirmation rather than an automatic retry', async t => {
    const versions = []
    const fresh = { ...candidate, version: 9, displayName: 'Изменённое имя', normalizedPhone: '79997654321' }
    const f = await fixture(t, { searchCrmClients: async () => ({ items: [candidate], hasMore: false }), getCrmClient: async () => fresh,
        linkCrmClient: async row => { versions.push(row.version); if (versions.length === 1) throw new apiClient.ApiError(409, 'Состояние CRM изменилось.', 'CRM_VERSION_CONFLICT') },
    })
    await click(f.ui, 'Выбрать'); await click(f.ui, 'Связать существующего')
    assert.deepEqual(versions, [7]); assert.equal(f.done, 0)
    assert.match(text(f.ui), /Изменённое имя/); assert.match(text(f.ui), /подтвердите связь ещё раз/)
    await click(f.ui, 'Связать существующего'); assert.deepEqual(versions, [7, 9]); assert.equal(f.done, 1)
})

test('a concurrent reassignment is never overwritten by version conflict recovery', async t => {
    let writes = 0
    const f = await fixture(t, { searchCrmClients: async () => ({ items: [candidate], hasMore: false }),
        getCrmClient: async () => ({ ...candidate, version: 9, clientId: 'other-core' }),
        linkCrmClient: async () => { writes++; throw new apiClient.ApiError(409, 'Conflict', 'CRM_VERSION_CONFLICT') },
    })
    await click(f.ui, 'Выбрать'); await click(f.ui, 'Связать существующего')
    assert.equal(writes, 1); assert.equal(f.done, 0)
    assert.equal(button(f.ui, 'Связать существующего'), undefined)
    assert.match(text(f.ui), /уже связана с другим клиентом/)
})

test('version recovery is bounded and also applies to explicit UNKNOWN resolution', async t => {
    const versions = []
    const f = await fixture(t, { getLatestClientProvision: async () => ({ id: 'op', status: 'UNKNOWN' }),
        searchCrmClients: async () => ({ items: [candidate], hasMore: false }), getCrmClient: async () => ({ ...candidate, version: 9 }),
        resolveClientProvision: async (_, row) => { versions.push(row.version); throw new apiClient.ApiError(409, 'Conflict', 'CRM_VERSION_CONFLICT') },
    })
    await click(f.ui, 'Выбрать'); await click(f.ui, 'Связать существующего')
    assert.deepEqual(versions, [7, 9]); assert.equal(f.done, 0)
})
