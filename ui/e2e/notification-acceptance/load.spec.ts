import { readFileSync, writeFileSync } from 'node:fs'
import { test, expect, type BrowserContext, type Page } from '@playwright/test'

const manifest = JSON.parse(readFileSync(process.env.PW_NOTIFICATION_LOAD_MANIFEST!, 'utf8'))
const f = manifest.fixture, profile = manifest.profile
type Role = 'owner' | 'other' | 'supervisor' | 'admin'
type Observer = { context: BrowserContext; page: Page; role: Role; token: string; source: string }
type Change = { sequence: number; source: string; requestedAt: number; committedAt: number; control: boolean }
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)))
const percentile = (values: number[], fraction: number) => [...values].sort((a, b) => a - b)[Math.ceil(values.length * fraction) - 1]

test('fixed two-backend local profile measures convergence, latency, REST and resources', async ({ browser }) => {
    const observers: Observer[] = [], changes: Change[] = [], samples: unknown[] = [], verifications: unknown[] = []
    const run = 'PROFILE_' + Date.now() + '_'
    let report: Record<string, unknown> = { profile, host: manifest.host, run, status: 'running' }
    let controlToken = ''
    try {
        for (const role of ['owner', 'other', 'supervisor', 'admin'] as Role[]) for (let node = 0; node < 2; node++) {
            const context = await browser.newContext({ baseURL: manifest.baseURLs[node], viewport: { width: 1440, height: 1000 } })
            const login = await context.request.post('/api/auth/login', { data: f[role] })
            expect(login.ok()).toBe(true)
            const { accessToken: token } = await login.json()
            if (role === 'admin' && node === 0) controlToken = token
            // Audio is accepted separately; keep this profile focused on reconciliation and rendering.
            const muted = await context.request.put('/api/notifications/preferences', {
                headers: { Authorization: 'Bearer ' + token }, data: { soundEnabled: false },
            })
            expect(muted.ok()).toBe(true)
            const page = await context.newPage()
            await page.addInitScript(({ token, workspace, prefix }) => {
                localStorage.setItem('clientbus.accessToken', token)
                localStorage.setItem('clientbus.currentWorkspaceId', workspace)
                const metrics = { gets: 0, active: 0, maxActive: 0, paths: {} as Record<string, number>,
                    errors: [] as Array<{ path: string; status: number }>, ready: false, frames: '', seen: {} as Record<string, number>, searchRequest: null as unknown }
                ;(window as any).__loadMetrics = metrics
                const original = window.fetch.bind(window)
                window.fetch = async (...args) => {
                    const url = new URL(String(args[0]), location.href), stream = url.pathname === '/api/events'
                    const counted = !stream && url.pathname.startsWith('/api/') && (args[1]?.method ?? 'GET').toUpperCase() === 'GET'
                    if (url.pathname === '/api/conversations/workspace/' + workspace + '/search' && typeof args[1]?.body === 'string')
                        metrics.searchRequest = JSON.parse(args[1].body)
                    let pending = counted
                    const done = () => { if (pending) { pending = false; metrics.active-- } }
                    if (counted) {
                        metrics.gets++; metrics.active++; metrics.maxActive = Math.max(metrics.maxActive, metrics.active)
                        metrics.paths[url.pathname] = (metrics.paths[url.pathname] ?? 0) + 1
                    }
                    try {
                        const response = await original(...args)
                        if (!response.ok && counted) metrics.errors.push({ path: url.pathname, status: response.status })
                        if (!response.body) { done(); return response }
                        const decoder = new TextDecoder()
                        const body = response.body.pipeThrough(new TransformStream({
                            transform(chunk, controller) {
                                if (stream && url.searchParams.get('workspaceId') === workspace) {
                                    metrics.frames = (metrics.frames + decoder.decode(chunk, { stream: true })).slice(-65536)
                                    if (metrics.frames.includes('stream.ready')) metrics.ready = true
                                }
                                controller.enqueue(chunk)
                            }, flush() { done() },
                        }))
                        args[1]?.signal?.addEventListener('abort', done, { once: true })
                        return new Response(body, { status: response.status, headers: response.headers })
                    } catch (error) { done(); throw error }
                }
                const scan = () => {
                    for (const element of document.querySelectorAll('.message-content')) {
                        const text = element.textContent ?? ''
                        if (!text.startsWith(prefix)) continue
                        const sequence = text.slice(prefix.length).split(' ')[0]
                        if (!(sequence in metrics.seen)) metrics.seen[sequence] = Date.now()
                    }
                }
                document.addEventListener('DOMContentLoaded', () => {
                    new MutationObserver(scan).observe(document.body, { subtree: true, childList: true, characterData: true }); scan()
                }, { once: true })
            }, { token, workspace: f.workspaceId, prefix: run })
            const source = role === 'other' ? 'load-other' : 'load'
            await page.goto('/inbox/' + (role === 'other' ? f.otherConversationId : f.conversationId) + '?tab=all')
            await expect(page.locator('.conversation-header')).toBeVisible()
            await expect.poll(() => page.evaluate(() => (window as any).__loadMetrics.ready)).toBe(true)
            if (role === 'owner' || role === 'other')
                await page.locator('.message-composer textarea').fill('Profile preserved draft ' + role)
            else await expect(page.locator('.message-composer')).toHaveCount(0)
            observers.push({ context, page, role, token, source })
        }
        const admin = observers.find(value => value.role === 'admin')!
        const request = (path: string, data?: unknown) => admin.context.request.fetch(path, {
            method: data === undefined ? 'GET' : 'POST', data, headers: { Authorization: 'Bearer ' + controlToken }, timeout: 120_000,
        })
        const stats = async () => {
            const response = await request('/api/test/notification-load/stats'); expect(response.ok()).toBe(true)
            const value = await response.json(); samples.push(value)
            expect(value.outboxQuarantined, 'Valid profile events must never drain into quarantine').toBe(0)
            return value
        }
        const verifyResources = async (phase: string) => {
            const started = Date.now()
            const verified = await Promise.all(observers.map(async observer => {
                const auth = { Authorization: 'Bearer ' + observer.token }, page = observer.page
                const json = async (path: string, data?: unknown) => {
                    const response = await observer.context.request.fetch(path, { headers: auth,
                        method: data === undefined ? 'GET' : 'POST', data })
                    expect(response.ok(), path).toBe(true); return response.json()
                }
                const query = await page.evaluate(() => (window as any).__loadMetrics.searchRequest)
                expect(query).toBeTruthy()
                await expect.poll(async () => {
                    const expected = await json('/api/conversations/workspace/' + f.workspaceId + '/search', { ...query, page: 0, size: 100 })
                    expect(expected.hasNext).toBe(false)
                    const actual = await page.locator('a.conversation-list-item').evaluateAll(links => links.map(link => ({
                        id: new URL((link as HTMLAnchorElement).href).pathname.split('/').pop(),
                        preview: link.querySelector('.conversation-list-item-bottom > span')?.textContent ?? '',
                        unread: Number(link.querySelector('.unread-badge')?.textContent ?? 0),
                    })))
                    return JSON.stringify(actual) === JSON.stringify(expected.content.map((item: any) => ({
                        id: item.id, preview: item.lastMessagePreview ?? 'Нет сообщений', unread: item.unreadCount,
                    })))
                }, { timeout: 30_000, message: phase + ': ordered inbox rows, previews and unread must match REST' }).toBe(true)
                const bell = page.locator('.notification-bell')
                if (await bell.getAttribute('aria-expanded') !== 'true') await bell.click()
                await expect.poll(async () => {
                    const [summary, list] = await Promise.all([json('/api/notifications/summary'), json('/api/notifications?limit=30')])
                    const actual = await page.locator('.notification-item').evaluateAll(items => items.map(item => ({
                        id: item.getAttribute('data-notification-id'), unread: item.classList.contains('unread'),
                        createdAt: item.querySelector('time')?.getAttribute('datetime'),
                    })))
                    return await bell.getAttribute('aria-label') === `Уведомления: ${summary.unreadCount} непрочитанных`
                        && JSON.stringify(actual) === JSON.stringify(list.items.map((item: any) => ({
                            id: item.id, unread: !item.readAt, createdAt: item.createdAt,
                        })))
                }, { timeout: 30_000, message: phase + ': notification count, IDs, ordering and read-state must match REST' }).toBe(true)
                const rows = await page.locator('a.conversation-list-item').count()
                const notifications = await page.locator('.notification-item').count()
                await page.locator('.notification-dialog > header button').click()
                return { role: observer.role, source: observer.source, rows, notifications }
            }))
            verifications.push({ phase, elapsedMs: Date.now() - started, contexts: verified })
        }
        await expect.poll(async () => (await stats()).outboxPending).toBe(0)
        for (const observer of observers) {
            await expect.poll(() => observer.page.evaluate(() => (window as any).__loadMetrics.active)).toBe(0)
            await observer.page.evaluate(() => { (window as any).__loadMetrics.maxActive = 0 })
        }
        const baselineGets = await Promise.all(observers.map(value => value.page.evaluate(() => (window as any).__loadMetrics.gets as number)))
        const emit = async (sequence: number, source: string, control: boolean) => {
            const requestedAt = Date.now()
            const response = await request('/api/test/notification-acceptance/message', {
                fixture: source, externalId: run + sequence, content: run + sequence + ' ' + source,
            })
            expect(response.ok()).toBe(true)
            const ack = await response.json()
            changes.push({ sequence, source, control, requestedAt, committedAt: Date.parse(ack.committedAt) })
        }
        const normalStart = Date.now(), normalCount = profile.normalMs / profile.spacingMs
        report = { ...report, normalStartedAt: new Date(normalStart).toISOString(), normalEmitted: 0, samples }
        writeFileSync('../server/target/notification-load-profile.json', JSON.stringify(report, null, 2))
        for (let index = 0; index < normalCount; index++) {
            await sleep(normalStart + index * profile.spacingMs - Date.now())
            await emit(index, index % 2 ? 'load-other' : 'load', index % 4 < 2)
            if (index % 40 === 0) {
                await stats()
                report = { ...report, normalEmitted: index + 1, samples }
                writeFileSync('../server/target/notification-load-profile.json', JSON.stringify(report, null, 2))
                console.log('Normal profile:', index + 1, '/', normalCount, 'committed changes')
            }
        }
        await sleep(normalStart + profile.normalMs - Date.now())
        // Every control must have appeared. A missing control is a failed run, never an omitted percentile sample.
        for (const observer of observers) {
            const controls = changes.filter(value => value.control && value.source === observer.source)
            await expect.poll(() => observer.page.evaluate(ids => ids.filter(id => !(id in (window as any).__loadMetrics.seen)),
                controls.map(value => String(value.sequence))), { timeout: 15_000 }).toEqual([])
        }
        const requestToDom: number[] = [], markerToDom: number[] = []
        let visibleBeforeMarker = 0
        for (const observer of observers) {
            const seen = await observer.page.evaluate(() => (window as any).__loadMetrics.seen as Record<string, number>)
            for (const change of changes.filter(value => value.control && value.source === observer.source)) {
                requestToDom.push(seen[change.sequence] - change.requestedAt)
                if (seen[change.sequence] < change.committedAt) visibleBeforeMarker++
                markerToDom.push(Math.max(0, seen[change.sequence] - change.committedAt))
            }
        }
        report = { ...report, normalEmitted: normalCount, phase: 'burst', normal: { emitted: normalCount, elapsedMs: Date.now() - normalStart, samples: requestToDom.length,
            requestToDomP95Ms: percentile(requestToDom, .95), postCommitMarkerToDomP95Ms: percentile(markerToDom, .95),
            visibleBeforeMarker, requestToDomMaxMs: Math.max(...requestToDom),
            criterion: 'request-to-DOM <= 5s is a conservative upper bound for commit-to-DOM' } }
        writeFileSync('../server/target/notification-load-profile.json', JSON.stringify(report, null, 2))
        expect(percentile(requestToDom, .95)).toBeLessThanOrEqual(5000)
        await verifyResources('normal')

        const burstGets = await Promise.all(observers.map(value => value.page.evaluate(() => (window as any).__loadMetrics.gets as number)))
        const burstStart = Date.now()
        // Generation has independent pacing. Waiting serially for each HTTP response would reduce
        // the requested rate to the source's round-trip latency instead of exercising the burst.
        await Promise.all(Array.from({ length: profile.burst }, async (_, index) => {
            await sleep(burstStart + index * 20 - Date.now())
            await emit(normalCount + index, index % 2 ? 'load-other' : 'load', false)
        }))
        const burstEmissionMs = Date.now() - burstStart
        for (const observer of observers) {
            const last = changes.filter(value => value.source === observer.source).sort((a, b) => b.sequence - a.sequence)[0]
            await expect(observer.page.locator('.message-content').filter({ hasText: run + last.sequence + ' ' })).toBeVisible({ timeout: 15_000 })
        }
        // Exercise both read and unread states under burst reconciliation, across the two nodes.
        const owner = observers.find(value => value.role === 'owner')!
        const ownerHeaders = { Authorization: 'Bearer ' + owner.token }
        const list = await owner.context.request.get('/api/notifications?limit=1', { headers: ownerHeaders })
        expect(list.ok()).toBe(true)
        const readId = (await list.json()).items[0].id
        const read = await owner.context.request.put('/api/notifications/' + readId + '/read', { headers: ownerHeaders })
        expect(read.ok()).toBe(true); expect((await read.json()).readAt).toBeTruthy()
        await verifyResources('burst')
        const burstExtraGets = await Promise.all(observers.map(async (value, index) =>
            await value.page.evaluate(() => (window as any).__loadMetrics.gets as number) - burstGets[index]))
        report = { ...report, phase: 'outage', burst: { changes: profile.burst, emissionMs: burstEmissionMs,
            convergenceMs: Date.now() - burstStart, extraGetsPerContext: burstExtraGets } }
        writeFileSync('../server/target/notification-load-profile.json', JSON.stringify(report, null, 2))
        // A burst is coalesced; no context may issue one resource GET for each event.
        expect(Math.max(...burstExtraGets)).toBeLessThan(profile.burst)
        expect(burstEmissionMs).toBeLessThanOrEqual(3000) // 2s target plus scheduling/HTTP tolerance, recorded explicitly.
        await stats()

        const paused = await request('/api/test/notification-acceptance/kafka', { paused: true }); expect(paused.ok()).toBe(true)
        const outageStart = Date.now()
        try {
            await emit(normalCount + profile.burst, 'load', false)
            const health = await request('/api/notifications/summary'); expect(health.ok()).toBe(true)
            await sleep(outageStart + profile.outageMs - Date.now())
        } finally { expect((await request('/api/test/notification-acceptance/kafka', { paused: false })).ok()).toBe(true) }
        const restoredAt = Date.now(), last = changes[changes.length - 1]
        for (const observer of observers.filter(value => value.source === 'load'))
            await expect(observer.page.locator('.message-content').filter({ hasText: run + last.sequence + ' ' })).toBeVisible({ timeout: 180_000 })
        await expect.poll(async () => (await stats()).outboxPending, { timeout: 180_000 }).toBe(0)
        await verifyResources('restored')
        report = { ...report, outage: { durationMs: restoredAt - outageStart, convergenceAfterRestoreMs: Date.now() - restoredAt } }
        for (const observer of observers.filter(value => value.role === 'owner' || value.role === 'other'))
            await expect(observer.page.locator('.message-composer textarea')).toHaveValue('Profile preserved draft ' + observer.role)
        const browserMetrics = await Promise.all(observers.map(async (value, index) => ({ role: value.role, source: value.source,
            ...await value.page.evaluate(() => { const m = (window as any).__loadMetrics; return { gets: m.gets, maxActive: m.maxActive, errors: m.errors, paths: m.paths } }),
            baselineGets: baselineGets[index] })))
        for (const metric of browserMetrics) { expect(metric.errors).toEqual([]); expect(metric.maxActive).toBeLessThanOrEqual(4) }
        for (const sample of samples as any[]) for (const node of sample.nodes) {
            for (const [active, capacity] of [['workers.active', 'workers.capacity'], ['executor.queue.entries', 'executor.queue.capacity']])
                expect(node['clientbus.notification.sse.' + active]).toBeLessThanOrEqual(node['clientbus.notification.sse.' + capacity])
        }
        report = { ...report, status: 'passed', browserVersion: browser.version(), browserMetrics, samples, verifications }
    } catch (error) {
        const failures = await Promise.all(observers.map(async value => ({ role: value.role, url: value.page.url(),
            ...await value.page.evaluate(() => ({ diagnostics: (window as any).clientbusNotificationDiagnostics?.() ?? null,
                errors: (window as any).__loadMetrics?.errors, hasToken: !!localStorage.getItem('clientbus.accessToken') })).catch(() => ({})) })))
        report = { ...report, status: 'failed', error: error instanceof Error ? error.message : String(error), failures, samples }
        throw error
    } finally {
        writeFileSync('../server/target/notification-load-profile.json', JSON.stringify(report, null, 2))
        await Promise.allSettled(observers.map(value => value.context.close()))
    }
})
