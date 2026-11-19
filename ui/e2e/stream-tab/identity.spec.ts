import { test, expect } from '@playwright/test'
import { createServer, type ServerResponse } from 'node:http'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { randomUUID } from 'node:crypto'

// Real browser documents, real BroadcastChannel and fetch/SSE. The small server
// reproduces eviction by tab ID without loading the rest of the application.
const modules = Object.fromEntries(['streamTab', 'sseParser', 'uiEventStream'].map(name => [name,
    ts.transpileModule(readFileSync(`src/api/events/${name}.ts`, 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText]))
const script = `const modules=${JSON.stringify(modules)}, cache={};
function load(name){ name=name.replace('./',''); if(cache[name])return cache[name].exports;
const m={exports:{}};cache[name]=m;new Function('require','module','exports',modules[name])(load,m,m.exports);return m.exports; }
const identity=load('streamTab').streamTabIdentity();window.tabId=()=>identity.id;
window.readyCount=0;window.start=()=>window.stream=load('uiEventStream').startUiEventStream({
getToken:()=> 'synthetic-test-token',onState:s=>window.streamState=s,onReady:()=>{window.readyCount++},
onEvent:()=>{},onResync:()=>{},reconnectBaseMs:10,reconnectMaxMs:100});window.start();`
const active = new Map<string, ServerResponse>()
const server = createServer((req, res) => {
    const url = new URL(req.url!, 'http://localhost')
    if (url.pathname !== '/api/events') {
        res.writeHead(200, { 'content-type': 'text/html' })
        res.end(`<html><body>Stream test<script>${script}</script></body></html>`); return
    }
    const tab = url.searchParams.get('tab')!
    active.get(tab)?.end()
    active.set(tab, res)
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store' })
    const event = { eventId: randomUUID(), schemaVersion: 1, type: 'stream.ready', occurredAt: new Date().toISOString(),
        workspaceId: null, resource: null, stream: { streamId: randomUUID(), alertsAfter: new Date().toISOString(), heartbeatSeconds: 1 } }
    res.write(`event: stream.ready\ndata: ${JSON.stringify(event)}\n\n`)
    const timer = setInterval(() => res.write(': heartbeat\n\n'), 500)
    res.on('close', () => { clearInterval(timer); if (active.get(tab) === res) active.delete(tab) })
})
let base: string
test.beforeAll(async () => {
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
    base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
})
test.afterAll(async () => {
    for (const response of active.values()) response.end()
    server.closeAllConnections()
    await new Promise<void>(resolve => server.close(() => resolve()))
})

test('cloned session storage converges to two live streams; reload and reconnect preserve ownership', async ({ page, context }) => {
    await page.goto(base)
    await expect.poll(() => page.evaluate(() => (window as any).readyCount)).toBeGreaterThan(0)
    const popupPromise = page.waitForEvent('popup')
    await page.evaluate(url => window.open(url, '_blank'), base)
    const copied = await popupPromise
    await copied.waitForLoadState()
    const ids = async () => Promise.all([page, copied].map(p => p.evaluate(() => (window as any).tabId())))
    await expect.poll(async () => new Set(await ids()).size).toBe(2)
    await expect.poll(() => active.size).toBe(2)
    await expect.poll(() => copied.evaluate(() => (window as any).streamState)).toBe('open')
    await expect.poll(() => page.evaluate(() => (window as any).streamState)).toBe('open')
    const copiedId = await copied.evaluate(() => (window as any).tabId())
    await copied.reload()
    await expect.poll(() => copied.evaluate(() => (window as any).streamState)).toBe('open')
    expect(await copied.evaluate(() => (window as any).tabId())).toBe(copiedId)
    const originalId = await page.evaluate(() => (window as any).tabId())
    active.get(originalId)!.end()
    await expect.poll(() => page.evaluate(() => (window as any).readyCount)).toBeGreaterThan(1)
    await expect.poll(() => active.size).toBe(2)
    await copied.close()
    await expect.poll(() => active.size).toBe(1)
    expect(await page.evaluate(() => (window as any).tabId())).toBe(originalId)
    expect(context.pages()).toHaveLength(1)
})

test('a frozen owner converges after resume and history navigation restores a stream', async ({ page, context }) => {
    await page.goto(base)
    await expect.poll(() => page.evaluate(() => (window as any).streamState)).toBe('open')
    const copiedStorage = await page.evaluate(() => sessionStorage.getItem('clientbus.stream.tab'))
    const cdp = await context.newCDPSession(page)
    await cdp.send('Page.setWebLifecycleState', { state: 'frozen' })
    const copied = await context.newPage()
    await copied.addInitScript(id => {
        if (!sessionStorage.getItem('clientbus.stream.tab')) sessionStorage.setItem('clientbus.stream.tab', id!)
    }, copiedStorage)
    try {
        await copied.goto(base)
        await expect.poll(() => copied.evaluate(() => (window as any).streamState)).toBe('open')
    } finally { await cdp.send('Page.setWebLifecycleState', { state: 'active' }) }
    await expect.poll(async () => {
        const ids = await Promise.all([page, copied].map(p => p.evaluate(() => (window as any).tabId())))
        return new Set(ids).size
    }).toBe(2)
    await expect.poll(() => active.size).toBe(2)
    await copied.goto('about:blank')
    await expect.poll(() => active.size).toBe(1)
    await copied.goBack()
    await expect.poll(() => copied.evaluate(() => (window as any).streamState)).toBe('open')
    await expect.poll(() => active.size).toBe(2)
    await copied.close()
    await expect.poll(() => active.size).toBe(1)
})
