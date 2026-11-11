const { test } = require('node:test')
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const load = require('./loadInboxModule.cjs')
const scope = { organizationId: 'organization', employeeId: 'employee', projectId: 'project' }
function fixture(t) {
    const values = new Map(), queues = new Map()
    const originals = Object.fromEntries(['localStorage', 'navigator', 'window', 'crypto', 'CustomEvent'].map(key => [key, Object.getOwnPropertyDescriptor(global, key)]))
    const storage = { fail: false, getItem: key => values.get(key) ?? null, setItem(key, value) { if (this.fail) throw Error('Storage denied'); values.set(key, value) }, removeItem: key => values.delete(key) }
    const globals = { localStorage: storage, navigator: { locks: { async request(key, options, callback) {
        const action = typeof options === 'function' ? options : callback, signal = typeof options === 'function' ? undefined : options.signal
        const previous = queues.get(key) ?? Promise.resolve()
        let release; const next = new Promise(resolve => { release = resolve })
        queues.set(key, previous.then(() => next))
        let abort
        const cancelled = new Promise((_, reject) => { abort = () => reject(signal.reason); signal?.addEventListener('abort', abort, { once: true }); if (signal?.aborted) abort() })
        try { await Promise.race([previous, cancelled]); signal?.throwIfAborted(); return await action() }
        finally { signal?.removeEventListener('abort', abort); release() }
    } } }, window: { dispatchEvent() {} }, crypto: { randomUUID }, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail } } }
    for (const [key, value] of Object.entries(globals)) Object.defineProperty(global, key, { configurable: true, value })
    t.after(() => { for (const [key, descriptor] of Object.entries(originals)) { if (descriptor) Object.defineProperty(global, key, descriptor); else delete global[key] } })
    return { values, storage, api: load('../crm/crmIntent.ts', { '../../api/apiClient': { getApiErrorCode: error => error.code } }) }
}
const operation = (key, status = 'REQUESTED', owner = scope) => ({ id: randomUUID(), projectId: owner.projectId, initiatorEmployeeId: owner.employeeId, idempotencyKey: key, type: 'CREATE', status })
test('CRM intent is durably stored before POST and preserves only the key/type/reference', async t => {
    const { api, values } = fixture(t)
    const result = await api.sendIntent(scope, 'CREATE', async key => { assert.equal(api.readIntent(scope).key, key); return operation(key) })
    assert.equal(api.readIntent(scope).operationId, result.id)
    assert.deepEqual(Object.keys(JSON.parse([...values.values()][0])).sort(), ['key', 'operationId', 'type'])
})
test('a lost response survives reload and prevents a new automatic POST', async t => {
    const { api } = fixture(t); let calls = 0
    await assert.rejects(api.sendIntent(scope, 'CREATE', async () => { calls++; throw Error('Lost response') }))
    const first = api.readIntent(scope)
    const reloaded = load('../crm/crmIntent.ts', { '../../api/apiClient': { getApiErrorCode: error => error.code } })
    assert.equal(reloaded.readIntent(scope).key, first.key)
    await assert.rejects(reloaded.sendIntent(scope, 'CREATE', async () => { calls++ }), /Повторный запрос не отправлен/)
    assert.equal(calls, 1)
})
test('UNKNOWN remains pending and only a proved terminal result clears the pointer', async t => {
    const { api } = fixture(t)
    const result = await api.sendIntent(scope, 'CREATE', async key => operation(key, 'UNKNOWN'))
    assert.equal(api.readIntent(scope).key, result.idempotencyKey)
    await api.resolveIntent(scope, { ...result, status: 'CONFIRMED' })
    assert.equal(api.readIntent(scope), null)
})
test('concurrent tabs share one durable intent and only one starts POST', async t => {
    const { api } = fixture(t); let calls = 0, release
    const gate = new Promise(resolve => { release = resolve })
    const send = key => { calls++; return gate.then(() => operation(key)) }
    const first = api.sendIntent(scope, 'CREATE', send)
    const second = api.sendIntent(scope, 'CREATE', send).catch(error => error)
    await new Promise(resolve => setImmediate(resolve)); assert.equal(calls, 1); release()
    await first; assert.match((await second).message, /Повторный запрос не отправлен/)
})
test('storage failure blocks POST and does not pretend that an operation was sent', async t => {
    const { api, storage } = fixture(t); storage.fail = true; let calls = 0
    await assert.rejects(api.sendIntent(scope, 'CREATE', async () => { calls++ }), /Запрос не отправлен/)
    assert.equal(calls, 0)
})
test('rejection before admission may clear the key while an idempotency conflict keeps it', async t => {
    const { api } = fixture(t)
    await assert.rejects(api.sendIntent(scope, 'CREATE', async () => { throw { code: 'CRM_CAPABILITY_UNVERIFIED' } }))
    assert.equal(api.readIntent(scope), null)
    await assert.rejects(api.sendIntent(scope, 'CREATE', async () => { throw { code: 'CRM_IDEMPOTENCY_CONFLICT' } }))
    assert.ok(api.readIntent(scope))
})
test('another actor or project cannot resolve the current actors intent', async t => {
    const { api } = fixture(t)
    const result = await api.sendIntent(scope, 'CREATE', async key => operation(key))
    const another = { ...scope, employeeId: 'other' }
    assert.equal(api.readIntent(another), null)
    await assert.rejects(api.resolveIntent(scope, { ...result, initiatorEmployeeId: 'other' }), /Контекст/)
    assert.equal(api.readIntent(scope).key, result.idempotencyKey)
})
test('an already cancelled form neither stores an intent nor starts POST', async t => {
    const { api } = fixture(t), controller = new AbortController(); let calls = 0
    controller.abort()
    await assert.rejects(api.sendIntent(scope, 'CREATE', async key => { calls++; return operation(key) }, controller.signal), { name: 'AbortError' })
    assert.equal(calls, 0); assert.equal(api.readIntent(scope), null)
})
test('cancelling a queued Web Lock promptly leaves the next command usable', { timeout: 2000 }, async t => {
    const { api } = fixture(t), controller = new AbortController(); let calls = 0, release
    const hold = navigator.locks.request(api.intentKey(scope), () => new Promise(resolve => { release = resolve }))
    await new Promise(resolve => setImmediate(resolve))
    const waiting = api.sendIntent(scope, 'CREATE', async key => { calls++; return operation(key) }, controller.signal)
    const rejected = assert.rejects(waiting, { name: 'AbortError' })
    controller.abort(); await rejected
    assert.equal(calls, 0); assert.equal(api.readIntent(scope), null)
    release(); await hold
    await api.sendIntent(scope, 'CREATE', async key => { calls++; return operation(key, 'CONFIRMED') })
    assert.equal(calls, 1); assert.equal(api.readIntent(scope), null)
})
test('cancellation just after storage clears the known unsent key under the same lock', async t => {
    const { api } = fixture(t), controller = new AbortController(); let calls = 0
    window.dispatchEvent = () => controller.abort()
    await assert.rejects(api.sendIntent(scope, 'CREATE', async key => { calls++; return operation(key) }, controller.signal), { name: 'AbortError' })
    assert.equal(calls, 0); assert.equal(api.readIntent(scope), null)
    window.dispatchEvent = () => {}
    await api.sendIntent(scope, 'CREATE', async key => { calls++; return operation(key, 'CONFIRMED') })
    assert.equal(calls, 1)
})
test('cancellation after starting POST preserves the key and blocks another POST', async t => {
    const { api } = fixture(t), controller = new AbortController(); let calls = 0
    const pending = api.sendIntent(scope, 'CREATE', () => {
        calls++
        return new Promise((_, reject) => controller.signal.addEventListener('abort', () => reject(controller.signal.reason), { once: true }))
    }, controller.signal)
    await new Promise(resolve => setImmediate(resolve))
    const rejected = assert.rejects(pending, { name: 'AbortError' }); controller.abort(); await rejected
    const key = api.readIntent(scope).key
    await assert.rejects(api.sendIntent(scope, 'CREATE', async () => { calls++ }), /Повторный запрос не отправлен/)
    assert.equal(calls, 1); assert.equal(api.readIntent(scope).key, key)
})
