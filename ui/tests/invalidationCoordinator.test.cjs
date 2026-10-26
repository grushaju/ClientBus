const { test } = require('node:test')
const assert = require('node:assert/strict')
const load = require('./loadInboxModule.cjs')
const { InvalidationCoordinator } = load('../../api/events/invalidationCoordinator.ts')

test('optional invalidation fences run synchronously and remain separate from revoke resets', async () => {
    const manager = new InvalidationCoordinator(() => false)
    const observed = [], event = { type: 'notifications.invalidated' }
    const subscription = manager.subscribe(value => value.type === event.type, async () => {},
        () => observed.push('reset'), () => true, value => observed.push(value ?? 'local'))
    manager.subscribe(() => true, async () => {})
    manager.invalidate({ type: 'unrelated' }); assert.deepEqual(observed, [])
    manager.invalidate(event); assert.deepEqual(observed, [event])
    subscription.invalidate(); assert.deepEqual(observed, [event, 'local'])
    manager.revoke(event); assert.deepEqual(observed, [event, 'local', 'reset'])
    subscription(); manager.invalidate(event)
    assert.deepEqual(observed, [event, 'local', 'reset'])
    manager.close()
})
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

test('invalidations during REST preserve response and request one follow-up without parallel calls', async () => {
    const manager = new InvalidationCoordinator(() => true, 1, 1)
    let release, calls = 0, active = 0, max = 0
    manager.subscribe(() => true, async () => {
        calls++; active++; max = Math.max(max, active)
        if (calls === 1) await new Promise(resolve => { release = resolve })
        active--
    })
    try {
        const first = manager.reconcile(); await pause(1)
        for (let i = 0; i < 100; i++) manager.invalidate()
        assert.equal(calls, 1); release(); await first; await pause(20)
        assert.equal(calls, 2); assert.equal(max, 1)
    } finally { manager.close() }
})

test('failed refresh stays dirty and retries; unsubscription aborts and prevents late work', { timeout: 3000 }, async () => {
    const manager = new InvalidationCoordinator(() => true, 1, 1)
    let calls = 0, signal
    const unsubscribe = manager.subscribe(() => true, async s => { signal = s; calls++; if (calls === 1) throw new Error('offline') })
    await assert.rejects(manager.reconcile())
    await pause(1100); assert.equal(calls, 2)
    unsubscribe(); manager.invalidate(); await pause(20)
    assert.equal(calls, 2); manager.close()
})

test('attempt abort cancels an active REST operation and reconnect starts a new read', async () => {
    const manager = new InvalidationCoordinator(() => true, 1, 1)
    const attempt = new AbortController()
    let calls = 0, requestSignal
    manager.subscribe(() => true, async signal => { requestSignal = signal; if (++calls === 1) await new Promise(() => {}) })
    const first = manager.reconcile(attempt.signal)
    await pause(1); const cancelledSignal = requestSignal; attempt.abort()
    await assert.rejects(first, error => error.name === 'AbortError')
    assert.equal(cancelledSignal.aborted, true)
    await manager.reconcile(new AbortController().signal)
    assert.equal(calls, 2); manager.close()
})

test('deadline releases a noncooperating refresh and preserves retry eligibility', async () => {
    const manager = new InvalidationCoordinator(() => true, 1, 1, 10)
    let calls = 0, firstSignal
    manager.subscribe(() => true, async signal => { if (++calls === 1) { firstSignal = signal; await new Promise(() => {}) } })
    await assert.rejects(manager.reconcile(), error => error.name === 'TimeoutError')
    assert.equal(firstSignal.aborted, true)
    await manager.reconcile(); assert.equal(calls, 2); manager.close()
})

test('ready waits for a read started after earlier work and propagates its failure', async () => {
    const manager = new InvalidationCoordinator(() => true, 1, 1)
    let calls = 0, releaseFirst, rejectSecond, ready = false
    manager.subscribe(() => true, async () => {
        calls++
        if (calls === 1) await new Promise(resolve => { releaseFirst = resolve })
        if (calls === 2) await new Promise((_, reject) => { rejectSecond = reject })
    })
    manager.invalidate(); await pause(10)
    const reconciliation = manager.reconcile().then(() => { ready = true })
    const rejected = assert.rejects(reconciliation, /offline/)
    releaseFirst(); await pause(10)
    assert.equal(calls, 2); assert.equal(ready, false)
    rejectSecond(new Error('offline')); await rejected
    manager.close()
})

test('hidden resources retain dirty state and resume via reconciliation; cleanup supports StrictMode setup', async () => {
    let visible = false, calls = 0
    const manager = new InvalidationCoordinator(() => visible, 1, 1)
    manager.subscribe(() => true, async () => { calls++ })
    manager.invalidate(); await pause(10); assert.equal(calls, 0)
    visible = true; await manager.reconcile(); assert.equal(calls, 1)
    manager.close(); manager.subscribe(() => true, async () => { calls++ })
    await manager.reconcile(); assert.equal(calls, 2); manager.close()
})

test('continuous events allow responses and respect request frequency without parallel reads', async t => {
    let now = 100000
    t.mock.method(Date, 'now', () => now)
    t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
    const manager = new InvalidationCoordinator(() => true, 2, 30)
    let active=0,max=0,completed=0,requests=0
    const starts = []
    manager.subscribe(() => true, async () => {
        starts.push(Date.now()); requests++; active++; max=Math.max(max,active)
        await pause(10); completed++; active--
    })
    const events=setInterval(()=>manager.invalidate(),2)
    try {
        const initial = manager.reconcile()
        // Advance timers and promise continuations together; host CPU scheduling is not the clock.
        for (let elapsed = 0; elapsed < 240; elapsed++) {
            for (let step = 0; step < 20; step++) await Promise.resolve()
            now++; t.mock.timers.tick(1)
        }
        await initial
        assert.equal(max,1);assert.ok(completed>=4);assert.ok(requests<=10)
        for (let index = 1; index < starts.length; index++) assert.ok(starts[index] - starts[index - 1] >= 30)
    } finally { clearInterval(events);manager.close();t.mock.timers.reset() }
})

test('hidden resources keep dirty and receive immediate revoke before being resumed', async () => {
    const manager=new InvalidationCoordinator(()=>true,1,1)
    let visible=false,reads=0,resets=0
    manager.subscribe(()=>true,async()=>{reads++},()=>{resets++},()=>visible)
    try {
        manager.invalidate();manager.revoke();await pause(10)
        assert.equal(reads,0);assert.equal(resets,1)
        visible=true;manager.wake();await pause(10)
        assert.equal(reads,1)
    } finally {manager.close()}
})

test('cancelled shared metadata can start a new read before the obsolete adapter completes', async () => {
    const {singleFlightRead}=load('../../api/events/singleFlightRead.ts')
    let calls=0,oldSignal,release
    const reader=singleFlightRead(async signal=>{
        if(++calls===1){oldSignal=signal;return new Promise(resolve=>{release=resolve})}
        return 'new-scope'
    })
    const first=reader.read().catch(error=>error)
    await pause(1);reader.cancel()
    assert.equal(oldSignal.aborted,true)
    assert.equal(await reader.read(),'new-scope')
    release('obsolete');assert.equal((await first).name,'AbortError')
})
