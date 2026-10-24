import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { test, expect, type Page } from '@playwright/test'
const manifest = JSON.parse(readFileSync(process.env.PW_HISTORY_MANIFEST!, 'utf8'))
type Credentials = { username: string; password: string }
type Fixture = { employee: Credentials; admin: Credentials; workspaceId: string; conversationId: string }
async function token(page: Page, credentials: Credentials): Promise<string> {
    const response = await page.request.post('/api/auth/login', { data: credentials })
    expect(response.ok()).toBe(true); return (await response.json()).accessToken
}
async function login(page: Page, fixture: Fixture) {
    const accessToken = await token(page, fixture.employee)
    const admin = await token(page, fixture.admin)
    await page.addInitScript(({ accessToken, workspaceId }) => {
        localStorage.setItem('clientbus.accessToken', accessToken)
        localStorage.setItem('clientbus.currentWorkspaceId', workspaceId)
    }, { accessToken, workspaceId: fixture.workspaceId })
    return { Authorization: 'Bearer ' + admin }
}
async function startOlder(page: Page) {
    await expect.poll(async () => {
        await page.locator('.message-list').evaluate(element => {
            element.scrollTop = 0
            element.dispatchEvent(new Event('scroll', { bubbles: true }))
        })
        return await page.locator('.message-list-loading-older, .message-history-error').count()
    }).toBeGreaterThan(0)
}
test('lost 202 completes through Kafka and reload recovers the same terminal operation', async ({ page }) => {
    const name = test.info().project.name + '-recovery', fixture: Fixture = manifest.fixtures[name]
    const headers = await login(page, fixture), keys: string[] = []
    let lost = false
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/history-sync')) keys.push(request.headers()['idempotency-key']) })
    await page.route('**/api/conversations/*/history-sync', async route => {
        if (!lost) { lost = true; await route.fetch(); await route.abort('failed') }
        else await route.continue()
    })
    await page.goto('/inbox/' + fixture.conversationId)
    await expect(page.locator('.message-list').getByText('History cache ' + name, { exact: true })).toBeVisible()
    await startOlder(page)
    await expect(page.getByRole('button', { name: 'Повторить загрузку истории' })).toBeVisible()
    expect(keys).toHaveLength(1)
    expect((await page.request.post('/api/test/history/release', { headers, data: { fixture: name } })).ok()).toBe(true)
    const state = async () => (await page.request.get('/api/test/history/state/' + name, { headers })).json()
    await expect.poll(async () => (await state()).status).toBe('SUCCEEDED')
    await page.reload()
    await expect(page.getByText('History imported ' + name + ' 1', { exact: true })).toBeVisible()
    await expect.poll(() => keys.length).toBeGreaterThanOrEqual(2)
    expect(new Set(keys).size).toBe(1)
    await expect(page.locator('.message-history-error')).toHaveCount(0)
    await expect(page.locator('.notification-summary-warning')).toHaveCount(0)
    await expect(page.locator('.notification-bell')).toHaveAttribute('aria-label', 'Уведомления: 0 непрочитанных')
    expect(await state()).toMatchObject({ calls: 1, operations: 1, status: 'SUCCEEDED', notifications: 0 })
    await page.screenshot({ path: test.info().outputPath('history-recovered.png') })
})
test('connector failure is inline; explicit retry preserves the draft and produces no history alerts', async ({ page }) => {
    const name = test.info().project.name + '-inline', fixture: Fixture = manifest.fixtures[name]
    const headers = await login(page, fixture)
    await page.goto('/inbox/' + fixture.conversationId)
    await expect(page.locator('.message-list').getByText('History cache ' + name, { exact: true })).toBeVisible()
    await startOlder(page)
    await expect(page.locator('.message-list-loading-older')).toBeVisible()
    const composer = page.getByPlaceholder('Введите сообщение...')
    await composer.fill('Черновик во время загрузки истории')
    expect((await page.request.post('/api/test/history/release', { headers, data: { fixture: name } })).ok()).toBe(true)
    await expect(page.locator('.message-history-error')).toContainText('Канал недоступен')
    await expect(composer).toHaveValue('Черновик во время загрузки истории')
    expect((await page.request.post('/api/test/history/release', { headers, data: { fixture: name, fail: false } })).ok()).toBe(true)
    await page.getByRole('button', { name: 'Повторить загрузку истории' }).click()
    await expect(page.getByText('History imported ' + name + ' 1', { exact: true })).toBeVisible()
    await expect(page.locator('.message-history-error')).toHaveCount(0)
    await expect(composer).toHaveValue('Черновик во время загрузки истории')
    const state = await (await page.request.get('/api/test/history/state/' + name, { headers })).json()
    expect(state).toMatchObject({ calls: 2, operations: 2, status: 'SUCCEEDED', notifications: 0 })
    await page.screenshot({ path: test.info().outputPath('history-inline-retry.png') })
})

test('logout in another tab clears recovery while the accepted operation still completes', async ({ page, context }) => {
    const name = test.info().project.name + '-logout', fixture: Fixture = manifest.fixtures[name]
    const headers = await login(page, fixture)
    await page.goto('/inbox/' + fixture.conversationId)
    await expect(page.locator('.message-list').getByText('History cache ' + name, { exact: true })).toBeVisible()
    await startOlder(page)
    const pendingCount = () => page.evaluate(() => Object.keys(sessionStorage).filter(key => key.startsWith('clientbus.history.pending:')).length)
    await expect.poll(pendingCount).toBe(1)
    const peer = await context.newPage()
    try {
        await peer.goto('/settings')
        await peer.getByRole('button', { name: 'Выйти', exact: true }).click()
        await expect(page).toHaveURL(/\/login(?:\?|$)/)
        await expect.poll(pendingCount).toBe(0)
        expect((await page.request.post('/api/test/history/release', { headers, data: { fixture: name } })).ok()).toBe(true)
        const state = async () => (await page.request.get('/api/test/history/state/' + name, { headers })).json()
        await expect.poll(async () => (await state()).status).toBe('SUCCEEDED')
        expect(await state()).toMatchObject({ calls: 1, operations: 1, status: 'SUCCEEDED', notifications: 0 })
    } finally { await peer.close() }
})

test('discovered operation survives a status failure and cached messages remain visible on reload', async ({ page }) => {
    const name = test.info().project.name + '-discovery', fixture: Fixture = manifest.fixtures[name]
    const headers = await login(page, fixture)
    const employeeHeaders = { Authorization: 'Bearer ' + await token(page, fixture.employee), 'Idempotency-Key': randomUUID() }
    const accepted = await page.request.post('/api/conversations/' + fixture.conversationId + '/history-sync', {
        headers: employeeHeaders, data: { beforeExternalId: 'initial-' + name, limit: 100 },
    })
    expect(accepted.status()).toBe(202)
    const operation = await accepted.json()
    let failed = false, posts = 0
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/history-sync')) posts++ })
    await page.route('**/api/history-sync-operations/*', async route => {
        if (!failed) {
            failed = true
            await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'UNAVAILABLE' }) })
        } else await route.continue()
    })
    await page.goto('/inbox/' + fixture.conversationId)
    const cached = page.locator('.message-list').getByText('History cache ' + name, { exact: true })
    await expect(cached).toBeVisible()
    await expect(page.locator('.message-history-error')).toBeVisible()
    await expect.poll(() => page.evaluate(id => Object.keys(sessionStorage).some(key =>
        key.startsWith('clientbus.history.pending:') && JSON.parse(sessionStorage.getItem(key)!).operationId === id), operation.id)).toBe(true)
    await page.reload()
    await expect(cached).toBeVisible()
    await expect(page.locator('.message-list-loading-older')).toBeVisible()
    const composer = page.getByPlaceholder('Введите сообщение...')
    await composer.fill('Черновик при восстановлении найденной операции')
    expect((await page.request.post('/api/test/history/release', { headers, data: { fixture: name } })).ok()).toBe(true)
    await expect(page.getByText('History imported ' + name + ' 1', { exact: true })).toBeVisible()
    await expect(composer).toHaveValue('Черновик при восстановлении найденной операции')
    await expect(page.locator('.message-history-error')).toHaveCount(0)
    const state = await (await page.request.get('/api/test/history/state/' + name, { headers })).json()
    expect(state).toMatchObject({ calls: 1, operations: 1, status: 'SUCCEEDED', notifications: 0 })
    expect(posts).toBe(0)
    await page.screenshot({ path: test.info().outputPath('history-discovery-recovered.png') })
})

test('a message response with null timestamps keeps the conversation usable', async ({ page }) => {
    const fixture: Fixture = manifest.fixtures[test.info().project.name + '-recovery']
    await login(page, fixture)
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/api/messages/conversation/' + fixture.conversationId + '?*', async route => {
        const original = await route.fetch()
        const payload = await original.json()
        if (payload.content?.length) payload.content.push({ ...payload.content[0],
            id: '00000000-0000-4000-8000-000000000099', externalId: null,
            direction: 'OUTBOUND', deliveryStatus: 'PENDING', processingStatus: 'QUEUED',
            sentAt: null, createdAt: null, content: 'Message awaiting its timestamp',
        })
        await route.fulfill({ response: original, json: payload })
    })
    await page.goto('/inbox/' + fixture.conversationId)
    const message = page.locator('.message-row').filter({ hasText: 'Message awaiting its timestamp' })
    await expect(message).toBeVisible()
    await expect(message.locator('time')).toHaveText('')
    expect((await page.locator('.message-date-separator').allTextContents()).join(' ')).not.toContain('1970')
    await page.getByPlaceholder('Введите сообщение...').fill('Conversation still works')
    expect(errors).toEqual([])
})
