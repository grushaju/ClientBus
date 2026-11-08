import { test, expect } from './fixtures/inbox'
import type { Page } from '@playwright/test'
import type {} from '../src/api/events/uiDiagnostics'

declare global {
    interface Window {
        pageLoadingStream?: { opens: number; aborts: number }
    }
}

const pageChunk = /\/assets\/(\w+Page)-[^/]+\.js$/
const clientsChunk = '**/assets/ClientsPage-*.js'

function trackPages(page: Page) {
    const pages: string[] = []
    page.on('request', request => {
        const match = new URL(request.url()).pathname.match(pageChunk)
        if (match) pages.push(match[1])
    })
    return pages
}

// A live stream makes provider remounts observable during suspended navigation.
async function installStream(page: Page) {
    await page.addInitScript(() => {
        const stats = { opens: 0, aborts: 0 }
        Object.assign(window, { pageLoadingStream: stats })
        const original = window.fetch.bind(window)
        window.fetch = async (...args) => {
            if (new URL(String(args[0]), location.href).pathname !== '/api/events') return original(...args)
            stats.opens++
            const body = new ReadableStream<Uint8Array>({ start(controller) {
                args[1]?.signal?.addEventListener('abort', () => { stats.aborts++; controller.close() }, { once: true })
                controller.enqueue(new TextEncoder().encode(`event: stream.ready\ndata: ${JSON.stringify({
                    eventId: crypto.randomUUID(), schemaVersion: 1, type: 'stream.ready', occurredAt: new Date().toISOString(),
                    workspaceId: null, resource: null, correlationId: null,
                    stream: { streamId: crypto.randomUUID(), alertsAfter: new Date().toISOString(), heartbeatSeconds: 60 },
                })}\n\n`))
            } })
            return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
        }
    })
}

test('Страницы загружаются по требованию; ожидание сохраняет каркас и SSE', async ({ page }) => {
    const pages = trackPages(page)
    await installStream(page)
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    await page.route(clientsChunk, async route => { await gate; await route.continue() })
    try {
        await page.goto('/inbox/conversation-1')
        await expect(page.locator('.conversation-header')).toBeVisible()
        await expect.poll(() => page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.transport.state)).toMatch(/^(open|degraded)$/)
        expect(pages).toEqual(['InboxPage'])
        const header = await page.locator('.app-header').elementHandle()
        const stream = () => page.evaluate(() => ({ ...window.pageLoadingStream }))
        const before = await stream()
        // Initial workspace selection may replace the initial identity scope.
        expect(before.opens).toBeGreaterThan(0)
        expect(before.opens! - before.aborts!).toBe(1)
        const diagnostics = await page.evaluateHandle(() => window.clientbusNotificationDiagnostics)

        await page.locator('.sidebar-nav').getByRole('link', { name: 'Клиенты', exact: true }).click()
        await expect(page.locator('.app-main').getByRole('status')).toHaveText('Загрузка страницы…')
        await expect(page.locator('.conversation-header')).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Уведомления: 0 непрочитанных', exact: true })).toBeVisible()
        expect(await header!.evaluate(node => node === document.querySelector('.app-header'))).toBe(true)
        expect(await diagnostics.evaluate(value => value === window.clientbusNotificationDiagnostics)).toBe(true)
        expect(await stream()).toEqual(before)

        release()
        await expect(page.getByRole('heading', { name: 'Клиенты', exact: true })).toBeVisible()
        await expect(page.locator('.client-list-item')).toHaveCount(1)
        expect(pages).toEqual(['InboxPage', 'ClientsPage'])
        await page.goBack()
        await expect(page.locator('.conversation-header')).toBeVisible()
        await page.goForward()
        await expect(page.locator('.client-list-item')).toHaveCount(1)
        expect(pages).toEqual(['InboxPage', 'ClientsPage'])
        expect(await stream()).toEqual(before)
        expect(await diagnostics.evaluate(value => value === window.clientbusNotificationDiagnostics)).toBe(true)
    } finally {
        release()
    }
})

test('Холодная ссылка CRM сохраняет вкладку и параметры без загрузки Inbox', async ({ page }) => {
    const pages = trackPages(page)
    await page.route('**/api/crm/workspaces/workspace-1/projects', route => route.fulfill({ json: [] }))
    const path = '/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-09'
    await page.goto(path)
    await expect(page.getByRole('heading', { name: 'Подключите проект CRM', exact: true })).toBeVisible()
    expect(new URL(page.url()).pathname + new URL(page.url()).search).toBe(path)
    expect(pages).toEqual(['CrmPage'])
})

test('Ошибка чанка остаётся внутри страницы; переход и полное обновление восстанавливают работу', async ({ page }) => {
    let attempts = 0
    await page.route(clientsChunk, route => ++attempts === 1
        ? route.fulfill({ status: 404, contentType: 'text/plain', body: 'Missing chunk' })
        : route.continue())
    await page.goto('/inbox')
    await expect(page.locator('.conversation-header')).toBeVisible()
    const header = await page.locator('.app-header').elementHandle()
    const clients = page.locator('.sidebar-nav').getByRole('link', { name: 'Клиенты', exact: true })
    await clients.click()
    await expect(page.getByRole('alert')).toContainText('Не удалось открыть страницу')
    expect(await header!.evaluate(node => node === document.querySelector('.app-header'))).toBe(true)
    await expect(page.getByRole('button', { name: 'Уведомления: 0 непрочитанных', exact: true })).toBeVisible()
    await page.locator('.sidebar-nav').getByRole('link', { name: 'Диалоги', exact: true }).click()
    await expect(page.locator('.conversation-header')).toBeVisible()
    await clients.click()
    await expect(page.getByRole('alert')).toContainText('Не удалось открыть страницу')
    // React.lazy caches the rejected import. Only a real document reload retries it.
    expect(attempts).toBe(1)
    await page.getByRole('button', { name: 'Обновить страницу', exact: true }).click()
    await expect(page.locator('.client-list-item')).toHaveCount(1)
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(attempts).toBe(2)
})

test('Проверка роли выполняется до загрузки страницы администратора', async ({ page }) => {
    const pages = trackPages(page)
    await page.goto('/employees')
    await expect(page).toHaveURL(/\/inbox(?:\/conversation-1)?$/)
    await expect(page.locator('.conversation-header')).toBeVisible()
    expect(pages).toEqual(['InboxPage'])
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: 'Сотрудники', exact: true })).toHaveCount(0)
})

test('Без авторизации прямая ссылка загружает только страницу входа', async ({ context, api }) => {
    // The authenticated fixture initializes only its own page; this page starts with empty storage.
    const guest = await context.newPage()
    guest.on('pageerror', error => api.pageErrors.push(error.message))
    await guest.route('**/api/**', route => api.handle(route))
    const pages = trackPages(guest)
    try {
        await guest.goto('/inbox/conversation-1')
        await expect(guest).toHaveURL(/\/login$/)
        await expect(guest.locator('.login-card')).toBeVisible()
        expect(pages).toEqual(['LoginPage'])
        await expect(guest.locator('.app-layout')).toHaveCount(0)
    } finally {
        await guest.close()
    }
})
