import { readFileSync } from 'node:fs'
import { test, expect, type Page } from '@playwright/test'

const manifest = JSON.parse(readFileSync(process.env.PW_NOTIFICATION_CENTER_MANIFEST!, 'utf8'))
type Credentials = { username: string; password: string }
type Fixture = { allowed: Credentials; other: Credentials; admin: Credentials; workspaceId: string;
    targetWorkspaceId: string; conversationId: string; ownerId: string; otherId: string }

async function token(page: Page, credentials: Credentials): Promise<string> {
    const response = await page.request.post('/api/auth/login', { data: credentials })
    expect(response.ok()).toBe(true)
    return (await response.json()).accessToken
}
async function login(page: Page, fixture: Fixture, role: 'allowed' | 'other' = 'allowed') {
    const accessToken = await token(page, fixture[role])
    await page.addInitScript(({ accessToken, workspaceId }) => {
        localStorage.setItem('clientbus.accessToken', accessToken)
        localStorage.setItem('clientbus.currentWorkspaceId', workspaceId)
        ;(window as any).__centerFrames = ''
        ;(window as any).__centerReadyWorkspaces = []
        ;(window as any).__centerConnections = 0
        const original = window.fetch.bind(window)
        window.fetch = async (...args) => {
            const url = new URL(String(args[0]), location.href)
            const workspace = url.searchParams.get('workspaceId')
            if (url.pathname === '/api/events') (window as any).__centerConnections++
            const response = await original(...args)
            if (!response.headers.get('Content-Type')?.startsWith('text/event-stream') || !response.body) return response
            const decoder = new TextDecoder()
            let streamText = ''
            const body = response.body.pipeThrough(new TransformStream({ transform(chunk, controller) {
                const text = decoder.decode(chunk, { stream: true })
                streamText += text
                ;(window as any).__centerFrames += text
                if (streamText.includes('stream.ready') && !(window as any).__centerReadyWorkspaces.includes(workspace))
                    (window as any).__centerReadyWorkspaces.push(workspace)
                controller.enqueue(chunk)
            } }))
            return new Response(body, { status: response.status, headers: response.headers })
        }
    }, { accessToken, workspaceId: fixture.workspaceId })
    await page.goto('/inbox?tab=mine')
    await expect(page.locator('.notification-bell')).toBeVisible()
    await expect.poll(() => page.evaluate(workspace => (window as any).__centerReadyWorkspaces.includes(workspace), fixture.workspaceId)).toBe(true)
    await expect(page.locator('.workspace-switcher-trigger')).toContainText('Center selected')
    expect(await page.evaluate(() => localStorage.getItem('clientbus.currentWorkspaceId'))).toBe(fixture.workspaceId)
    return accessToken
}
const count = (page: Page, value: number) => expect(page.locator('.notification-bell')).toHaveAttribute('aria-label', `Уведомления: ${value} непрочитанных`)
async function open(page: Page) {
    if (!await page.locator('.notification-dialog').isVisible()) await page.locator('.notification-bell').click()
    await expect(page.locator('.notification-dialog')).toBeVisible()
}

test('global center shares N1/N2, read state and safe read-all replay across sessions, reload and ACL changes', async ({ browser, page }) => {
    const name = test.info().project.name
    const f: Fixture = manifest.fixtures[name]
    const secondContext = await browser.newContext({ baseURL: manifest.baseURL,
        viewport: test.info().project.use.viewport, isMobile: test.info().project.use.isMobile,
        hasTouch: test.info().project.use.hasTouch })
    const otherContext = await browser.newContext({ baseURL: manifest.baseURL })
    const second = await secondContext.newPage(), other = await otherContext.newPage()
    let activeTab: Page | undefined
    try {
        const accessToken = await login(page, f)
        await login(second, f); const otherToken = await login(other, f, 'other')
        const adminToken = await token(page, f.admin)
        const adminHeaders = { Authorization: `Bearer ${adminToken}` }
        const headers = { Authorization: `Bearer ${accessToken}` }
        const assignment = (employee: string) => page.request.put(`/api/conversations/${f.conversationId}/assignment/${employee}`, { headers: adminHeaders })
        const incoming = async (suffix: string) => {
            const response = await page.request.post('/api/test/notification-center/message', { headers: adminHeaders,
                data: { fixture: name, externalId: `${name}-${suffix}-${Date.now()}`, content: `Center ${suffix}` } })
            expect(response.ok()).toBe(true)
        }
        for (const current of [page, second, other]) await count(current, 0)
        expect((await assignment(f.ownerId)).ok()).toBe(true)
        for (const current of [page, second]) { await count(current, 1); await open(current) }
        await expect(page.locator('.notification-item')).toHaveCount(1)
        await expect(page.locator('.notification-item')).toContainText('Вам назначен диалог')
        const dialog = await page.locator('.notification-dialog').boundingBox()
        expect(dialog).not.toBeNull()
        expect(dialog!.x).toBeGreaterThanOrEqual(0)
        expect(dialog!.x + dialog!.width).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
        await page.keyboard.press('Escape')
        await expect(page.locator('.notification-dialog')).toHaveCount(0)
        await expect(page.locator('.notification-bell')).toBeFocused()
        await open(page)
        await count(other, 0)
        // Target W-B is outside the selected W-A SSE scope, but personal notifications remain global.
        const beforeIncoming = await page.evaluate(() => ({ connections: (window as any).__centerConnections,
            created: ((window as any).__centerFrames.match(/event:\s*notifications.created/g) ?? []).length }))
        await incoming('first')
        for (const current of [page, second]) {
            await count(current, 2)
            await expect(current.locator('.notification-item')).toHaveCount(2)
            await expect(current.locator('.workspace-switcher-trigger')).toContainText('Center selected')
            expect(await current.evaluate(() => localStorage.getItem('clientbus.currentWorkspaceId'))).toBe(f.workspaceId)
        }
        await expect.poll(() => page.evaluate(() =>
            ((window as any).__centerFrames.match(/event:\s*notifications.created/g) ?? []).length)).toBeGreaterThan(beforeIncoming.created)
        expect(await page.evaluate(() => (window as any).__centerConnections)).toBe(beforeIncoming.connections)
        await page.screenshot({ path: test.info().outputPath('notification-center.png') })
        const privateList = await other.request.get('/api/notifications', { headers: { Authorization: `Bearer ${otherToken}` } })
        expect((await privateList.json()).items).toEqual([])
        const assigned = page.locator('.notification-item').filter({ hasText: 'Вам назначен диалог' })
        await assigned.getByRole('button', { name: 'Прочитано', exact: true }).click()
        for (const current of [page, second]) await count(current, 1)

        let firstKey = '', replayKey = ''
        await page.route('**/api/notifications/read-all', async route => {
            firstKey = route.request().headers()['idempotency-key']
            const committed = await route.fetch()
            expect(committed.ok()).toBe(true)
            await route.abort('failed')
        }, { times: 1 })
        await page.getByRole('button', { name: 'Отметить все прочитанными', exact: true }).click()
        await expect(page.getByRole('button', { name: 'Повторить отметку', exact: true })).toBeVisible()
        for (const current of [page, second]) await count(current, 0)
        expect(firstKey).toMatch(/^[0-9a-f-]{36}$/i)
        // Same-origin active tabs share localStorage. A second pending action must not replace the first retry key.
        activeTab = await page.context().newPage()
        await activeTab.goto('/inbox?tab=mine'); await count(activeTab, 0); await open(activeTab)
        let otherKey = ''
        await activeTab.route('**/api/notifications/read-all', async route => {
            otherKey = route.request().headers()['idempotency-key']; await route.abort('failed')
        }, { times: 1 })
        await activeTab.getByRole('button', { name: 'Отметить все прочитанными', exact: true }).click()
        await expect(activeTab.getByRole('button', { name: 'Повторить отметку', exact: true })).toBeVisible()
        expect(otherKey).not.toBe(firstKey)
        await page.bringToFront()
        await page.reload(); await open(page)
        await expect(page.getByRole('button', { name: 'Повторить отметку', exact: true })).toBeVisible()
        await incoming('after-lost-read-all')
        for (const current of [page, second]) await count(current, 1)
        await page.route('**/api/notifications/read-all', async route => {
            replayKey = route.request().headers()['idempotency-key']; await route.continue()
        }, { times: 1 })
        await page.getByRole('button', { name: 'Повторить отметку', exact: true }).click()
        await expect(page.getByRole('button', { name: 'Повторить отметку', exact: true })).toHaveCount(0)
        expect(replayKey).toBe(firstKey)
        const summary = await page.request.get('/api/notifications/summary', { headers })
        expect((await summary.json()).unreadCount).toBe(1)
        for (const current of [page, second]) await count(current, 1)
        await activeTab.bringToFront()
        await activeTab.reload(); await count(activeTab, 1); await open(activeTab)
        let otherReplay = ''
        await activeTab.route('**/api/notifications/read-all', async route => {
            otherReplay = route.request().headers()['idempotency-key']; await route.abort('failed')
        }, { times: 1 })
        await activeTab.getByRole('button', { name: 'Повторить отметку', exact: true }).click()
        await expect.poll(() => otherReplay).toBe(otherKey)
        await activeTab.close(); activeTab = undefined
        await page.bringToFront()
        // An unread row predating the new stream boundary stays in the center without a replayed banner.
        await page.reload(); await count(page, 1); await open(page)
        await expect(page.locator('.notification-item')).toHaveCount(3)
        await expect(page.locator('.notification-banner')).toHaveCount(0)
        await page.getByRole('button', { name: 'Отметить все прочитанными', exact: true }).click()
        for (const current of [page, second]) await count(current, 0)
        await incoming('before-revoke')
        for (const current of [page, second]) await count(current, 1)
        await expect(page.locator('.notification-item')).toHaveCount(4)
        // Opening the current target rechecks REST access, marks the shared row read and selects W-B.
        await page.locator('.notification-item.unread .notification-target').click()
        await expect(page).toHaveURL(new RegExp('/inbox/' + f.conversationId + '(?:[?#]|$)'))
        await expect(page.locator('.workspace-switcher-trigger')).toContainText('Center target')
        expect(await page.evaluate(() => localStorage.getItem('clientbus.currentWorkspaceId'))).toBe(f.targetWorkspaceId)
        for (const current of [page, second]) await count(current, 0)
        await open(page)
        await expect(page.locator('.notification-item')).toHaveCount(4)
        expect((await assignment(f.otherId)).ok()).toBe(true)
        for (const current of [page, second]) {
            await count(current, 0)
            await expect(current.locator('.notification-item')).toHaveCount(0)
            await expect(current.locator('.notification-banner')).toHaveCount(0)
        }
        const denied = await page.request.get(`/api/conversations/${f.conversationId}`, { headers })
        expect(denied.status()).toBe(403)
    } finally { await activeTab?.close(); await secondContext.close(); await otherContext.close() }
})
