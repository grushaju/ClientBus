const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const cache = new Map()
function load(file) {
    file = path.resolve(file)
    if (cache.has(file)) return cache.get(file)
    const output = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText
    const module = { exports: {} }; cache.set(file, module.exports)
    new Function('require', 'module', 'exports', output)(name => load(path.resolve(path.dirname(file), name + '.ts')), module, module.exports)
    return module.exports
}
const { SseParser } = load(path.join(__dirname, '../src/api/events/sseParser.ts'))
const { startUiEventStream } = load(path.join(__dirname, '../src/api/events/uiEventStream.ts'))

test('disabled backend endpoint does not cause an endless retry loop', async () => {
    let calls = 0; const states = []
    const handle = startUiEventStream(options({ fetcher: async () => { calls++; return new Response('', { status: 404 }) },
        onState: value => states.push(value) }))
    await handle.done; assert.equal(calls, 1); assert.equal(states.at(-1), 'disabled')
})

test('stalled reconciliation times out despite healthy heartbeats and ignores late completion', async () => {
    let handle, release, heartbeat, received = 0, attemptSignal
    const states = []
    handle = startUiEventStream(options({ reconciliationTimeoutMs: 20, watchdogIntervalMs: 1,
        fetcher: async (_, init) => {
            attemptSignal = init.signal
            return new Response(new ReadableStream({
                start(controller) {
                    const send = e => controller.enqueue(new TextEncoder().encode(frame(e)))
                    send(event()); send(event('inbox.invalidated'))
                    heartbeat = setInterval(() => send(event('stream.heartbeat')), 2)
                    init.signal.addEventListener('abort', () => { clearInterval(heartbeat); controller.error(new Error('aborted')) }, { once: true })
                },
                cancel() { clearInterval(heartbeat) },
            }), { headers: { 'Content-Type': 'text/event-stream' } })
        },
        onReady: () => new Promise(resolve => { release = resolve }), onEvent: () => received++,
        onState: state => { states.push(state); if (state === 'backoff') handle.stop() },
    }))
    try {
        await handle.done
        release(); await new Promise(resolve => setImmediate(resolve))
        assert.equal(attemptSignal.aborted, true); assert.ok(states.includes('backoff'))
        assert.ok(!states.includes('open')); assert.equal(received, 0)
    } finally { clearInterval(heartbeat); handle.stop() }
})
const id = '12345678-1234-1234-1234-123456789abc'
const event = (type = 'stream.ready') => ({ eventId: id, schemaVersion: 1, type,
    occurredAt: '2026-10-22T08:00:00Z', workspaceId: null, resource: null, correlationId: null,
    stream: type === 'stream.ready' ? { streamId: id, alertsAfter: '2026-10-22T08:00:00Z', heartbeatSeconds: 1 } : null })
const frame = e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`
function response(text, signal, bytes = false) {
    let aborted
    const body = new ReadableStream({
        start(controller) {
            aborted = () => { try { controller.error(new Error('aborted')) } catch {} }
            signal?.addEventListener('abort', aborted, { once: true })
            const encoded = new TextEncoder().encode(text)
            if (bytes) for (const byte of encoded) controller.enqueue(Uint8Array.of(byte))
            else if (text) controller.enqueue(encoded)
        },
        cancel() { signal?.removeEventListener('abort', aborted) },
    })
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } })
}
const options = extra => ({ getToken: () => 'secret', workspaceId: id,
    onReady: () => {}, onEvent: () => {}, onResync: () => {}, reconnectBaseMs: 1, ...extra })

test('SSE parser accepts split CRLF, comments, BOM, multiline data, ignores id/retry', () => {
    const values = []; const parser = new SseParser(v => values.push(v))
    for (const char of '\ufeff:comment\r\nid: private\r\nretry: 1\r\nevent: test\r\ndata: one\r\ndata: two\r\n\r\ndata: unfinished') parser.push(char)
    assert.deepEqual(values, [{ event: 'test', data: 'one\ntwo' }])
})
test('SSE parser bounds complete frames and unterminated lines', () => {
    assert.throws(() => new SseParser(() => {}, 32).push('data: ' + 'x'.repeat(40)))
    assert.throws(() => new SseParser(() => {}, 32).push('data: ' + 'я'.repeat(20) + '\n\n'))
})
test('stream decodes split UTF-8 and sends Bearer outside URL', async () => {
    const incoming = { ...event('inbox.invalidated'), futureLabel: 'Привет' }
    let handle, called = 0
    handle = startUiEventStream(options({
        fetcher: async (url, init) => {
            assert.equal(url, '/api/events?workspaceId=' + id); assert.ok(!url.includes('secret'))
            assert.equal(init.headers.Authorization, 'Bearer secret'); assert.equal(init.cache, 'no-store')
            return response(frame(event()) + frame(incoming), init.signal, true)
        },
        onEvent: value => { called++; assert.equal(value.futureLabel, 'Привет'); handle.stop() },
    }))
    await handle.done; assert.equal(called, 1)
})
test('events wait for initial reconciliation and flush in order', async () => {
    let handle, release, sawReady
    const waiting = new Promise(resolve => { sawReady = resolve })
    const reconciliation = new Promise(resolve => { release = resolve })
    const received = []
    handle = startUiEventStream(options({
        fetcher: async (_, init) => response(frame(event()) + frame(event('messages.invalidated')) + frame(event('inbox.invalidated')), init.signal),
        onReady: () => { sawReady(); return reconciliation },
        onEvent: value => { received.push(value.type); if (received.length === 2) handle.stop() },
    }))
    await waiting; await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(received, [])
    release(); await handle.done; assert.deepEqual(received, ['messages.invalidated', 'inbox.invalidated'])
})

test('access and identity controls bypass a pending ready reconciliation', async () => {
    let release
    const received = []
    const handle = startUiEventStream(options({
        fetcher: async (_, init) => response(frame(event()) + frame(event('inbox.invalidated'))
            + frame(event('access.invalidated')) + frame(event('identity.invalidated')), init.signal),
        onReady: () => new Promise(resolve => { release = resolve }), onEvent: value => received.push(value.type),
    }))
    try {
        await new Promise(resolve => setImmediate(resolve))
        assert.deepEqual(received, ['access.invalidated', 'identity.invalidated'])
        release(); await new Promise(resolve => setImmediate(resolve))
        assert.deepEqual(received, ['access.invalidated', 'identity.invalidated', 'inbox.invalidated'])
    } finally { handle.stop(); await handle.done }
})
test('reconciliation queue overflow requests resync instead of forwarding partial backlog', async () => {
    let handle, resyncs = 0, received = 0
    handle = startUiEventStream(options({
        fetcher: async (_, init) => response(frame(event()) + frame(event('inbox.invalidated')).repeat(129), init.signal),
        onReady: () => new Promise(() => {}), onEvent: () => received++,
        onResync: () => { resyncs++; handle.stop() },
    }))
    await handle.done; assert.equal(resyncs, 1); assert.equal(received, 0)
})
test('401 and 403 are terminal and never retry the same credential', async () => {
    for (const status of [401, 403]) {
        let calls = 0; const states = []
        const handle = startUiEventStream(options({ fetcher: async () => { calls++; return new Response('', { status }) }, onState: s => states.push(s) }))
        await handle.done; assert.equal(calls, 1); assert.equal(states.at(-1), status === 401 ? 'unauthorized' : 'forbidden')
    }
})
test('transient failure reconnects and starts reconciliation', async () => {
    let handle, calls = 0, ready = 0
    handle = startUiEventStream(options({
        fetcher: async (_, init) => ++calls === 1 ? new Response('', { status: 503 }) : response(frame(event()), init.signal),
        onReady: () => { ready++; handle.stop() },
    }))
    await handle.done; assert.equal(calls, 2); assert.equal(ready, 1)
})
test('account change during backoff stops the old transport', async () => {
    let token = 'old', calls = 0; const states = []
    const handle = startUiEventStream(options({ getToken: () => token,
        fetcher: async () => { calls++; return new Response('', { status: 503 }) },
        onState: state => { states.push(state); if (state === 'backoff') token = 'new' },
    }))
    await handle.done; assert.equal(calls, 1); assert.equal(states.at(-1), 'session-changed')
})
test('abort cancels pending read and ignores late reconciliation completion', async () => {
    let release, onReady, received = 0
    const waiting = new Promise(resolve => { onReady = resolve })
    const handle = startUiEventStream(options({
        fetcher: async (_, init) => response(frame(event()) + frame(event('inbox.invalidated')), init.signal),
        onReady: () => { onReady(); return new Promise(resolve => { release = resolve }) }, onEvent: () => received++,
    }))
    await waiting; handle.stop(); release(); await handle.done; assert.equal(received, 0)
})
test('missing ready times out and enters backoff', async () => {
    let handle; const states = []
    handle = startUiEventStream(options({ handshakeTimeoutMs: 5, watchdogIntervalMs: 1,
        fetcher: async (_, init) => response('', init.signal),
        onState: state => { states.push(state); if (state === 'backoff') handle.stop() },
    }))
    await handle.done; assert.ok(states.includes('backoff'))
})
test('unknown type requests reconciliation and does not reach resource listeners', async () => {
    let handle, received = 0
    handle = startUiEventStream(options({
        fetcher: async (_, init) => response(frame(event()) + frame(event('future.event')), init.signal),
        onEvent: () => received++, onResync: () => handle.stop(),
    }))
    await handle.done; assert.equal(received, 0)
})
test('unsupported version and malformed envelope trigger recovery', async () => {
    for (const bad of [{ ...event(), schemaVersion: 2 }, { ...event(), eventId: 'invalid' }]) {
        let handle, ready = 0
        handle = startUiEventStream(options({
            fetcher: async (_, init) => response(frame(bad), init.signal),
            onReady: () => ready++, onResync: () => handle.stop(),
        }))
        await handle.done; assert.equal(ready, 0)
    }
})

test('an incompatible schema enters terminal fallback instead of reconnecting the same protocol',async()=>{
    let calls=0,resyncs=0;const states=[]
    const handle=startUiEventStream(options({
        fetcher:async(_url,init)=>{calls++;return response(frame({...event(),schemaVersion:2}),init.signal)},
        onState:state=>states.push(state),onResync:()=>resyncs++,
    }))
    await handle.done
    assert.equal(calls,1);assert.equal(resyncs,1);assert.equal(states.at(-1),'incompatible')
})

test('REST reconciliation failure keeps the stream and continues delivering invalidation',async()=>{
    let handle,calls=0;const states=[],received=[]
    handle=startUiEventStream(options({
        fetcher:async(_url,init)=>{calls++;return response(frame(event())+frame(event('messages.invalidated')),init.signal)},
        onReady:async()=>{throw new Error('optional GET unavailable')},onState:state=>states.push(state),
        onEvent:event=>{received.push(event.type);handle.stop()},
    }))
    await handle.done
    assert.equal(calls,1);assert.ok(states.includes('degraded'));assert.deepEqual(received,['messages.invalidated'])
})

test('offline pauses connection attempts and online resumes once',async()=>{
    let online=false,calls=0,handle;const target=new EventTarget(),states=[]
    handle=startUiEventStream(options({isOnline:()=>online,connectivityTarget:target,
        fetcher:async(_url,init)=>{calls++;return response(frame(event()),init.signal)},
        onReady:()=>handle.stop(),onState:state=>states.push(state),
    }))
    await new Promise(resolve=>setImmediate(resolve));assert.equal(calls,0);assert.equal(states.at(-1),'offline')
    online=true;target.dispatchEvent(new Event('online'));await handle.done;assert.equal(calls,1)
})

test('short-lived ready streams keep exponential backoff instead of resetting on every ready',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']});t.mock.method(Math,'random',()=>0)
    let handle,backoffs=0;const starts=[]
    handle=startUiEventStream(options({
        fetcher:async()=>{
            starts.push(Date.now())
            return new Response(new ReadableStream({start(controller){
                controller.enqueue(new TextEncoder().encode(frame(event())))
                setTimeout(()=>controller.close(),1)
            }}),{headers:{'Content-Type':'text/event-stream'}})
        },
        onState:state=>{if(state==='backoff' && ++backoffs===4)handle.stop()},
    }))
    try {
        for(let i=0;i<100 && backoffs<4;i++){t.mock.timers.tick(1);for(let j=0;j<15;j++)await Promise.resolve()}
        await handle.done
        assert.equal(starts.length,4)
        const gaps=starts.slice(1).map((value,index)=>value-starts[index])
        assert.ok(gaps[1]>gaps[0]);assert.ok(gaps[2]>gaps[1])
    } finally {handle.stop()}
})
