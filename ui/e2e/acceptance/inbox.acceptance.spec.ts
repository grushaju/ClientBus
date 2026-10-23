import { readFileSync } from 'node:fs'
import { test as base, expect, type Page, type APIResponse } from '@playwright/test'
import { showList, showWorkbench } from '../fixtures/inbox'

type Role = 'first' | 'second' | 'supervisor' | 'admin'
type Identity = { username: string; password: string; employeeId: string }
type Fixture = Record<Role, Identity> & {
    workspaceId: string; workspaceName: string; hiddenWorkspaceId: string
    mineId: string; freeId: string; otherId: string; mineAccountId: string
    groupId: string; otherGroupId: string; clientId: string; clientName: string
    linkedId: string; anotherWorkspaceId: string; anotherWorkspaceName: string; foreignWorkspaceId: string
}
const manifest = JSON.parse(readFileSync(process.env.PW_ACCEPTANCE_MANIFEST!, 'utf8'))
const test = base.extend<{ fixture: Fixture }>({
    fixture: async ({}, use, info) => {
        const fixture = manifest.fixtures[`${info.project.name}:${info.title}`]
        if (!fixture) throw new Error(`Missing isolated fixture for ${info.title}`)
        await use(fixture)
    },
})

test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => { throw error })
})

async function login(page: Page, fixture: Fixture, role: Role = 'first', id = fixture.mineId) {
    if (page.url().startsWith('http')) await page.evaluate(() => localStorage.clear())
    const entry = await page.goto('/login')
    expect(entry?.status(), 'Direct SPA login URL must be public').toBe(200)
    await page.getByLabel('Username', { exact: true }).fill(fixture[role].username)
    await page.getByLabel('Пароль', { exact: true }).fill(fixture[role].password)
    await page.getByRole('button', { name: 'Войти', exact: true }).click()
    await expect(page).toHaveURL(/\/inbox/)
    await page.evaluate(workspaceId => localStorage.setItem('clientbus.currentWorkspaceId', workspaceId), fixture.workspaceId)
    await page.goto(`/inbox/${id}?tab=${role === 'admin' ? 'all' : 'mine'}`)
    await expect(page.locator('.conversation-header')).toBeVisible()
}

async function api(page: Page, fixture: Fixture, role: Role = 'first') {
    const response = await page.request.post('/api/auth/login', { data: {
        username: fixture[role].username, password: fixture[role].password,
    } })
    expect(response.ok(), 'Isolated user must authenticate').toBe(true)
    const { accessToken } = await response.json()
    return (path: string, method = 'GET', data?: unknown): Promise<APIResponse> => page.request.fetch(path, {
        method, data, headers: { Authorization: `Bearer ${accessToken}` },
    })
}

const row = (page: Page, id: string) => page.locator('.conversation-list-item-row').filter({ has: page.locator(`a[href*="/inbox/${id}?"]`) })
const groupBase = (f: Fixture) => `/api/conversations/workspace/${f.workspaceId}/groups`
const filterBase = (f: Fixture) => `/api/workspaces/${f.workspaceId}/conversation-filters`

test('navigation', async ({ page, fixture: f }) => {
    await login(page, f)
    await showWorkbench(page)
    await page.getByRole('button', { name: '+ Фильтр', exact: true }).click()
    const editor = page.getByRole('region', { name: 'Редактор фильтра' })
    await editor.getByLabel('Связь с клиентом', { exact: true }).selectOption('WITHOUT_CLIENT')
    await editor.getByLabel('Название фильтра', { exact: true }).fill('Without client')
    await editor.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Without client')
    const send = await api(page, f)
    const saved = await (await send(`${filterBase(f)}?filterType=PRIVATE`)).json()
    expect(saved).toHaveLength(1)
    expect(saved[0].filterDefinition.clientLink).toBe('WITHOUT_CLIENT')
    if (await page.getByRole('dialog', { name: 'Фильтры и группы' }).isVisible()) await page.keyboard.press('Escape')
    await showList(page)
    await page.getByRole('navigation', { name: 'Область диалогов' }).getByRole('button', { name: 'Другие', exact: true }).click()
    await expect(row(page, f.freeId)).toBeVisible()
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Without client')
    await row(page, f.freeId).locator('a').click()
    await expect(page).toHaveURL(new RegExp(`/inbox/${f.freeId}\\?tab=unassigned`))
    await page.goBack()
    await expect(page).toHaveURL(new RegExp(`/inbox/${f.mineId}\\?tab=unassigned`))
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Without client')
    await page.goForward()
    await showList(page)
    await expect(row(page, f.freeId)).toBeAttached()
    await page.getByRole('button', { name: 'Очистить фильтр', exact: true }).click()
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Без фильтра')
    const supervisor = await api(page, f, 'supervisor')
    const shared = await supervisor(filterBase(f), 'POST', { name: 'Shared', filterType: 'SYSTEM', filterDefinition: { view: 'MINE' } })
    expect(shared.status()).toBe(201)
    const sharedId = (await shared.json()).id
    expect((await send(`${filterBase(f)}/${sharedId}`, 'DELETE')).status()).toBe(403)
})

test('groups', async ({ page, fixture: f }) => {
    await login(page, f)
    await showWorkbench(page)
    const groups = page.getByRole('region', { name: 'Управление группами' })
    await groups.getByRole('button', { name: '+ Группа', exact: true }).click()
    await groups.getByPlaceholder('Название группы').fill('Created group')
    await groups.getByRole('button', { name: 'Значок группы', exact: true }).click()
    await groups.getByRole('button', { name: 'Сердце', exact: true }).click()
    await groups.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await groups.getByRole('button', { name: 'Изменить', exact: true }).click()
    await groups.getByPlaceholder('Название группы').fill('Renamed group')
    await groups.getByRole('button', { name: 'Сохранить', exact: true }).click()
    const send = await api(page, f)
    const created = (await (await send(groupBase(f))).json()).find((item: { name: string }) => item.name === 'Renamed group')
    expect(created.icon).toBe('heart.svg')
    if (await page.getByRole('dialog', { name: 'Фильтры и группы' }).isVisible()) await page.keyboard.press('Escape')
    await showList(page)
    await row(page, f.mineId).getByRole('button', { name: 'В группу', exact: true }).click()
    await row(page, f.mineId).getByRole('checkbox', { name: 'Renamed group', exact: true }).click()
    await expect(row(page, f.mineId).getByRole('checkbox', { name: 'Renamed group', exact: true })).toBeChecked()
    await expect.poll(async () => (await (await send(`${groupBase(f)}/${created.id}/conversations`)).json()).length).toBe(1)
    await row(page, f.mineId).getByRole('checkbox', { name: 'Renamed group', exact: true }).click()
    await expect(row(page, f.mineId).getByRole('checkbox', { name: 'Renamed group', exact: true })).not.toBeChecked()
    await expect.poll(async () => (await (await send(`${groupBase(f)}/${created.id}/conversations`)).json()).length).toBe(0)
    const colleague = await api(page, f, 'second')
    expect((await colleague(`${groupBase(f)}/${created.id}`, 'DELETE')).status()).toBe(404)
    await showWorkbench(page)
    page.once('dialog', dialog => dialog.accept())
    await groups.getByRole('button', { name: 'Удалить', exact: true }).click()
    await expect.poll(async () => (await (await send(groupBase(f))).json()).length).toBe(1)
    expect((await send(`/api/conversations/${f.mineId}`)).status()).toBe(200)
})

test('clients', async ({ page, fixture: f }) => {
    await login(page, f)
    const draft = page.getByPlaceholder('Введите сообщение...')
    await draft.fill('Keep my draft')
    const panel = page.locator('.conversation-client-panel')
    await panel.getByRole('button', { name: 'Создать клиента', exact: true }).click()
    await panel.getByLabel('Имя', { exact: true }).fill('Browser')
    await panel.getByLabel('Фамилия', { exact: true }).fill('Created')
    await panel.getByRole('button', { name: 'Создать и привязать', exact: true }).click()
    await expect(panel.getByText('Browser Created', { exact: true })).toBeVisible()
    await expect(draft).toHaveValue('Keep my draft')
    const send = await api(page, f)
    expect((await (await send(`/api/clientaccounts/${f.mineAccountId}`)).json()).clientId).toBeTruthy()
    await panel.getByRole('button', { name: 'Отвязать аккаунт', exact: true }).click()
    await expect(panel.getByText('Клиент не создан', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Привязать к клиенту', exact: true }).click()
    await panel.getByLabel('Поиск клиента', { exact: true }).fill('Existing')
    const found = await send('/api/clients/search?query=Existing')
    expect(found.status()).toBe(200)
    expect(await found.json()).toContainEqual(expect.objectContaining({ id: f.clientId }))
    await expect(panel.getByRole('combobox').locator('option').filter({ hasText: f.clientName })).toBeAttached()
    await panel.getByRole('combobox').selectOption(f.clientId)
    await panel.getByRole('button', { name: 'Привязать', exact: true }).click()
    await expect(panel.getByText(f.clientName, { exact: true })).toBeVisible()
    expect((await (await send(`/api/clientaccounts/${f.mineAccountId}`)).json()).clientId).toBe(f.clientId)
    await expect(draft).toHaveValue('Keep my draft')
})

test('assignments', async ({ page, fixture: f }) => {
    await login(page, f, 'first', f.freeId)
    await page.getByRole('button', { name: 'Взять', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Освободить', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Освободить', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Взять', exact: true })).toBeVisible()
    await page.goto(`/inbox/${f.mineId}?tab=mine`)
    await expect(page.getByPlaceholder('Введите сообщение...')).toBeVisible()
    const supervisor = await api(page, f, 'supervisor')
    expect((await supervisor(`/api/conversations/${f.mineId}/assignment/${f.second.employeeId}`, 'PUT')).status()).toBe(200)
    await expect(page.getByRole('button', { name: 'Повторить загрузку диалога', exact: true })).toBeVisible({ timeout: 20_000 })
    await expect(page.getByPlaceholder('Введите сообщение...')).toHaveCount(0)
    const first = await api(page, f)
    const denial = await first(`/api/conversations/${f.mineId}`)
    expect(denial.status()).toBe(403)
    expect((await denial.json()).code).toBe('ACCESS_DENIED')
    expect((await supervisor(`/api/conversations/${f.mineId}/assignment/${f.first.employeeId}`, 'PUT')).status()).toBe(200)
    await page.getByRole('button', { name: 'Повторить загрузку диалога', exact: true }).click()
    await expect(page.getByPlaceholder('Введите сообщение...')).toBeVisible()
    await login(page, f, 'supervisor')
    await page.locator('.employee-assignment-trigger').click()
    await page.getByRole('option', { name: /second Acceptance/ }).click()
    const admin = await api(page, f, 'admin')
    await expect.poll(async () => (await (await admin(`/api/conversations/${f.mineId}`)).json()).assignedEmployeeId).toBe(f.second.employeeId)
    await login(page, f, 'admin')
    await expect(page.getByPlaceholder('Введите сообщение...')).toHaveCount(0)
    expect((await admin(`/api/conversations/${f.mineId}/assignment`, 'DELETE')).status()).toBe(200)
})

test('concurrency', async ({ page, browser, fixture: f }) => {
    await login(page, f, 'first', f.freeId)
    const first = await api(page, f)
    const colleagueContext = await browser.newContext({ baseURL: manifest.baseURL, viewport: page.viewportSize()! })
    try {
        const colleague = await colleagueContext.newPage()
        await login(colleague, f, 'second', f.freeId)
        const assignmentResponse = (p: Page) => p.waitForResponse(response =>
            response.request().method() === 'POST' && response.url().endsWith(`/api/conversations/${f.freeId}/assignment/me`))
        const firstResponse = assignmentResponse(page)
        const secondResponse = assignmentResponse(colleague)
        await Promise.all([
            page.getByRole('button', { name: 'Взять', exact: true }).click(),
            colleague.getByRole('button', { name: 'Взять', exact: true }).click(),
        ])
        const results = await Promise.all([firstResponse, secondResponse])
        expect(results.map(result => result.status()).sort()).toEqual([200, 409])
        expect((await results.find(result => result.status() === 409)!.json()).code).toBe('CONVERSATION_ASSIGNMENT_CONFLICT')
    } finally { await colleagueContext.close() }
    expect((await first(`${groupBase(f)}/${f.groupId}/members`, 'POST', { conversationId: f.mineId })).status()).toBe(204)
    const supervisor = await api(page, f, 'supervisor')
    expect((await supervisor(`/api/conversations/${f.mineId}/assignment/${f.second.employeeId}`, 'PUT')).status()).toBe(200)
    expect(await (await first(`${groupBase(f)}/${f.groupId}/conversations`)).json()).toEqual([])
    expect((await first(`${groupBase(f)}/${f.groupId}/members`, 'POST', { conversationId: f.mineId })).status()).toBe(403)
})

test('errors', async ({ page, fixture: f }) => {
    await login(page, f)
    await showWorkbench(page)
    // Only the transport fault is injected. Successful responses and ACLs come from Spring.
    await page.route(`**/api/conversations/workspace/${f.workspaceId}/search`, route => route.abort('failed'))
    await page.getByRole('button', { name: 'Применить', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: /Failed to fetch|fetch|сеть|загруз/i })).toBeVisible()
    await expect(row(page, f.mineId)).toBeAttached()
    await page.unroute(`**/api/conversations/workspace/${f.workspaceId}/search`)
    await page.getByRole('button', { name: 'Повторить', exact: true }).click()
    await expect(page.locator('.conversation-list').getByRole('alert')).toHaveCount(0)
    await showWorkbench(page)
    await page.route(`**/api/conversations/workspace/${f.workspaceId}/search`, route => route.fulfill({
        status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'Acceptance temporary outage' }),
    }))
    await page.getByRole('button', { name: 'Применить', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Acceptance temporary outage' })).toBeVisible()
    await expect(row(page, f.mineId)).toBeAttached()
    await page.unroute(`**/api/conversations/workspace/${f.workspaceId}/search`)
    await page.getByRole('button', { name: 'Повторить', exact: true }).click()
    await expect(page.locator('.conversation-list').getByRole('alert')).toHaveCount(0)
    await page.goto(`/inbox/${f.otherId}?tab=mine`)
    await expect(page.getByRole('button', { name: 'Повторить загрузку диалога', exact: true })).toBeVisible()
    await expect(page.getByText('History other 1', { exact: true })).toHaveCount(0)
    await page.goto('/inbox/00000000-0000-0000-0000-000000000001?tab=mine')
    await expect(page.getByRole('button', { name: 'Повторить загрузку диалога', exact: true })).toBeVisible()
    await expect(page.getByPlaceholder('Введите сообщение...')).toHaveCount(0)
})

test('history', async ({ page, fixture: f }) => {
    await login(page, f)
    await expect(page.getByText('History mine 125', { exact: true })).toBeVisible()
    const messages = page.locator('.message-list')
    await messages.evaluate(element => { element.scrollTop = 0; element.dispatchEvent(new Event('scroll')) })
    await expect(page.getByText('History mine 1', { exact: true })).toBeAttached()
    for (let index = 1; index <= 125; index++) await expect(page.getByText(`History mine ${index}`, { exact: true })).toHaveCount(1)
    await expect.poll(() => messages.evaluate(element => element.scrollTop)).toBeGreaterThan(100)
    const draft = page.getByPlaceholder('Введите сообщение...')
    await draft.fill('Keep draft through navigation')
    await showWorkbench(page)
    await page.keyboard.press('Escape')
    await row(page, f.mineId).locator('a').click()
    await expect(draft).toHaveValue('Keep draft through navigation')
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('isolation', async ({ page, fixture: f }) => {
    await login(page, f)
    const first = await api(page, f)
    const supervisor = await api(page, f, 'supervisor')
    for (const send of [first, supervisor]) {
        expect((await send(`/api/conversations/workspace/${f.hiddenWorkspaceId}/search`, 'POST', { view: 'ALL' })).status()).toBe(403)
        expect((await send(`${groupBase(f)}/${f.otherGroupId}`, 'DELETE')).status()).toBe(404)
    }
    const admin = await api(page, f, 'admin')
    expect((await admin(`/api/conversations/workspace/${f.hiddenWorkspaceId}/search`, 'POST', { view: 'ALL' })).status()).toBe(200)
    expect((await admin(groupBase(f), 'POST', { name: 'Forbidden personal group', icon: 'users.svg' })).status()).toBe(403)
    expect((await admin(`/api/conversations/workspace/${f.foreignWorkspaceId}/search`, 'POST', { view: 'ALL' })).status()).toBe(403)
    await expect(page.locator('.workspace-switcher-trigger')).toContainText(f.workspaceName)
})

test('transitions', async ({ page, fixture: f }) => {
    await page.clock.install()
    await login(page, f)
    await expect(page.getByText('History mine 1', { exact: true })).toBeAttached()
    let releaseMessage!: () => void, messageStarted!: () => void, messageFinished!: () => void
    const messageGate = new Promise<void>(resolve => { releaseMessage = resolve })
    const messageReady = new Promise<void>(resolve => { messageStarted = resolve })
    const messageDone = new Promise<void>(resolve => { messageFinished = resolve })
    let held = false
    const messagesUrl = `**/api/messages/conversation/${f.mineId}?*`
    await page.route(messagesUrl, async route => {
        if (held) return route.continue()
        held = true
        const response = await route.fetch()
        messageStarted()
        await messageGate
        await route.fulfill({ response })
        messageFinished()
    })
    await page.clock.fastForward(5_000)
    await messageReady
    await showList(page)
    await row(page, f.linkedId).locator('a').click()
    await expect(page.getByText('History linked 1', { exact: true })).toBeAttached()
    await showList(page)
    await row(page, f.mineId).locator('a').click()
    await expect(page.getByText('History mine 1', { exact: true })).toBeAttached()
    releaseMessage()
    await messageDone
    await page.unroute(messagesUrl)
    await expect(page.locator('.message-list')).not.toContainText('History linked 1')

    await showWorkbench(page)
    let releaseSearch!: () => void, searchStarted!: () => void, searchFinished!: () => void
    const searchGate = new Promise<void>(resolve => { releaseSearch = resolve })
    const searchReady = new Promise<void>(resolve => { searchStarted = resolve })
    const searchDone = new Promise<void>(resolve => { searchFinished = resolve })
    const searchUrl = `**/api/conversations/workspace/${f.workspaceId}/search`
    await page.route(searchUrl, async route => {
        const response = await route.fetch()
        searchStarted()
        await searchGate
        await route.fulfill({ response })
        searchFinished()
    })
    await page.getByRole('button', { name: 'Применить', exact: true }).click()
    await searchReady
    await page.locator('.workspace-switcher-trigger').click()
    await page.locator('.workspace-switcher-option').filter({ hasText: f.anotherWorkspaceName }).click()
    await expect(page.locator('.workspace-switcher-trigger')).toContainText(f.anotherWorkspaceName)
    await expect(page.locator('.conversation-list-item-row')).toHaveCount(0)
    releaseSearch()
    await searchDone
    await page.unroute(searchUrl)
    await expect(page.locator('.conversation-list-item-row')).toHaveCount(0)
})

test('visibility', async ({ page, fixture: f }) => {
    await page.clock.install()
    await login(page, f)
    await expect(page.getByText('History mine 1', { exact: true })).toBeAttached()
    let reads = 0
    page.on('request', request => {
        if (new URL(request.url()).pathname === `/api/messages/conversation/${f.mineId}`) reads++
    })
    // Exercise real responsive hiding, including a desktop window resized to
    // mobile width. Native browser-tab hiding needs headed/manual acceptance:
    // this headless runtime keeps document.visibilityState === 'visible'.
    if (test.info().project.name === 'desktop') await page.setViewportSize({ width: 390, height: 850 })
    await page.getByPlaceholder('Введите сообщение...').fill('Hidden thread draft')
    await showList(page)
    const before = reads
    await page.clock.runFor(5_500)
    expect(reads, 'Hidden mobile thread must not poll messages').toBe(before)
    await row(page, f.mineId).locator('a').click()
    await expect(page.getByPlaceholder('Введите сообщение...')).toHaveValue('Hidden thread draft')
    await page.clock.runFor(5_500)
    await expect.poll(() => reads).toBeGreaterThan(before)
})
