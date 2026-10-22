const {test}=require('node:test')
const assert=require('node:assert/strict')
const load=require('./loadInboxModule.cjs')
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const deferred=()=>{let resolve;const promise=new Promise(value=>{resolve=value});return {promise,resolve}}
const budgetModule=load('../../api/events/requestBudget.ts')
const {RequestBudget,bindRequestBudget}=budgetModule
const {withCancellation}=load('../../api/events/requestScope.ts',{'./requestBudget':budgetModule})
const {apiFetch}=load('../../api/apiClient.ts',{'./events/requestBudget':budgetModule,'../auth/authStorage':{getAccessToken:()=>null}})
const {recoveryPolicy}=load('../../api/events/recoveryPolicy.ts')
const {UiRecovery}=load('../../api/events/uiRecovery.ts')
const {InvalidationCoordinator}=load('../../api/events/invalidationCoordinator.ts')
const flush=async()=>{for(let i=0;i<15;i++) await Promise.resolve()}

test('the budget covers nested automatic GETs, preserves FIFO and lets commands bypass the queue',async t=>{
    const budget=new RequestBudget(2,32,0),owner=new AbortController(),gate=deferred()
    bindRequestBudget(owner.signal,budget)
    let active=0,peak=0;const order=[]
    t.mock.method(global,'fetch',async(input,init)=>{
        if(init.method==='POST') return new Response('{}')
        order.push(input);active++;peak=Math.max(peak,active)
        if(order.length<=2) await gate.promise
        await pause(2);active--
        return new Response('{"ok":true}')
    })
    const pending=withCancellation(signal=>Promise.all(Array.from({length:8},(_,id)=>apiFetch('/get/'+id,{signal}).then(value=>value.json()))),[owner.signal])
    try {
        await flush()
        assert.equal(order.length,2)
        assert.equal((await apiFetch('/command',{method:'POST',signal:owner.signal})).status,200)
        gate.resolve();await pending
        assert.equal(peak,2);assert.deepEqual(order,Array.from({length:8},(_,id)=>'/get/'+id))
        assert.equal(budget.snapshot().active,0);assert.equal(budget.snapshot().started,8)
    } finally {gate.resolve();budget.close();await pending.catch(()=>{})}
})

test('a response body retains its permit; queued cancellation and close release all work',async()=>{
    const budget=new RequestBudget(1,4,0),owner=new AbortController();let body
    const first=await budget.fetch('/one',{signal:owner.signal},async()=>new Response(new ReadableStream({start:controller=>{body=controller}})))
    const next=new AbortController()
    const pending=budget.fetch('/two',{signal:next.signal},async()=>new Response('{}')).catch(error=>error)
    await flush();assert.equal(budget.snapshot().active,1);assert.equal(budget.snapshot().queued,1)
    next.abort();assert.equal((await pending).name,'AbortError');assert.equal(budget.snapshot().queued,0)
    const reading=first.text();body.enqueue(new TextEncoder().encode('complete'));body.close()
    assert.equal(await reading,'complete');assert.equal(budget.snapshot().active,0)
    const stalled=await budget.fetch('/stalled',{signal:owner.signal},async()=>new Response(new ReadableStream()))
    const failed=stalled.text().catch(error=>error)
    budget.close();assert.equal((await failed).name,'AbortError');assert.equal(budget.snapshot().active,0)
})

test('the automatic queue is bounded and an aborted waiter never starts its fetch',async()=>{
    const budget=new RequestBudget(1,1,0),first=new AbortController(),queued=new AbortController()
    const started=[]
    const fetcher=async input=>{started.push(input);return new Response(new ReadableStream())}
    const response=await budget.fetch('/active',{signal:first.signal},fetcher)
    const waiting=budget.fetch('/queued',{signal:queued.signal},fetcher).catch(error=>error)
    await assert.rejects(budget.fetch('/rejected',{},fetcher),error=>error.status===429)
    queued.abort();assert.equal((await waiting).name,'AbortError')
    first.abort();await response.text().catch(()=>{})
    assert.deepEqual(started,['/active']);assert.equal(budget.snapshot().rejected,1);budget.close()
})

test('automatic GET start frequency is bounded independently of response speed',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const budget=new RequestBudget(4,10,100),starts=[]
    const calls=Array.from({length:4},(_,id)=>budget.fetch('/'+id,{},async()=>{starts.push(Date.now());return new Response(null,{status:204})}))
    await flush();assert.equal(starts.length,1)
    for(let i=0;i<3;i++){t.mock.timers.tick(100);await flush()}
    await Promise.all(calls);assert.deepEqual(starts.map(value=>value-starts[0]),[0,100,200,300]);budget.close()
})

test('fallback cannot starve under repeated reconnect states and healthy mode uses rare control reads',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    let rounds=0
    const manager={reconcileAvailable:async()=>{rounds++;return true},invalidate(){}}
    const recovery=new UiRecovery(manager,recoveryPolicy({fallbackMs:100,controlMs:1000,jitter:0}))
    try {
        recovery.state('backoff')
        for(let i=0;i<5;i++){t.mock.timers.tick(20);recovery.state(i%2?'connecting':'backoff');await flush()}
        assert.equal(rounds,1)
        recovery.state('open');t.mock.timers.tick(999);await flush();assert.equal(rounds,1)
        t.mock.timers.tick(1);await flush();assert.equal(rounds,2)
    } finally {recovery.stop()}
})

test('hidden and offline time creates no polling backlog; returning invalidates once',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    let rounds=0,invalidations=0
    const recovery=new UiRecovery({reconcileAvailable:async()=>{rounds++;return true},invalidate(){invalidations++}},recoveryPolicy({fallbackMs:100,controlMs:1000,jitter:0}))
    try {
        recovery.state('disabled');recovery.visibility(false)
        t.mock.timers.tick(10000);await flush();assert.equal(rounds,0)
        recovery.visibility(true);assert.equal(invalidations,1)
        recovery.connectivity(false);t.mock.timers.tick(10000);await flush();assert.equal(rounds,0)
        recovery.connectivity(true);assert.equal(invalidations,2)
        t.mock.timers.tick(100);await flush();assert.equal(rounds,1)
        recovery.state('unauthorized');t.mock.timers.tick(10000);await flush();assert.equal(rounds,1)
    } finally {recovery.stop()}
})

test('REST fallback failure backs off to its cap and successful reconciliation restores live mode',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    let rounds=0,ok=false
    const recovery=new UiRecovery({reconcileAvailable:async()=>{rounds++;return ok},invalidate(){}},recoveryPolicy({fallbackMs:100,fallbackMaxMs:400,controlMs:1000,jitter:0}))
    try {
        recovery.state('degraded')
        for(const delay of [100,200,400,400]){t.mock.timers.tick(delay);await flush()}
        assert.equal(rounds,4);assert.equal(recovery.mode(),'fallback')
        ok=true;t.mock.timers.tick(400);await flush();assert.equal(recovery.mode(),'live')
        t.mock.timers.tick(999);await flush();assert.equal(rounds,5)
    } finally {recovery.stop()}
})

test('one failed resource does not reject tolerant ready or discard another successful resource',async()=>{
    const manager=new InvalidationCoordinator(()=>true,1,1,100,{retryMs:20,retryMaxMs:80})
    let failed=0,succeeded=0
    manager.subscribe(()=>true,async()=>{failed++;throw new Error('optional service down')})
    manager.subscribe(()=>true,async()=>{succeeded++})
    try {
        assert.equal(await manager.reconcileAvailable(),false)
        assert.equal(succeeded,1);assert.equal(manager.snapshot().dirty,1)
        await manager.reconcileAvailable(undefined,true)
        assert.equal(failed,1,'Periodic reconciliation must respect resource error backoff')
        assert.equal(succeeded,2)
        await pause(25);assert.equal(failed,2)
    } finally {manager.close()}
})

test('resource retry uses exponential backoff and invalidation does not reset it',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const manager=new InvalidationCoordinator(()=>true,1,1,1000,{retryMs:100,retryMaxMs:400})
    let calls=0
    manager.subscribe(()=>true,async()=>{calls++;throw new Error('down')})
    try {
        await manager.reconcileAvailable()
        for(const delay of [100,200,400,400]){
            manager.invalidate();t.mock.timers.tick(delay-1);await flush()
            const before=calls;t.mock.timers.tick(1);await flush();assert.equal(calls,before+1)
        }
        assert.equal(calls,5)
    } finally {manager.close()}
})

test('policy rejects invalid limits and preserves an explicit rollback flag',()=>{
    for(const changes of [{fallbackMs:0},{maxConcurrent:33},{jitter:2},{fallbackMaxMs:1},{controlMs:1},{retryMaxMs:1},{maxQueued:Infinity}])
        assert.throws(()=>recoveryPolicy(changes))
    assert.equal(recoveryPolicy({enabled:false}).enabled,false)
})

test('pausing the request budget prevents queued GET starts until connectivity returns',async()=>{
    const budget=new RequestBudget(2,4,0),started=[]
    budget.pause()
    const pending=budget.fetch('/queued',{},async input=>{started.push(input);return new Response(null,{status:204})})
    await flush();assert.equal(started.length,0)
    budget.resume();await pending;assert.deepEqual(started,['/queued']);budget.close()
})

test('a tolerant ready attempt still cancels a joined reconciliation promptly',async()=>{
    const manager=new InvalidationCoordinator(()=>true,1,1,10000),attempt=new AbortController()
    let signal
    manager.subscribe(()=>true,async value=>{signal=value;await new Promise(()=>{})})
    const original=manager.reconcile().catch(error=>error)
    await flush()
    const ready=manager.reconcileAvailable(attempt.signal)
    attempt.abort()
    await assert.rejects(ready,error=>error.name==='AbortError')
    assert.equal(signal.aborted,true);await original;manager.close()
})

test('a continuous invalidation load respects the HTTP budget and drains all dirty resources',async t=>{
    const budget=new RequestBudget(4,64,10)
    const {InvalidationCoordinator:Manager}=load('../../api/events/invalidationCoordinator.ts',{'./requestBudget':budgetModule})
    const manager=new Manager(()=>true,2,30,30000,{budget})
    let active=0,peak=0,applied=0
    t.mock.method(global,'fetch',async()=>{
        active++;peak=Math.max(peak,active);await pause(35);active--
        return new Response('{}')
    })
    for(let i=0;i<24;i++) manager.subscribe(()=>true,async signal=>{
        await Promise.all([apiFetch('/left/'+i,{signal}).then(value=>value.json()),apiFetch('/right/'+i,{signal}).then(value=>value.json())]);applied++
    })
    const started=Date.now(),events=setInterval(()=>{for(let i=0;i<20;i++)manager.invalidate()},2)
    try {
        const ready=manager.reconcileAvailable()
        await pause(120);clearInterval(events);assert.equal(await ready,true)
        for(let i=0;i<100 && manager.snapshot().dirty;i++) await pause(20)
        assert.equal(manager.snapshot().dirty,0);assert.ok(applied>=24);assert.ok(peak<=4)
        assert.equal(budget.snapshot().rejected,0);assert.ok(budget.snapshot().peakQueued<=64)
        t.diagnostic(JSON.stringify({profile:{resources:24,getsPerResource:2,spacingMs:10,eventBurst:20,eventIntervalMs:2},
            elapsedMs:Date.now()-started,http:budget.snapshot(),resources:manager.snapshot()}))
    } finally {clearInterval(events);manager.close();budget.close()}
})

test('REST Retry-After is retained and ordinary invalidation cannot bypass it',async t=>{
    const {retryAfterDelay}=load('../../api/events/retryAfter.ts')
    assert.equal(retryAfterDelay('2',0),2000)
    assert.equal(retryAfterDelay(new Date(5000).toUTCString(),0),5000)
    assert.equal(retryAfterDelay('99999',0),60000)
    assert.equal(retryAfterDelay('invalid',0),0)
    t.mock.method(global,'fetch',async()=>new Response('{}',{status:429,headers:{'Retry-After':'5'}}))
    await assert.rejects(apiFetch('/rate-limited'),error=>error.status===429 && error.retryAfterMs===5000)
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const manager=new InvalidationCoordinator(()=>true,1,1,10000)
    let calls=0
    manager.subscribe(()=>true,async()=>{calls++;throw Object.assign(new Error('limited'),{retryAfterMs:5000})})
    try {
        await manager.reconcileAvailable();manager.invalidate()
        t.mock.timers.tick(4999);await flush();assert.equal(calls,1)
        t.mock.timers.tick(1);await flush();assert.equal(calls,2)
    } finally {manager.close()}
})

test('a cancelled GET 401 for the current credential cannot expire a fresh access reconciliation',async t=>{
    const previous=global.window;global.window=new EventTarget();t.after(()=>{global.window=previous})
    const api=load('../../api/apiClient.ts',{'../auth/authStorage':{getAccessToken:()=> 'current'}})
    let expired=0;window.addEventListener('clientbus:session-expired',()=>expired++)
    const signal=new AbortController(),old=deferred()
    t.mock.method(global,'fetch',()=>old.promise)
    const pending=api.apiFetch('/obsolete',{signal:signal.signal}).catch(error=>error)
    signal.abort();old.resolve(new Response('{}',{status:401}));await pending
    assert.equal(expired,0)
})

test('closing an old coordinator suppresses late health callbacks and a StrictMode setup can subscribe again',async()=>{
    const health=[]
    const manager=new InvalidationCoordinator(()=>true,1,1,10000,{onHealth:value=>health.push(value)})
    manager.subscribe(()=>true,async()=>new Promise(()=>{}))
    const pending=manager.reconcileAvailable();await flush();manager.close();await pending
    assert.deepEqual(health,[])
    manager.subscribe(()=>true,async()=>{})
    assert.equal(await manager.reconcileAvailable(),true);assert.deepEqual(health,[true]);manager.close()
})
