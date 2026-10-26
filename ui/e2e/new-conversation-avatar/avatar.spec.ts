import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'

const manifest = JSON.parse(readFileSync(process.env.PW_NEW_CONVERSATION_AVATAR_MANIFEST!, 'utf8'))
type Credentials = { username: string; password: string }
type Fixture = { employee: Credentials; admin: Credentials; workspaceId: string; channelAccountId: string;
    externalId: string; username: string; phone: string; displayName: string }
type State = { accounts: number; conversations: number; accountId: string; avatarKey: string; storageObjects: number }

async function token(page: Page, credentials: Credentials) {
    const response = await page.request.post('/api/auth/login', { data: credentials })
    expect(response.ok()).toBe(true)
    return (await response.json()).accessToken as string
}

async function open(page: Page) {
    await page.getByRole('button', { name: '+ Новый диалог', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Новый диалог', exact: true })).toBeVisible()
}

async function search(page: Page, fixture: Fixture, mode: 'phone' | 'username') {
    const dialog = page.getByRole('dialog', { name: 'Новый диалог', exact: true })
    if (mode === 'username') await dialog.getByRole('button', { name: 'Username', exact: true }).click()
    await dialog.getByLabel(mode === 'phone' ? 'Номер телефона' : 'Username', { exact: true })
        .fill(mode === 'phone' ? fixture.phone : '@' + fixture.username)
    await dialog.getByRole('button', { name: 'Найти', exact: true }).click()
    await expect(dialog.locator('.new-conversation-result-name')).toHaveText(fixture.displayName)
    const image = dialog.locator('.new-conversation-result-avatar img')
    await expect(image).toBeVisible()
    await expect.poll(() => image.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(4)
    await expect(image).toHaveAttribute('src', /^blob:/)
    return image.getAttribute('src')
}

test('temporary phone/username avatar is discarded on cancel; starting persists and renders the full photo', async ({ page }) => {
    const name = test.info().project.name
    const fixture: Fixture = manifest.fixtures[name]
    const employeeToken = await token(page, fixture.employee)
    const adminToken = await token(page, fixture.admin)
    const employeeHeaders = { Authorization: `Bearer ${employeeToken}` }
    const state = async (): Promise<State> => {
        const response = await page.request.get('/api/test/new-conversation-avatar/' + name,
            { headers: { Authorization: `Bearer ${adminToken}` } })
        expect(response.ok()).toBe(true)
        return response.json()
    }
    const initial = await state()
    expect(initial.accounts).toBe(0)
    expect(initial.conversations).toBe(0)
    await page.addInitScript(({ employeeToken, workspaceId }) => {
        localStorage.setItem('clientbus.accessToken', employeeToken)
        localStorage.setItem('clientbus.currentWorkspaceId', workspaceId)
    }, { employeeToken, workspaceId: fixture.workspaceId })
    await page.goto('/inbox?tab=mine')
    await expect(page.locator('.workspace-switcher-trigger')).toContainText('Avatar acceptance')

    await open(page)
    const phonePreview = await search(page, fixture, 'phone')
    expect(await state()).toEqual(initial)
    await page.getByRole('dialog', { name: 'Новый диалог' }).getByRole('button', { name: 'Отмена', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Новый диалог' })).toHaveCount(0)
    expect(await state()).toEqual(initial)
    expect(await page.evaluate(async url => { try { return (await fetch(url!)).ok } catch { return false } }, phonePreview)).toBe(false)

    await open(page)
    await search(page, fixture, 'username')
    expect(await state()).toEqual(initial)
    const layout = await page.locator('.new-conversation-dialog').boundingBox()
    expect(layout).not.toBeNull()
    expect(layout!.x).toBeGreaterThanOrEqual(0)
    expect(layout!.x + layout!.width).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
    await page.screenshot({ path: test.info().outputPath('temporary-preview.png') })
    const outbound = page.waitForResponse(response => response.url().endsWith('/api/conversations/outbound') && response.request().method() === 'POST')
    await page.getByRole('dialog', { name: 'Новый диалог' }).getByRole('button', { name: 'Открыть диалог', exact: true }).click()
    const createdResponse = await outbound
    expect(createdResponse.status()).toBe(201)
    const created = await createdResponse.json()
    await expect(page).toHaveURL(new RegExp('/inbox/' + created.id + '(?:[?#]|$)'))
    const persisted = await state()
    expect(persisted.accounts).toBe(1)
    expect(persisted.conversations).toBe(1)
    expect(persisted.accountId).toBe(created.clientAccountId)
    expect(persisted.avatarKey).not.toBe('')
    expect(persisted.storageObjects).toBe(initial.storageObjects + 1)

    const content = await page.request.get(`/api/clientaccounts/${created.clientAccountId}/avatar/content`, { headers: employeeHeaders })
    expect(content.status(), 'Persisted avatar endpoint').toBe(200)
    expect((await content.body()).toString('base64')).toBe(manifest.fullPhotoBase64)
    expect((await content.body()).toString('base64')).not.toBe(manifest.previewPhotoBase64)
    const fullImage = page.locator('.conversation-header .client-account-avatar img')
    await expect(fullImage).toBeVisible()
    await expect.poll(() => fullImage.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(32)
    await page.screenshot({ path: test.info().outputPath('persisted-full-avatar.png') })

    // Reload removes all temporary preview state; the same image is served from persisted storage.
    await page.reload()
    await expect(page.locator('.conversation-header .client-account-avatar img')).toBeVisible()
    await expect.poll(() => page.locator('.conversation-header .client-account-avatar img')
        .evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(32)
    expect(await state()).toEqual(persisted)

    const repeated = await page.request.post('/api/conversations/outbound', { headers: employeeHeaders, data: {
        workspaceId: fixture.workspaceId, channelAccountId: fixture.channelAccountId, channelType: 'TELEGRAM',
        externalId: fixture.externalId, username: fixture.username, phone: fixture.phone, displayName: fixture.displayName,
    } })
    expect(repeated.status()).toBe(201)
    expect((await repeated.json()).id).toBe(created.id)
    expect(await state()).toEqual(persisted)
})
