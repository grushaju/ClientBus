import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { test, expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'

type Credentials = { username: string; password: string }
type Role = 'owner' | 'other' | 'supervisor' | 'admin' | 'foreign'
type Fixture = Record<Role, Credentials> & { name: string; workspaceId: string; organizationId: string;
    conversationId: string; otherConversationId: string; ownerId: string; otherId: string; clientAccountId: string; channelAccountId: string }
type Manifest = { baseURLs: [string, string]; fixtures: Record<string, Fixture> }
const path = process.env.PW_NOTIFICATION_ACCEPTANCE_MANIFEST
const manifest: Manifest = path ? JSON.parse(readFileSync(path, 'utf8')) : { baseURLs: ['', ''], fixtures: {} }
test.skip(!path, 'This opt-in suite requires NotificationStageBrowserAcceptance.')

async function credentials(request: APIRequestContext, node: number, identity: Credentials) {
    const response = await request.post(manifest.baseURLs[node] + '/api/auth/login', { data: identity })
    expect(response.ok()).toBe(true)
    return (await response.json()).accessToken as string
}
async function context(browser: Browser, node: number): Promise<BrowserContext> {
    const use = test.info().project.use
    return browser.newContext({ baseURL: manifest.baseURLs[node], viewport: use.viewport,
        isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent, serviceWorkers: 'block' })
}
async function login(page: Page, node: number, fixture: Fixture, role: Role = 'owner', thread = true) {
    const token = await credentials(page.request, node, fixture[role])
    await page.addInitScript(({ token, workspace }) => {
        localStorage.setItem('clientbus.accessToken', token)
        localStorage.setItem('clientbus.currentWorkspaceId', workspace)
        const original = window.fetch.bind(window)
        ;(window as any).__stageFrames = ''
        ;(window as any).__stageWorkspaceFrames = {}
        ;(window as any).__stageConnections = []
        ;(window as any).__stageGets = 0
        ;(window as any).__stageSound = []
        // Observe the native audio implementation. Nothing supplies a fake successful resume/play.
        const Audio = window.AudioContext ?? (window as any).webkitAudioContext
        if (Audio) {
            const create = Audio.prototype.createOscillator
            Audio.prototype.createOscillator = function () {
                const current = this, oscillator = create.call(this)
                const start = oscillator.start.bind(oscillator)
                oscillator.start = (...times: number[]) => {
                    ;(window as any).__stageSound.push({ kind: 'tone', state: current.state, visible: !document.hidden, at: performance.now() })
                    return start(...times)
                }
                return oscillator
            }
            const createBuffer = Audio.prototype.createBufferSource
            Audio.prototype.createBufferSource = function () {
                const current = this, source = createBuffer.call(this)
                const start = source.start.bind(source)
                source.start = (...times: number[]) => {
                    ;(window as any).__stageSound.push({ kind: 'clip', state: current.state, visible: !document.hidden, at: performance.now() })
                    return start(...times)
                }
                return source
            }
        }
        window.fetch = async (...args) => {
            const url = new URL(String(args[0]), location.href)
            const isStream = url.pathname === '/api/events'
            if (!isStream && (args[1]?.method ?? 'GET').toUpperCase() === 'GET') (window as any).__stageGets++
            const record = isStream ? { workspace: url.searchParams.get('workspaceId'), status: 0 } : null
            if (record) (window as any).__stageConnections.push(record)
            const response = await original(...args)
            if (record) record.status = response.status
            if (!response.headers.get('Content-Type')?.startsWith('text/event-stream') || !response.body) return response
            const decoder = new TextDecoder()
            const body = response.body.pipeThrough(new TransformStream({ transform(chunk, controller) {
                const chunkText = decoder.decode(chunk, { stream: true })
                ;(window as any).__stageFrames += chunkText
                const frames = (window as any).__stageWorkspaceFrames
                const workspace = url.searchParams.get('workspaceId') ?? 'none'
                frames[workspace] = (frames[workspace] ?? '') + chunkText
                controller.enqueue(chunk)
            } }))
            return new Response(body, { status: response.status, headers: response.headers })
        }
    }, { token, workspace: fixture.workspaceId })
    const id = role === 'other' ? fixture.otherConversationId : fixture.conversationId
    await page.goto(manifest.baseURLs[node] + (thread && role !== 'foreign' ? `/inbox/${id}?tab=${role === 'admin' || role === 'supervisor' ? 'all' : 'mine'}` : '/inbox?tab=mine'))
    await expect(page.locator('.notification-bell')).toBeVisible()
    if (thread && role !== 'foreign') await expect(page.locator('.conversation-header')).toBeVisible()
    await expect.poll(() => page.evaluate(({ workspace, foreign }) => foreign
        ? (window as any).__stageFrames.includes('stream.ready')
        : ((window as any).__stageWorkspaceFrames[workspace] ?? '').includes('stream.ready'),
    { workspace: fixture.workspaceId, foreign: role === 'foreign' })).toBe(true)
    await expect.poll(() => page.evaluate(() => (window as any).clientbusNotificationDiagnostics?.()?.transport.state)).toBe('open')
    return token
}
const headers = (token: string) => ({ Authorization: `Bearer ${token}` })
const count = (page: Page, value: number) => expect(page.locator('.notification-bell')).toHaveAttribute('aria-label', `Уведомления: ${value} непрочитанных`)
const readyCount = (page: Page) => page.evaluate(() => ((window as any).__stageFrames.match(/event:\s*stream.ready/g) ?? []).length)
async function openCenter(page: Page) {
    if (!await page.locator('.notification-dialog').isVisible()) await page.locator('.notification-bell').click()
    await expect(page.locator('.notification-dialog')).toBeVisible()
    await expect(page.locator('.notification-items')).toHaveAttribute('aria-busy', 'false')
}
async function admin(page: Page, node: number, fixture: Fixture) {
    const token = await credentials(page.request, node, fixture.admin)
    return async (path: string, data?: unknown) => page.request.fetch(manifest.baseURLs[node] + '/api/test/notification-acceptance/' + path,
        { method: data === undefined ? 'GET' : 'POST', data, headers: headers(token), timeout: 90_000 })
}
async function incoming(send: Awaited<ReturnType<typeof admin>>, fixture: string, content: string, viaKafka = false, externalId = randomUUID()) {
    const response = await send('message', { fixture, externalId, content, viaKafka })
    expect(response.ok()).toBe(true)
    return { ...await response.json(), externalId }
}
async function contains(page: Page, text: string) {
    await expect(page.locator('.message-content').filter({ hasText: text })).toBeVisible()
}

test('metadata source changes reach another backend without exposing personal groups or filters', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-metadata']
    const own = await context(browser, 1), peer = await context(browser, 1)
    try {
        const first = await own.newPage(), other = await peer.newPage()
        await login(first, 1, f); const peerToken = await login(other, 1, f, 'other')
        const token = await credentials(first.request, 0, f.owner)
        await first.locator('.message-composer textarea').fill('Metadata must preserve this draft')
        const back = first.getByRole('button', { name: 'К списку диалогов', exact: true })
        if (await back.isVisible()) await back.click()
        const toggle = first.getByRole('button', { name: 'Фильтры и группы', exact: true })
        if (await toggle.getAttribute('aria-expanded') !== 'true') await toggle.click()
        await expect(first.getByRole('region', { name: 'Управление группами' })).toBeVisible()
        const groups = '/api/conversations/workspace/' + f.workspaceId + '/groups'
        const name = 'Private group ' + randomUUID()
        const created = await first.request.post(manifest.baseURLs[0] + groups, { headers: headers(token), data: { name, icon: 'users.svg' } })
        expect(created.status()).toBe(201)
        const group = await created.json()
        const picker = first.getByRole('button', { name: 'Управлять группой', exact: true })
        await expect(picker).toBeEnabled(); await picker.click()
        await expect(first.getByRole('button', { name, exact: true })).toBeVisible()
        const renamed = name + ' renamed'
        expect((await first.request.put(manifest.baseURLs[0] + groups + '/' + group.id,
            { headers: headers(token), data: { name: renamed, icon: 'users.svg' } })).ok()).toBe(true)
        await expect(first.getByRole('button', { name: renamed, exact: true })).toBeVisible()
        await picker.click()
        const filters = '/api/workspaces/' + f.workspaceId + '/conversation-filters'
        const filterName = 'Private filter ' + randomUUID()
        const saved = await first.request.post(manifest.baseURLs[0] + filters, { headers: headers(token),
            data: { name: filterName, filterType: 'PRIVATE', filterDefinition: { view: 'MINE', page: 0, size: 50 } } })
        expect(saved.status()).toBe(201)
        const filter = await saved.json()
        await expect(first.getByRole('button', { name: filterName, exact: true })).toBeVisible()
        for (const path of [groups, filters + '?filterType=PRIVATE']) {
            const response = await other.request.get(manifest.baseURLs[1] + path, { headers: headers(peerToken) })
            expect(response.ok()).toBe(true); expect(await response.json()).toEqual([])
        }
        expect(await other.evaluate(({ group, filter }) => {
            const frames = (window as any).__stageFrames as string
            return frames.includes(group) || frames.includes(filter) || frames.includes('groups.invalidated') || frames.includes('filters.invalidated')
        }, { group: group.id, filter: filter.id })).toBe(false)
        expect((await first.request.delete(manifest.baseURLs[0] + filters + '/' + filter.id, { headers: headers(token) })).ok()).toBe(true)
        await expect(first.getByRole('button', { name: filterName, exact: true })).toHaveCount(0)
        expect((await first.request.delete(manifest.baseURLs[0] + groups + '/' + group.id, { headers: headers(token) })).ok()).toBe(true)
        await expect(picker).toBeDisabled()
        const displayName = 'Changed remote contact ' + randomUUID()
        const changed = await first.request.put(manifest.baseURLs[0] + '/api/clientaccounts/' + f.clientAccountId,
            { headers: headers(token), data: { displayName } })
        expect(changed.ok()).toBe(true)
        await expect(first.locator('.conversation-list')).toContainText(displayName)
        await expect(first.locator('.message-composer textarea')).toHaveValue('Metadata must preserve this draft')
    } finally { await own.close(); await peer.close() }
})

test('directory pages reconcile remote metadata and preserve client and workspace edit drafts', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-directories']
    const current = await context(browser, 1), page = await current.newPage()
    try {
        await login(page, 1, f, 'admin', false)
        const token = await credentials(page.request, 0, f.admin)
        const get = async (path: string) => {
            const response = await page.request.get(manifest.baseURLs[0] + path, { headers: headers(token) })
            expect(response.ok()).toBe(true); return response.json()
        }
        const put = async (path: string, data: unknown) => {
            const response = await page.request.put(manifest.baseURLs[0] + path, { headers: headers(token), data })
            expect(response.ok()).toBe(true); return response.json()
        }
        const open = async (path: string) => {
            await page.goto(manifest.baseURLs[1] + path)
            await expect.poll(() => page.evaluate(() => (window as any).clientbusNotificationDiagnostics?.()?.transport.state)).toBe('open')
        }
        const account = await get('/api/clientaccounts/' + f.clientAccountId)
        const suffix = randomUUID().slice(0, 8)
        await open('/clients')
        await expect(page.locator('.clients-list')).toBeVisible()
        const name = 'Client-' + suffix
        await put('/api/clients/' + account.clientId, { firstName: name })
        await expect(page.locator('.clients-page')).toContainText(name)
        await open('/clients/' + account.clientId)
        await page.getByRole('button', { name: 'Редактировать', exact: true }).click()
        const clientInput = page.getByRole('textbox', { name: 'Имя', exact: true })
        await clientInput.fill('Client unsaved draft')
        await put('/api/clients/' + account.clientId, { firstName: name + ' changed' })
        await expect(page.locator('.client-details-page h1')).toContainText(name + ' changed')
        await expect(clientInput).toHaveValue('Client unsaved draft')
        await page.getByRole('button', { name: 'Отмена', exact: true }).click()
        await expect(page.locator('.client-details-info')).toContainText(name + ' changed')

        await open('/channels')
        const channels = await get('/api/channels/workspace/' + f.workspaceId)
        const channel = channels.find((value: { account?: { id: string } }) => value.account?.id === f.channelAccountId)
        expect(channel).toBeTruthy()
        await expect(page.locator('.channels-page')).toContainText(channel.name)
        await page.locator('.channel-card').filter({ hasText: channel.name }).getByRole('button', { name: 'Подробнее', exact: true }).click()
        await expect(page.locator('.channel-details-modal h2')).toHaveText(channel.name)
        await put('/api/channels/' + channel.id, { name: 'Channel-' + suffix })
        await expect(page.locator('.channels-page')).toContainText('Channel-' + suffix)
        await expect(page.locator('.channel-details-modal h2')).toHaveText('Channel-' + suffix)
        await page.locator('.channel-details-modal').getByLabel('Закрыть', { exact: true }).click()

        await open('/employees')
        await expect(page.locator('.employees-page')).toContainText('owner')
        await put('/api/employees/' + f.ownerId, { firstName: 'Employee-' + suffix })
        await expect(page.locator('.employees-page')).toContainText('Employee-' + suffix)

        await open('/workspaces')
        await expect(page.locator('.workspaces-grid')).toBeVisible()
        await put('/api/workspaces/' + f.workspaceId, { name: 'Workspace-' + suffix })
        await expect(page.locator('.workspaces-grid')).toContainText('Workspace-' + suffix)
        await open('/workspaces/' + f.workspaceId)
        await page.getByRole('button', { name: 'Изменить', exact: true }).click()
        const workspaceInput = page.getByRole('textbox', { name: 'Название', exact: true })
        await workspaceInput.fill('Workspace unsaved draft')
        await put('/api/workspaces/' + f.workspaceId, { name: 'Workspace-' + suffix + ' changed' })
        await expect(page.locator('.workspace-details-page h1')).toContainText('Workspace-' + suffix + ' changed')
        await expect(workspaceInput).toHaveValue('Workspace unsaved draft')
        await page.getByRole('button', { name: 'Отмена', exact: true }).click()
    } finally { await current.close() }
})

test('two complete backends fan out permitted changes and share read state without duplicating push intents', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-cross']
    const contexts: BrowserContext[] = []
    try {
        const pages: Page[] = []
        for (const node of [0, 1, 1, 0, 1, 1]) {
            const current = await context(browser, node); contexts.push(current); pages.push(await current.newPage())
        }
        const [first, second, other, supervisor, administrator, forbidden] = pages
        const firstToken = await login(first, 0, f), secondToken = await login(second, 1, f)
        const otherToken = await login(other, 1, f, 'other')
        await login(supervisor, 0, f, 'supervisor'); await login(administrator, 1, f, 'admin')
        const forbiddenToken = await login(forbidden, 1, f, 'foreign', false)
        const send = await admin(first, 0, f), sendOtherNode = await admin(second, 1, f)
        const stats = async () => (await send('stats/' + f.name)).json()
        const initial = await stats()
        expect(initial.gateways.every((gateway: { ready: boolean }) => gateway.ready)).toBe(true)
        expect(new Set(initial.gateways.map((gateway: { group: string }) => gateway.group)).size).toBe(2)
        for (const [node, token] of [[0, firstToken], [1, secondToken]] as const) {
            const id = randomUUID()
            const registered = await first.request.put(`${manifest.baseURLs[node]}/api/push/installations/${id}`, {
                headers: headers(token), data: { expectedRegistrationVersion: 0, platform: 'ANDROID', provider: 'FCM',
                    appId: 'clientbus.test', environment: 'SANDBOX', registrationReference: 'acceptance-' + id,
                    installationSecret: 'a'.repeat(43), enabled: true, reportedPermission: 'GRANTED' },
            })
            expect(registered.status()).toBe(200)
        }
        for (const current of [first, second]) await current.locator('.message-composer textarea').fill('Draft survives cross-node invalidation')
        const content = `Cross-node live ${randomUUID()}`
        const original = await incoming(send, f.name, content)
        for (const current of [first, second, supervisor, administrator]) await contains(current, content)
        for (const current of [first, second]) {
            await count(current, 1); await openCenter(current)
            await expect(current.locator('.notification-item')).toHaveCount(1)
        }
        await count(other, 0); await count(forbidden, 0)
        for (const [current, token] of [[other, otherToken], [forbidden, forbiddenToken]] as const) {
            const denied = await current.request.get(`${manifest.baseURLs[1]}/api/conversations/${f.conversationId}`, { headers: headers(token) })
            expect(denied.status()).toBe(403)
            expect(await current.evaluate(id => (window as any).__stageFrames.includes(id), f.conversationId)).toBe(false)
        }
        await expect.poll(async () => (await stats()).deliveries).toBe(2)
        await expect.poll(async () => (await stats()).acceptedDeliveries).toBe(2)
        expect((await incoming(sendOtherNode, f.name, content, false, original.externalId)).messageId).toBe(original.messageId)
        await expect.poll(async () => (await stats()).outboxPending).toBe(0)
        expect((await stats()).messages).toBe(initial.messages + 1)
        expect((await stats()).notifications).toBe(1)
        expect((await stats()).deliveries).toBe(2)
        const a = await first.request.get(manifest.baseURLs[0] + '/api/notifications', { headers: headers(firstToken) })
        const b = await second.request.get(manifest.baseURLs[1] + '/api/notifications', { headers: headers(secondToken) })
        const firstItem = (await a.json()).items[0], secondItem = (await b.json()).items[0]
        expect(firstItem.id).toBe(secondItem.id)
        const key = randomUUID()
        await first.route('**/api/notifications/read-all', async route => {
            const committed = await route.fetch(); expect(committed.ok()).toBe(true); await route.abort('failed')
        }, { times: 1 })
        await first.getByRole('button', { name: 'Отметить все прочитанными', exact: true }).click()
        await expect(first.getByRole('button', { name: 'Повторить отметку', exact: true })).toBeVisible()
        for (const current of [first, second]) await count(current, 0)
        const later = `After lost read response ${randomUUID()}`
        await incoming(sendOtherNode, f.name, later)
        for (const current of [first, second]) await count(current, 1)
        await first.getByRole('button', { name: 'Повторить отметку', exact: true }).click()
        await expect(first.getByRole('button', { name: 'Повторить отметку', exact: true })).toHaveCount(0)
        for (const current of [first, second]) await count(current, 1)
        const readA = await first.request.get(`${manifest.baseURLs[0]}/api/notifications/${firstItem.id}`, { headers: headers(firstToken) })
        const readB = await second.request.get(`${manifest.baseURLs[1]}/api/notifications/${firstItem.id}`, { headers: headers(secondToken) })
        const readAt = (await readA.json()).readAt
        expect(readAt).not.toBeNull(); expect((await readB.json()).readAt).toBe(readAt)
        for (const current of [first, second]) {
            await current.keyboard.press('Escape')
            await expect(current.locator('.message-composer textarea')).toHaveValue('Draft survives cross-node invalidation')
        }
        await second.reload(); await count(second, 1); await openCenter(second)
        await expect(second.locator('.notification-item')).toHaveCount(2)
        await expect(second.locator('.notification-banner')).toHaveCount(0)
        await second.screenshot({ path: test.info().outputPath('cross-backend-center.png') })
        // One logical notification per source occurrence, two registrations, regardless of two gateways.
        await expect.poll(async () => (await stats()).deliveries).toBe(4)
        const otherContent = `Other employee ${key}`
        await incoming(sendOtherNode, f.name + '-other', otherContent)
        await contains(other, otherContent); await count(other, 1)
        await count(first, 1)
        const adminToken = await credentials(second.request, 1, f.admin)
        const reassigned = await second.request.put(`${manifest.baseURLs[1]}/api/conversations/${f.conversationId}/assignment/${f.otherId}`,
            { headers: headers(adminToken) })
        expect(reassigned.ok()).toBe(true)
        for (const current of [first, second]) {
            await count(current, 0)
            await expect(current.locator('.conversation-header')).toHaveCount(0)
            await expect(current.locator('.message-composer')).toHaveCount(0)
            await openCenter(current)
            await expect(current.locator('.notification-item')).toHaveCount(0)
            await expect(current.locator('.notification-banner')).toHaveCount(0)
        }
        await count(other, 2)
    } finally { for (const current of contexts) await current.close() }
})

test('real Kafka outage leaves HTTP available and browsers reconcile before fanout recovers', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-outage']
    const contexts = [await context(browser, 0), await context(browser, 1)]
    const [first, second] = await Promise.all(contexts.map(current => current.newPage()))
    let paused = false
    try {
        const tokens = [await login(first, 0, f), await login(second, 1, f)]
        const send = await admin(second, 1, f)
        const stats = async () => (await send('stats/' + f.name)).json()
        for (const current of [first, second]) await current.locator('.message-composer textarea').fill('Kafka outage draft')
        const ready = [await readyCount(first), await readyCount(second)]
        expect((await send('kafka', { paused: true })).ok()).toBe(true); paused = true
        await expect.poll(async () => (await stats()).gateways.every((value: { ready: boolean }) => !value.ready), { timeout: 15_000 }).toBe(true)
        const content = `Committed without Kafka ${randomUUID()}`
        await incoming(send, f.name, content)
        for (const [index, current] of [first, second].entries()) {
            const rest = await current.request.get(`${manifest.baseURLs[index]}/api/messages/conversation/${f.conversationId}?page=0&size=30`, { headers: headers(tokens[index]) })
            expect(rest.status()).toBe(200); expect(await rest.text()).toContain(content)
            await expect(current.locator('.message-content').filter({ hasText: content })).toBeVisible({ timeout: 30_000 })
            await expect(current.locator('.message-composer textarea')).toHaveValue('Kafka outage draft')
            expect(await readyCount(current)).toBe(ready[index])
        }
        expect((await stats()).outboxPending).toBeGreaterThan(0)
        expect((await send('kafka', { paused: false })).ok()).toBe(true); paused = false
        await expect.poll(async () => (await stats()).gateways.every((value: { ready: boolean }) => value.ready), { timeout: 60_000 }).toBe(true)
        for (const [index, current] of [first, second].entries())
            await expect.poll(() => readyCount(current), { timeout: 45_000 }).toBeGreaterThan(ready[index])
        await expect.poll(async () => (await stats()).outboxPending, { timeout: 30_000 }).toBe(0)
        const recovered = `After actual broker recovery ${randomUUID()}`
        await incoming(send, f.name, recovered, true)
        for (const current of [first, second]) { await contains(current, recovered); await count(current, 2) }
        await first.screenshot({ path: test.info().outputPath('kafka-recovered.png') })
    } finally {
        if (paused) { const send = await admin(second, 1, f); await send('kafka', { paused: false }) }
        for (const current of contexts) await current.close()
    }
})

test('a complete backend restart preserves history results and active browser recovery', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-history']
    const contexts = [await context(browser, 0), await context(browser, 1)]
    const [first, second] = await Promise.all(contexts.map(current => current.newPage()))
    try {
        const ownerToken = await login(first, 0, f), secondToken = await login(second, 1, f)
        const send = await admin(second, 1, f)
        const key = randomUUID()
        const accepted = await first.request.post(`${manifest.baseURLs[0]}/api/conversations/${f.conversationId}/history-sync`, {
            headers: { ...headers(ownerToken), 'Idempotency-Key': key }, data: { beforeExternalId: `initial-owner-${f.name}`, limit: 100 },
        })
        expect(accepted.status()).toBe(202)
        const operation = await accepted.json()
        const status = async (node: number) => {
            const response = await second.request.get(`${manifest.baseURLs[node]}/api/history-sync-operations/${operation.id}`, { headers: headers(secondToken) })
            expect(response.status()).toBe(200); return response.json()
        }
        await expect.poll(async () => (await status(1)).status).toBe('SUCCEEDED')
        const terminal = await status(1)
        await contains(first, 'Acceptance imported ' + f.name)
        await count(first, 0); await count(second, 0)
        for (const current of [first, second]) await current.locator('.message-composer textarea').fill('Full backend restart draft')
        const ready = await readyCount(first)
        expect((await send('restart/0', {})).ok()).toBe(true)
        await expect.poll(() => readyCount(first), { timeout: 60_000 }).toBeGreaterThan(ready)
        expect(await status(0)).toEqual(terminal)
        const replay = await second.request.post(`${manifest.baseURLs[1]}/api/conversations/${f.conversationId}/history-sync`, {
            headers: { ...headers(secondToken), 'Idempotency-Key': key }, data: { beforeExternalId: `initial-owner-${f.name}`, limit: 100 },
        })
        expect(replay.status()).toBe(202); expect((await replay.json()).id).toBe(operation.id)
        const content = `Live after backend restart ${randomUUID()}`
        await incoming(send, f.name, content)
        for (const current of [first, second]) {
            await contains(current, content)
            await expect(current.locator('.message-composer textarea')).toHaveValue('Full backend restart draft')
            await count(current, 1)
        }
        await first.screenshot({ path: test.info().outputPath('backend-restarted.png') })
    } finally { for (const current of contexts) await current.close() }
})

test('sound preferences persist across both backends and native audio starts only after a trusted gesture', async ({ browser }) => {
    const f = manifest.fixtures[test.info().project.name + '-sound']
    const current = await context(browser, 0), page = await current.newPage()
    try {
        const token = await login(page, 0, f, 'owner', false)
        // Inbox may automatically select its first conversation even without an ID in the URL.
        // Verify an off-thread notification from a page that cannot display that conversation.
        await page.goto(manifest.baseURLs[0] + '/settings')
        await expect.poll(() => page.evaluate(() => (window as any).clientbusNotificationDiagnostics?.()?.transport.state)).toBe('open')
        const send = await admin(page, 1, f)
        const preferences = async (node: number) => {
            const response = await page.request.get(manifest.baseURLs[node] + '/api/notifications/preferences', { headers: headers(token) })
            expect(response.status()).toBe(200); return response.json()
        }
        expect(await preferences(1)).toEqual({ soundEnabled: true })
        await openCenter(page)
        const sound = page.getByRole('checkbox', { name: 'Звуковые уведомления', exact: true })
        await expect(sound).toBeChecked(); await expect(sound).toBeEnabled()
        // This real pointer event unlocks audio. The explicit preview is a separate, observed native play.
        await page.getByRole('button', { name: 'Проверить звук', exact: true }).click()
        await expect.poll(() => page.evaluate(() => (window as any).__stageSound.length)).toBe(1)
        expect(await page.evaluate(() => (window as any).__stageSound[0].kind)).toBe('clip')
        expect(await page.evaluate(() => (window as any).__stageSound.every((value: { state: string }) => value.state === 'running'))).toBe(true)
        const content = `Off-thread sound ${randomUUID()}`
        const first = await incoming(send, f.name, content)
        await count(page, 1)
        await expect.poll(() => page.evaluate(() => (window as any).__stageSound.length), { timeout: 10_000 }).toBe(2)
        expect(await page.evaluate(() => (window as any).__stageSound[1].state)).toBe('running')
        await incoming(send, f.name, content, false, first.externalId)
        await expect.poll(async () => (await (await send('stats/' + f.name)).json()).outboxPending).toBe(0)
        expect(await page.evaluate(() => (window as any).__stageSound.length)).toBe(2)
        // Headless Chromium keeps every tab visible. Simulate only Page Visibility; fetch, SSE,
        // ACL and native AudioBufferSourceNode remain real. The shared resource scheduler pauses.
        await page.evaluate(() => {
            Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
            Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
            document.dispatchEvent(new Event('visibilitychange'))
        })
        const listed = await page.request.get(manifest.baseURLs[1] + '/api/notifications?limit=30', { headers: headers(token) })
        expect(listed.ok()).toBe(true)
        const notification = (await listed.json()).items[0]
        expect(notification.readAt).toBeNull()
        const invalidations = await page.evaluate(() => ((window as any).__stageFrames.match(/event:\s*notifications.invalidated/g) ?? []).length)
        const marked = await page.request.put(manifest.baseURLs[1] + '/api/notifications/' + notification.id + '/read', { headers: headers(token) })
        expect(marked.ok()).toBe(true)
        await expect.poll(() => page.evaluate(() => ((window as any).__stageFrames.match(/event:\s*notifications.invalidated/g) ?? []).length)).toBeGreaterThan(invalidations)
        // The product deliberately suppresses consecutive tones within two seconds.
        await page.waitForTimeout(2100)
        await incoming(send, f.name, `Fresh after remote read ${randomUUID()}`)
        await expect.poll(() => page.evaluate(() => (window as any).__stageSound.length), { timeout: 10_000 }).toBe(3)
        expect(await page.evaluate(() => (window as any).__stageSound[2])).toMatchObject({ kind: 'clip', state: 'running', visible: false })
        await page.evaluate(() => {
            delete (document as any).hidden; delete (document as any).visibilityState
            document.dispatchEvent(new Event('visibilitychange'))
        })
        await count(page, 1); await expect(sound).toBeEnabled()
        await sound.click(); await expect(sound).not.toBeChecked()
        await expect.poll(() => preferences(1)).toEqual({ soundEnabled: false })
        await page.reload(); await openCenter(page)
        await expect(page.getByRole('checkbox', { name: 'Звуковые уведомления', exact: true })).not.toBeChecked()
        expect(await preferences(0)).toEqual({ soundEnabled: false })
        await incoming(send, f.name, `Muted sound ${randomUUID()}`)
        await count(page, 2)
        await expect(page.locator('.notification-banner')).toHaveCount(1)
        expect(await page.evaluate(() => (window as any).__stageSound.length)).toBe(0)
        await page.screenshot({ path: test.info().outputPath('sound-muted.png') })
    } finally { await current.close() }
})
