import { readFileSync } from 'node:fs'
import { test, expect, type Page } from '@playwright/test'
const { fixture: f, baseURL } = JSON.parse(readFileSync(process.env.PW_NOTIFICATION_MANIFEST!, 'utf8'))

async function login(page: Page, role: 'allowed' | 'forbidden', expectStream = true) {
    const response = await page.request.post('/api/auth/login', { data: f[role] })
    expect(response.ok()).toBe(true)
    const { accessToken } = await response.json()
    await page.addInitScript(({ token, workspace }) => {
        localStorage.setItem('clientbus.accessToken', token)
        localStorage.setItem('clientbus.currentWorkspaceId', workspace)
        // Observe bytes from the real stream while passing the same bytes to the application.
        const original = window.fetch.bind(window)
        ;(window as any).__notificationFrames = ''
        ;(window as any).__notificationWorkspaceFrames = {}
        ;(window as any).__notificationConnections = []
        ;(window as any).__notificationReadErrors = []
        ;(window as any).__notificationGets = 0
        window.fetch = async (...args) => {
            const url = new URL(String(args[0]), location.href)
            const record = url.pathname === '/api/events' ? { workspace: url.searchParams.get('workspaceId'),
                status: 0, aborted: false, types: [] as string[] } : null
            if (!record && (args[1]?.method ?? 'GET').toUpperCase() === 'GET') (window as any).__notificationGets++
            if (record) {
                ;(window as any).__notificationConnections.push(record)
                args[1]?.signal?.addEventListener('abort', () => { record.aborted = true }, { once: true })
            }
            const response = await original(...args)
            if (record) record.status = response.status
            if (!response.ok && !record) (window as any).__notificationReadErrors.push({ path: url.pathname, status: response.status })
            if (!response.headers.get('Content-Type')?.startsWith('text/event-stream') || !response.body) return response
            const workspace = new URL(String(args[0]), location.href).searchParams.get('workspaceId') ?? 'none'
            const decoder = new TextDecoder()
            const body = response.body.pipeThrough(new TransformStream({ transform(chunk, controller) {
                const text = decoder.decode(chunk, { stream: true })
                ;(window as any).__notificationFrames += text
                const frames = (window as any).__notificationWorkspaceFrames
                frames[workspace] = (frames[workspace] ?? '') + text
                if (record) record.types = [...frames[workspace].matchAll(/event:\s*([^\r\n]+)/g)].map(match => match[1])
                controller.enqueue(chunk)
            } }))
            return new Response(body, { status: response.status, headers: response.headers })
        }
    }, { token: accessToken, workspace: f.workspaceId })
    await page.goto(role === 'allowed' ? `/inbox/${f.conversationId}?tab=mine` : '/inbox?tab=mine')
    if (role === 'allowed') await expect(page.locator('.conversation-header')).toBeVisible()
    // The bootstrap stream without W can close when the catalogue selects W.
    // Send only after the final Workspace stream is admitted.
    if (expectStream) await expect.poll(() => page.evaluate(workspace =>
        ((window as any).__notificationWorkspaceFrames[workspace] ?? '').includes('stream.ready'), f.workspaceId)).toBe(true)
    return (path: string, data?: unknown) => page.request.fetch('/api/test/notifications/' + path, {
        method: data ? 'POST' : 'GET', data, headers: { Authorization: `Bearer ${accessToken}` },
    })
}

test('committed inbound updates two sessions; forbidden scope, rollback, duplicate and reconnect are safe', async ({ browser, page }) => {
    const secondContext = await browser.newContext({ baseURL })
    const forbiddenContext = await browser.newContext({ baseURL })
    const second = await secondContext.newPage(), forbidden = await forbiddenContext.newPage()
    try {
        const send = await login(page, 'allowed')
        await login(second, 'allowed'); await login(forbidden, 'forbidden')
        for (const current of [page, second]) await current.locator('.message-composer textarea').fill('Unsent notification draft')
        const stats = async () => (await send('stats')).json()
        const initial = await stats()
        const key = `${test.info().project.name}-${Date.now()}`
        const content = `Live notification ${key}`
        expect((await send('message', { externalId: key, content, rollback: true })).ok()).toBe(true)
        expect(await stats()).toEqual(initial)
        expect((await send('message', { externalId: key, content })).ok()).toBe(true)
        for (const current of [page, second]) {
            await expect(current.locator('.message-content').filter({ hasText: content })).toBeVisible()
            try {
                await expect.poll(() => current.evaluate(() => (window as any).__notificationFrames.includes('messages.invalidated'))).toBe(true)
            } catch (error) {
                const diagnostics = await current.evaluate(() => ({ connections: (window as any).__notificationConnections,
                    readErrors: (window as any).__notificationReadErrors }))
                throw new Error(`${error}\nSSE connection diagnostics: ${JSON.stringify(diagnostics)}`)
            }
            const backToList = current.locator('.inbox-mobile-toolbar button')
            const mobile = await backToList.isVisible()
            if (mobile) await backToList.click()
            const row = current.locator('.conversation-list-item-row').filter({ hasText: content })
            await expect(row).toHaveCount(1)
            if (mobile) await row.locator('a.conversation-list-item').click()
            await expect(current.locator('.message-composer textarea')).toHaveValue('Unsent notification draft')
        }
        await expect.poll(async () => (await stats()).published).toBe(initial.published + 2)
        const committed = await stats()
        expect((await send('message', { externalId: key, content })).ok()).toBe(true)
        await page.waitForTimeout(1500)
        expect(await stats()).toEqual(committed)
        expect(await forbidden.evaluate(() => (window as any).__notificationFrames.includes('messages.invalidated'))).toBe(false)
        const denied = await forbidden.request.get(`/api/messages/conversation/${f.conversationId}`, {
            headers: { Authorization: `Bearer ${await forbidden.evaluate(() => localStorage.getItem('clientbus.accessToken'))}` },
        })
        expect(denied.status()).toBe(403)
        const readyBefore = await page.evaluate(() => ((window as any).__notificationFrames.match(/event:stream.ready|event: stream.ready/g) ?? []).length)
        expect((await send('disconnect', {})).ok()).toBe(true)
        await expect.poll(() => page.evaluate(() => ((window as any).__notificationFrames.match(/event:stream.ready|event: stream.ready/g) ?? []).length)).toBeGreaterThan(readyBefore)
        const recovered = `Recovered notification ${key}`
        expect((await send('message', { externalId: key + '-reconnected', content: recovered })).ok()).toBe(true)
        for (const current of [page, second]) await expect(current.locator('.message-content').filter({ hasText: recovered })).toBeVisible()
    } finally { await secondContext.close(); await forbiddenContext.close() }
})

test('account changes and storage logout in another tab clear the open thread and its draft', async ({ context, page }) => {
    await login(page, 'allowed')
    await page.locator('.message-composer textarea').fill('Unsent account A draft')
    const other = await context.newPage()
    try {
        await other.goto('/')
        const response = await other.request.post('/api/auth/login', { data: f.forbidden })
        expect(response.ok()).toBe(true)
        const { accessToken } = await response.json()
        await other.evaluate(token => localStorage.setItem('clientbus.accessToken', token), accessToken)
        await expect(page.locator('.conversation-header')).toHaveCount(0)
        await expect(page.locator('.message-composer')).toHaveCount(0)
        await expect(page.locator('.message-content')).toHaveCount(0)
        await expect(page.locator('.conversation-state.conversation-error')).toBeVisible()
        await other.evaluate(() => localStorage.removeItem('clientbus.accessToken'))
        await expect(page).toHaveURL(/\/login$/)
    } finally { await other.close() }
})

test('disabled SSE uses bounded REST fallback and preserves the draft',async({page})=>{
    await page.route('**/api/events**',route=>route.fulfill({status:404,contentType:'application/json',body:'{}'}))
    const send=await login(page,'allowed',false)
    const content=`Fallback ${test.info().project.name}-${Date.now()}`
    await page.locator('.message-composer textarea').fill('Fallback draft')
    const gets=await page.evaluate(()=>(window as any).__notificationGets)
    const started=Date.now()
    expect((await send('message',{externalId:content,content})).ok()).toBe(true)
    await expect(page.locator('.message-content').filter({hasText:content})).toBeVisible({timeout:25_000})
    const elapsedMs=Date.now()-started
    await expect(page.locator('.message-composer textarea')).toHaveValue('Fallback draft')
    const connections=await page.evaluate(()=>(window as any).__notificationConnections)
    expect(connections.filter((value: {workspace:string})=>value.workspace===f.workspaceId)).toHaveLength(1)
    expect(connections.every((value: {status:number})=>value.status===404)).toBe(true)
    const extraGets=await page.evaluate(before=>(window as any).__notificationGets-before,gets)
    expect(extraGets).toBeLessThan(30)
    await test.info().attach('fallback-metrics',{body:JSON.stringify({elapsedMs,extraGets}),contentType:'application/json'})
})

test('rare REST control recovers a relay gap while SSE heartbeat remains live',async({page})=>{
    test.setTimeout(180_000)
    const send=await login(page,'allowed')
    const content=`Relay gap ${test.info().project.name}-${Date.now()}`
    const stats=async()=>(await send('stats')).json()
    await expect.poll(async()=>(await stats()).pending).toBe(0)
    await page.locator('.message-composer textarea').fill('Relay gap draft')
    const countType=(type:string)=>page.evaluate(value=>
        ((window as any).__notificationFrames.match(new RegExp('event:\\s*'+value,'g'))??[]).length,type)
    const invalidations=await countType('messages.invalidated'),heartbeats=await countType('stream.heartbeat')
    const gets=await page.evaluate(()=>(window as any).__notificationGets)
    expect((await send('relay',{paused:true})).ok()).toBe(true)
    const started=Date.now()
    try {
        expect((await send('message',{externalId:content,content})).ok()).toBe(true)
        await expect.poll(async()=>(await stats()).pending).toBeGreaterThan(0)
        await expect.poll(()=>countType('stream.heartbeat')).toBeGreaterThan(heartbeats)
        await expect(page.locator('.message-content').filter({hasText:content})).toHaveCount(0)
        await expect(page.locator('.message-content').filter({hasText:content})).toBeVisible({timeout:150_000})
        expect(Date.now()-started).toBeGreaterThan(90_000)
        expect(await countType('messages.invalidated')).toBe(invalidations)
        await expect(page.locator('.message-composer textarea')).toHaveValue('Relay gap draft')
        const elapsedMs=Date.now()-started
        const extraGets=await page.evaluate(before=>(window as any).__notificationGets-before,gets)
        expect(extraGets).toBeLessThan(30)
        await test.info().attach('relay-gap-metrics',{body:JSON.stringify({elapsedMs,extraGets}),contentType:'application/json'})
    } finally {
        expect((await send('relay',{paused:false})).ok()).toBe(true)
        await expect.poll(async()=>(await stats()).pending,{timeout:15_000}).toBe(0)
    }
})
