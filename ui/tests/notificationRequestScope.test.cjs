const { test } = require('node:test')
const assert = require('node:assert/strict')
const load = require('./loadInboxModule.cjs')

const budgetModule = load('../../api/events/requestBudget.ts')
const { RequestBudget, bindRequestBudget } = budgetModule
const { withCancellation } = load('../../api/events/requestScope.ts', { './requestBudget': budgetModule })
const { apiFetch } = load('../../api/apiClient.ts', {
    './events/requestBudget': budgetModule,
    '../auth/authStorage': { getAccessToken: () => null },
})

const flush = async () => { for (let index = 0; index < 80; index++) await Promise.resolve() }
const deferred = () => {
    let resolve
    const promise = new Promise(value => { resolve = value })
    return { promise, resolve }
}
const readJson = (path, signal) => apiFetch(path, { signal }).then(response => response.json())

function responseBody() {
    let controller
    const state = { cancelled: 0, pulled: 0 }
    const stream = new ReadableStream({
        start(value) { controller = value },
        pull() { state.pulled++ },
        cancel() { state.cancelled++ },
    })
    return {
        state,
        response: new Response(stream),
        complete(value) {
            controller.enqueue(new TextEncoder().encode(JSON.stringify(value)))
            controller.close()
        },
    }
}

function requestOwner(budget) {
    const owner = new AbortController()
    bindRequestBudget(owner.signal, budget)
    return owner
}

test('an early parallel GET failure cancels its hanging sibling body and releases the HTTP permit', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const budget = new RequestBudget(2, 8, 0), owner = requestOwner(budget)
    const failure = deferred(), body = responseBody()
    const started = []
    t.mock.method(global, 'fetch', async path => {
        started.push(path)
        return path === '/failing' ? failure.promise : body.response
    })
    const pending = withCancellation(signal => Promise.all([
        readJson('/failing', signal), readJson('/hanging-body', signal),
    ]), [owner.signal])
    const outcome = pending.catch(error => error)
    try {
        await flush()
        assert.deepEqual(started, ['/failing', '/hanging-body'])
        assert.ok(body.state.pulled > 0, 'The sibling has started reading an unfinished response body')
        assert.equal(budget.snapshot().active, 2)

        failure.resolve(new Response('{"message":"Unavailable"}', { status: 503 }))
        assert.equal((await outcome).status, 503)
        await flush()

        assert.equal(body.state.cancelled, 1, 'A settled failed operation must cancel the unfinished sibling body')
        assert.equal(budget.snapshot().active, 0, 'The sibling must not retain an HTTP permit after its scope failed')
        assert.equal(budget.snapshot().queued, 0)
    } finally {
        owner.abort(); budget.close()
        failure.resolve(new Response(null, { status: 503 }))
        await outcome
        await flush()
    }
})

test('repeated failed parallel reads cannot exhaust the budget and block the next healthy resource', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const budget = new RequestBudget(2, 8, 0)
    const owners = Array.from({ length: 3 }, () => requestOwner(budget))
    const bodies = [responseBody(), responseBody()]
    const started = [], pending = []
    t.mock.method(global, 'fetch', async path => {
        started.push(path)
        if (path.startsWith('/failed/')) return new Response('{"message":"Unavailable"}', { status: 503 })
        if (path.startsWith('/hanging/')) return bodies[Number(path.slice('/hanging/'.length))].response
        return new Response('{"current":true}')
    })
    try {
        for (let index = 0; index < 2; index++) {
            const operation = withCancellation(signal => Promise.all([
                readJson('/failed/' + index, signal), readJson('/hanging/' + index, signal),
            ]), [owners[index].signal])
            pending.push(operation)
            await assert.rejects(operation, error => error.status === 503)
            await flush()
        }

        // Expiry of the original operation deadline must not leave orphaned bodies holding all permits.
        t.mock.timers.tick(30_001)
        await flush()
        const healthy = withCancellation(signal => readJson('/healthy', signal), [owners[2].signal])
        pending.push(healthy)
        const observed = healthy.catch(error => error)
        await flush()

        assert.ok(started.includes('/healthy'), 'A healthy resource must start after the previous resource scopes failed')
        assert.deepEqual(await observed, { current: true })
        assert.equal(budget.snapshot().active, 0)
        assert.equal(budget.snapshot().queued, 0)
    } finally {
        owners.forEach(owner => owner.abort()); budget.close()
        await Promise.allSettled(pending)
        await flush()
    }
})

test('successful parallel reads retain their bodies until completion and then release all permits', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const budget = new RequestBudget(2, 8, 0), owner = requestOwner(budget)
    const bodies = [responseBody(), responseBody()]
    t.mock.method(global, 'fetch', async path => bodies[Number(path.slice(1))].response)
    let settled = false
    const pending = withCancellation(signal => Promise.all([
        readJson('/0', signal), readJson('/1', signal),
    ]), [owner.signal]).finally(() => { settled = true })
    const observed = pending.catch(error => error)
    try {
        await flush()
        assert.equal(budget.snapshot().active, 2)
        bodies[0].complete({ id: 'first' })
        await flush()
        assert.equal(settled, false, 'The second response body is still being consumed')
        assert.equal(budget.snapshot().active, 1)
        assert.deepEqual(bodies.map(body => body.state.cancelled), [0, 0])

        bodies[1].complete({ id: 'second' })
        assert.deepEqual(await observed, [{ id: 'first' }, { id: 'second' }])
        assert.equal(budget.snapshot().active, 0)
        assert.equal(budget.snapshot().queued, 0)
        assert.deepEqual(bodies.map(body => body.state.cancelled), [0, 0])
    } finally {
        owner.abort(); budget.close()
        await observed
        await flush()
    }
})

test('a successful parallel race preserves its parsed value and cancels the unused response body', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const budget = new RequestBudget(2, 8, 0), owner = requestOwner(budget)
    const winner = deferred(), body = responseBody()
    t.mock.method(global, 'fetch', async path => path === '/winner' ? winner.promise : body.response)
    const pending = withCancellation(signal => Promise.race([
        readJson('/winner', signal), readJson('/unused-body', signal),
    ]), [owner.signal])
    const observed = pending.catch(error => error)
    try {
        await flush()
        assert.equal(budget.snapshot().active, 2)
        winner.resolve(new Response('{"source":"winner"}'))
        assert.deepEqual(await observed, { source: 'winner' })
        await flush()

        assert.equal(body.state.cancelled, 1, 'A completed read must not leave its unused sibling running')
        assert.equal(budget.snapshot().active, 0)
        assert.equal(budget.snapshot().queued, 0)
    } finally {
        owner.abort(); budget.close()
        winner.resolve(new Response(null, { status: 204 }))
        await observed
        await flush()
    }
})

test('parent cancellation frees an active response body and removes its queued sibling', async t => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
    const budget = new RequestBudget(1, 8, 0), owner = requestOwner(budget)
    const body = responseBody(), started = []
    t.mock.method(global, 'fetch', async path => { started.push(path); return body.response })
    const pending = withCancellation(signal => Promise.all([
        readJson('/active-body', signal), readJson('/queued', signal),
    ]), [owner.signal])
    const outcome = pending.catch(error => error)
    try {
        await flush()
        assert.equal(budget.snapshot().active, 1)
        assert.equal(budget.snapshot().queued, 1)
        owner.abort()
        assert.equal((await outcome).name, 'AbortError')
        await flush()

        assert.deepEqual(started, ['/active-body'])
        assert.equal(body.state.cancelled, 1)
        assert.equal(budget.snapshot().active, 0)
        assert.equal(budget.snapshot().queued, 0)
    } finally {
        owner.abort(); budget.close()
        await outcome
        await flush()
    }
})
