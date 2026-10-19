import { test, expect } from '@playwright/test'

test('реальный сервер: неавторизованный Inbox показывает вход', async ({ page }) => {
    await page.goto('/inbox')
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: 'Вход', exact: true })).toBeVisible()
    await expect(page.getByLabel('Username', { exact: true })).toBeVisible()
    await expect(page.getByLabel('Пароль', { exact: true })).toBeVisible()
})

test('реальный сервер: вход тестовым сотрудником и загрузка Inbox', async ({ page }) => {
    test.skip(!process.env.PW_USERNAME || !process.env.PW_PASSWORD, 'Задайте PW_USERNAME и PW_PASSWORD для тестового сотрудника')
    await page.goto('/login')
    await page.getByLabel('Username', { exact: true }).fill(process.env.PW_USERNAME!)
    await page.getByLabel('Пароль', { exact: true }).fill(process.env.PW_PASSWORD!)
    const [searchResponse] = await Promise.all([
        page.waitForResponse(response => response.request().method() === 'POST'
            && /^\/api\/conversations\/workspace\/[^/]+\/search$/.test(new URL(response.url()).pathname)),
        page.getByRole('button', { name: 'Войти', exact: true }).click(),
    ])
    await expect(page.locator('.workspace-switcher-trigger')).toBeEnabled()
    expect(searchResponse.ok(), 'Сервер должен успешно вернуть список диалогов').toBe(true)
    await expect(page.getByRole('complementary', { name: 'Список диалогов' })).toBeVisible()
    await expect(page.locator('.conversation-list')).not.toContainText('Выберите Workspace')
})
