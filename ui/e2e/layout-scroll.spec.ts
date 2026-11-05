import { test, expect } from './fixtures/inbox'
import type { InboxApi } from './fixtures/inbox'
import type { Locator, Page } from '@playwright/test'

const timestamp = '2026-11-04T10:00:00Z'
const workspace = { id: 'workspace-1', organizationId: 'organization-1', name: 'Рабочее пространство', avatarUrl: null, createdAt: timestamp, updatedAt: timestamp }
const state = { dataset: 'CATALOG', status: 'SUCCEEDED', freshness: 'CURRENT', completeness: 'COMPLETE', generation: 1,
    lastAttemptAt: timestamp, lastSuccessAt: timestamp, errorCode: null }
const project = { id: 'crm-project', workspaceId: workspace.id, connectionId: 'crm-connection', provider: 'DIKIDI', externalId: '100',
    displayName: 'Проект CRM', currency: 'RUB', zoneId: 'Europe/Moscow', zoneSource: 'MANUAL', zoneRevision: 1, contextVersion: 1,
    enabled: true, connectionState: 'CONNECTED', version: 0, capabilities: [{ code: 'READ_SERVICES', availability: 'AVAILABLE', reasonCode: null }] }

async function installLongPages(page: Page, api: InboxApi) {
    api.role = 'admin'
    api.client.phoneList = Array.from({ length: 15 }, (_, index) => String(70000000000 + index))
    api.channels = Array.from({ length: 18 }, (_, index) => ({ ...api.channels[0], id: 'channel-' + index, name: 'Канал ' + index,
        account: { ...api.channels[0].account, id: 'channel-account-' + index } }))
    const employees = Array.from({ length: 18 }, (_, index) => ({ id: 'person-' + index, organizationId: workspace.organizationId,
        firstName: 'Сотрудник', lastName: String(index), username: 'person' + index, email: 'person' + index + '@example.test',
        enabled: true, role: 'EMPLOYEE', phone: '', avatarUrl: null, createdAt: timestamp, updatedAt: timestamp }))
    const memberships = employees.map(employee => ({ employeeId: employee.id, workspaceId: workspace.id, isSupervisor: false }))
    const workspaces = Array.from({ length: 18 }, (_, index) => ({ ...workspace, id: 'workspace-' + (index + 1), name: 'Пространство ' + index }))
    const clients = Array.from({ length: 30 }, (_, index) => ({ ...api.client, id: 'client-' + (index + 1), firstName: 'Клиент',
        lastName: String(index), enabled: true, phoneList: ['70000000001'], accountCount: 1, lastContactAt: timestamp }))
    const connections = Array.from({ length: 16 }, (_, index) => ({ id: index === 0 ? 'crm-connection' : 'crm-connection-' + index,
        provider: 'DIKIDI', displayName: 'Аккаунт CRM ' + index, loginHint: '••••0000', credentialConfigured: true,
        status: 'CONNECTED', version: 0, lastCheckedAt: timestamp, errorCode: null }))
    const masters = Array.from({ length: 24 }, (_, index) => ({ id: 'master-' + index, projectId: project.id, externalId: String(index),
        displayName: 'Мастер ' + index, employeeId: null, mappingUpdatedAt: null, mappingUpdatedBy: null, mappingPreviousId: null,
        mappingReason: null, lastObservedAt: timestamp, observationState: 'OBSERVED', version: 0 }))
    const services = masters.map((master, index) => ({ id: 'service-' + index, projectId: project.id, externalId: String(index),
        displayName: 'Услуга ' + index, category: 'Категория', offers: [{ id: 'offer-' + index, serviceId: 'service-' + index,
            masterId: master.id, masterDisplayName: master.displayName, externalMasterId: master.externalId,
            price: '1500', currency: 'RUB', durationSeconds: 1800, offerState: { active: true, online: true }, attributes: {}, warnings: [] }] }))
    await page.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname
        if (route.request().method() !== 'GET') return route.fallback()
        const reply = (json: unknown) => route.fulfill({ json })
        if (path === '/api/workspaces/all') return reply(workspaces)
        if (path === '/api/workspaces/statistics') return reply(workspaces.map(item => ({ workspaceId: item.id, employees: 18, channels: 18, conversations: 0, unread: 0, lastActivity: null })))
        if (path === '/api/workspaces/workspace-1') return reply(workspace)
        if (path === '/api/workspaces/workspace-1/memberships' || path === '/api/employees/organization/organization-1/memberships') return reply(memberships)
        if (path === '/api/employees/workspace/workspace-1' || path === '/api/employees/organization/organization-1') return reply(employees)
        if (path === '/api/conversations/workspace/workspace-1' || path === '/api/clients/client-1/conversations') return reply([])
        if (path === '/api/clients') return reply(clients)
        if (path === '/api/clients/client-1') return reply({ ...api.client, enabled: true })
        if (path === '/api/clients/client-1/clientaccounts') return reply(Array.from({ length: 8 }, (_, index) => ({ ...api.account, id: 'account-' + index, displayName: 'Аккаунт клиента ' + index })))
        if (path === '/api/crm/types') return reply(['DIKIDI'])
        if (path === '/api/crm/connections') return reply(connections)
        if (path === '/api/crm/projects' || path === '/api/crm/workspaces/workspace-1/projects') return reply([project])
        if (path === '/api/crm/projects/crm-project/services') return reply({ items: services, hasMore: false, nextCursor: null, revision: 1, dataState: state })
        if (path === '/api/crm/projects/crm-project/masters') return reply({ items: masters, hasMore: false, nextCursor: null, revision: 1, dataState: state })
        return route.fallback()
    })
}

async function scrollToBottom(page: Page, target: Locator) {
    await expect(target).toBeAttached()
    const container = page.locator('.app-main')
    await expect.poll(() => container.evaluate(element => element.scrollHeight - element.clientHeight)).toBeGreaterThan(0)
    const header = page.locator('.app-header')
    const headerY = (await header.boundingBox())!.y
    const box = (await container.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + Math.min(100, box.height / 2))
    await page.mouse.wheel(0, 50000)
    await expect.poll(() => container.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
    await expect(target).toBeInViewport({ ratio: 1 })
    expect((await header.boundingBox())!.y).toBe(headerY)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight + 1)).toBe(true)
}

test('Все страницы: нижние блоки доступны прокруткой при небольшой высоте окна', async ({ page, api }, info) => {
    await installLongPages(page, api)
    await page.setViewportSize({ width: info.project.name.startsWith('mobile') ? 390 : 1024, height: 600 })
    const pages = [
        ['/workspaces', '.workspace-card:last-child .workspace-card-actions button:last-child'],
        ['/employees', '.employee-card:last-child .employee-card-workspaces'],
        ['/clients', '.client-list-item:last-child .client-list-name'],
        ['/channels', '.channel-card:last-child .channel-card-actions button:last-child'],
        ['/clients/client-1', '.client-conversations-card h2'],
        ['/workspaces/workspace-1', '#crm .crm-actions button:last-child'],
        ['/crm?tab=services', '.crm-service:last-child'],
        ['/crm?tab=masters', '.crm-master:last-child'],
    ]
    for (const [path, target] of pages) {
        await test.step(path, async () => {
            await page.goto(path)
            await scrollToBottom(page, page.locator(target))
            await page.screenshot({ path: info.outputPath(path.replace(/[^a-z0-9]/gi, '-') + '.png') })
        })
    }
    for (const path of ['/settings', '/settings?section=crm']) {
        await page.goto(path)
        await scrollToBottom(page, page.locator(path.includes('crm') ? '.crm-connection:last-child .crm-actions' : '.settings-actions').last())
    }
})

test('Настройки: колесо над боковыми полями прокручивает всю страницу', async ({ page, api }) => {
    await installLongPages(page, api)
    await page.setViewportSize({ width: 2000, height: 600 })
    for (const path of ['/settings', '/settings?section=crm']) {
        await page.goto(path)
        const bottom = page.locator(path.includes('crm') ? '.crm-connection:last-child .crm-actions' : '.settings-actions').last()
        await expect(bottom).toBeAttached()
        const main = page.locator('.app-main')
        const box = (await main.boundingBox())!
        expect((await page.locator('.settings-page').boundingBox())!.x).toBeGreaterThan(box.x + 20)
        await page.mouse.move(box.x + 20, box.y + 120)
        await page.mouse.wheel(0, 50000)
        await expect.poll(() => main.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
        await expect(bottom).toBeInViewport({ ratio: 1 })
    }
})

test('Навигация: после перехода из меню PageDown прокручивает содержимое', async ({ page, api }) => {
    await installLongPages(page, api)
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 600 })
    await page.goto('/workspaces')
    await expect(page.locator('.workspace-card').last()).toBeAttached()
    await page.locator('.sidebar-nav').getByRole('link', { name: 'Клиенты', exact: true }).click()
    await expect(page.locator('.client-list-item').last()).toBeAttached()
    const main = page.locator('.app-main')
    await expect(main).toBeFocused()
    await page.keyboard.press('PageDown')
    await expect.poll(() => main.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
    await expect(page.locator('.app-header')).toBeInViewport({ ratio: 1 })
})

test('Workspace CRM: прямая ссылка раскрывает нижнюю область, а переход страницы возвращает к началу', async ({ page, api }) => {
    await installLongPages(page, api)
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 600 })
    await page.goto('/workspaces/workspace-1#crm')
    await expect(page.getByRole('button', { name: 'Настроить проект', exact: true })).toBeInViewport()
    await expect(page.locator('#crm')).toBeFocused()
    await expect.poll(() => page.locator('.app-main').evaluate(element => element.scrollTop)).toBeGreaterThan(0)
    await expect(page.locator('.app-header')).toBeInViewport({ ratio: 1 })
    const navigation = page.locator('.sidebar-nav').getByRole('link', { name: 'Клиенты', exact: true })
    await navigation.click()
    await expect(page.locator('.clients-header')).toBeInViewport({ ratio: 1 })
    await expect.poll(() => page.locator('.app-main').evaluate(element => element.scrollTop)).toBe(0)
})

test('Меню: нижние пункты и выход доступны при низком окне', async ({ page, api }) => {
    api.role = 'admin'
    await page.setViewportSize({ width: 1024, height: 300 })
    await page.goto('/settings')
    const sidebar = page.locator('.sidebar')
    await expect(sidebar.getByRole('button', { name: 'Выйти', exact: true })).toBeAttached()
    await expect.poll(() => sidebar.evaluate(element => element.scrollHeight - element.clientHeight)).toBeGreaterThan(0)
    const box = (await sidebar.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.wheel(0, 10000)
    await expect(sidebar.getByRole('button', { name: 'Выйти', exact: true })).toBeInViewport({ ratio: 1 })
})

test('Inbox: сообщения читаемы, редактор доступен без наложения панели клиента', async ({ page }, info) => {
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 600 })
    await page.route('**/api/messages/conversation/conversation-1**', route => {
        const url = new URL(route.request().url())
        if (url.pathname !== '/api/messages/conversation/conversation-1') return route.fallback()
        if (url.searchParams.has('beforeMessageId')) return route.fulfill({ json: { content: [],
            page: { number: 0, size: 100, totalPages: 0, totalElements: 0 } } })
        return route.fulfill({ json: { content: Array.from({ length: 60 }, (_, index) => ({ id: 'message-' + index,
            conversationId: 'conversation-1', type: 'TEXT', direction: 'INBOUND', senderType: 'CLIENT', content: 'Сообщение ' + index,
            sentAt: timestamp, createdAt: timestamp, deliveryStatus: 'READ', processingStatus: 'PROCESSED' })),
            page: { number: 0, size: 60, totalPages: 1, totalElements: 60 } } })
    })
    await page.goto('/inbox/conversation-1')
    const editor = page.getByPlaceholder('Введите сообщение...')
    const messages = page.locator('.message-list')
    await expect(messages.locator('.message-bubble').first()).toBeAttached()
    expect((await messages.boundingBox())!.height).toBeGreaterThanOrEqual(160)
    await expect.poll(() => messages.evaluate(element => element.scrollHeight - element.clientHeight)).toBeGreaterThan(0)
    await expect(messages.locator('.message-bubble').last()).toBeInViewport({ ratio: 1 })
    const box = (await messages.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.wheel(0, -50000)
    // Loading the history boundary preserves the reading position while adding
    // the "Начало диалога" separator. Finish scrolling after that update.
    await expect(messages.getByText('Начало диалога', { exact: true })).toBeAttached()
    await page.mouse.wheel(0, -50000)
    await expect.poll(() => messages.evaluate(element => element.scrollTop)).toBe(0)
    await expect(messages.getByText('Сообщение 0', { exact: true })).toBeInViewport({ ratio: 1 })
    await page.screenshot({ path: info.outputPath('inbox-history.png') })
    const content = page.locator('.conversation-main')
    const panel = page.locator('.conversation-client-panel')
    if (await page.locator('.conversation-view').evaluate(element => getComputedStyle(element).flexDirection === 'column')) {
        const contentBox = (await content.boundingBox())!
        expect(contentBox.y + contentBox.height).toBeLessThanOrEqual((await panel.boundingBox())!.y + 1)
    }
    // In a short window the page scrolls to the composer while the message
    // list keeps its own scrolling area large enough to read a full message.
    const headerBox = (await page.locator('.conversation-header').boundingBox())!
    await page.mouse.move(headerBox.x + 10, headerBox.y + 10)
    await page.mouse.wheel(0, 50000)
    await expect(editor).toBeInViewport({ ratio: 1 })
    const send = page.getByRole('button', { name: 'Отправить', exact: true })
    await expect(send).toBeInViewport({ ratio: 1 })
    await editor.fill('Проверка доступности редактора')
    await send.click({ trial: true })
    await expect(page.locator('.app-header')).toBeInViewport({ ratio: 1 })
    await page.screenshot({ path: info.outputPath('inbox-composer.png') })
})

test('Вход: кнопка доступна прокруткой при низком окне', async ({ page }) => {
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 300 })
    await page.route('**/api/employees/me', route => route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED', message: 'Требуется вход' } }))
    await page.goto('/login')
    const submit = page.getByRole('button', { name: 'Войти', exact: true })
    await expect(submit).toBeAttached()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight - innerHeight)).toBeGreaterThan(0)
    await page.mouse.move(page.viewportSize()!.width / 2, 150)
    await page.mouse.wheel(0, 10000)
    await expect(submit).toBeInViewport({ ratio: 1 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
