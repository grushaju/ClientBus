import { test, expect, showList, showWorkbench } from './fixtures/inbox'

test('выбранный фильтр сохраняется при смене области и очищается отдельной кнопкой', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    await expect(page.getByPlaceholder('Введите сообщение...')).toBeVisible()
    await showWorkbench(page)
    await page.getByRole('button', { name: 'Мой фильтр', exact: true }).click()
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Мой фильтр')
    await page.getByRole('navigation', { name: 'Область диалогов' }).getByRole('button', { name: 'Другие', exact: true }).click()
    await expect.poll(() => api.searches.at(-1)?.view).toBe('UNASSIGNED')
    await expect.poll(() => api.searches.at(-1)?.unread).toBe('HAS_UNREAD')
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Мой фильтр')
    await page.getByRole('button', { name: 'Очистить фильтр', exact: true }).click()
    await expect(page.getByLabel('Выбранный фильтр')).toHaveText('Без фильтра')
    await expect.poll(() => api.searches.at(-1)?.unread).toBe('ANY')
})

test('личный фильтр сохраняет изменения, а системный предлагает сохранить себе', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    await showWorkbench(page)
    await page.getByRole('button', { name: 'Мой фильтр', exact: true }).click()
    await showWorkbench(page)
    const editor = page.getByRole('region', { name: 'Редактор фильтра' })
    await expect(editor.getByRole('button', { name: 'Сохранить', exact: true })).toBeDisabled()
    await editor.getByLabel('Название фильтра', { exact: true }).fill('Мой обновлённый фильтр')
    await editor.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect.poll(() => api.filters.find(item => item.id === 'private-1')?.name).toBe('Мой обновлённый фильтр')
    await page.getByRole('group', { name: 'Тип фильтров' }).getByRole('button', { name: 'Системные', exact: true }).click()
    await page.getByRole('button', { name: 'Системный фильтр', exact: true }).click()
    await showWorkbench(page)
    await expect(editor.getByRole('button', { name: 'Сохранить себе', exact: true })).toBeVisible()
    await editor.getByLabel('Название фильтра', { exact: true }).fill('Личная копия')
    await editor.getByRole('button', { name: 'Сохранить себе', exact: true }).click()
    await expect.poll(() => api.filters.find(item => item.name === 'Личная копия')?.filterType).toBe('PRIVATE')
    expect(api.calls.filter(call => call.method === 'PUT' && call.path.endsWith('/system-1'))).toEqual([])
})

test('группа создаётся со значком и редактируется независимо от области диалогов', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    await showWorkbench(page)
    const groups = page.getByRole('region', { name: 'Управление группами' })
    await groups.getByRole('button', { name: '+ Группа', exact: true }).click()
    await groups.getByPlaceholder('Название группы').fill('Новая группа')
    await groups.getByRole('button', { name: 'Значок группы', exact: true }).click()
    await groups.getByRole('button', { name: 'Сердце', exact: true }).click()
    await groups.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect.poll(() => api.groups.find(group => group.id === 'group-2')?.icon).toBe('heart.svg')
    await groups.getByRole('button', { name: 'Изменить', exact: true }).click()
    await groups.getByPlaceholder('Название группы').fill('Переименованная группа')
    await groups.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect.poll(() => api.groups.find(group => group.id === 'group-2')?.name).toBe('Переименованная группа')
    await expect(page).toHaveURL(/tab=mine/)
    await groups.getByRole('button', { name: /^Группы/ }).click()
    await expect(groups.getByRole('button', { name: 'Управлять группой', exact: true })).toBeHidden()
})

test('отказ изменения состава группы обновляет список и оставляет ошибку над ним', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    await expect(page.getByPlaceholder('Введите сообщение...')).toBeVisible()
    await showList(page)
    await page.getByRole('button', { name: 'В группу', exact: true }).click()
    api.rejectMembership = true
    await page.getByRole('checkbox', { name: 'Важные', exact: true }).click()
    await expect(page.locator('.conversation-list-item-row')).toHaveCount(0)
    await expect(page.locator('.conversation-list-header').getByRole('alert')).toHaveText(/Состав группы больше недоступен/)
})

for (const status of [403, 404]) {
    test(`первоначальный ${status} скрывает устаревшую карточку и редактор`, async ({ page, api }) => {
        api.conversationFailure = status
        await page.goto('/inbox?tab=mine')
        await expect(page.getByRole('button', { name: 'Повторить загрузку диалога', exact: true })).toBeVisible()
        await expect(page.getByPlaceholder('Введите сообщение...')).toHaveCount(0)
        await showList(page)
        await expect(page.locator('.conversation-list-item-row')).toHaveCount(0)
        expect(api.searches.length).toBeGreaterThanOrEqual(2)
    })
}

test('временный сбой обновления сохраняет карточки и допускает повтор', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    await expect(page.getByPlaceholder('Введите сообщение...')).toBeVisible()
    await showWorkbench(page)
    api.searchFailure = 503
    await page.getByRole('button', { name: 'Применить', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Сбой обновления списка' })).toBeVisible()
    await expect(page.locator('.conversation-list-item-row')).toHaveCount(1)
    api.searchFailure = null
    await page.getByRole('button', { name: 'Повторить', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Сбой обновления списка' })).toHaveCount(0)
})

test('ошибка отправки сохраняет черновик, повтор очищает его после успеха', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    const draft = page.getByPlaceholder('Введите сообщение...')
    await draft.fill('Тестовый ответ')
    api.failSend = true
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect(page.getByText('Отправка временно недоступна', { exact: true })).toBeVisible()
    await expect(draft).toHaveValue('Тестовый ответ')
    api.failSend = false
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect(draft).toHaveValue('')
    expect(api.calls.filter(call => call.path === '/api/messages/outbound')).toHaveLength(2)
})

test('создание клиента обновляет панель и сохраняет черновик переписки', async ({ page, api }) => {
    await page.goto('/inbox?tab=mine')
    const draft = page.getByPlaceholder('Введите сообщение...')
    await draft.fill('Сохранённый черновик')
    await expect(page.locator('.conversation-list-client-link')).toHaveText('Без клиента')
    const panel = page.locator('.conversation-client-panel')
    await panel.getByRole('button', { name: 'Создать клиента', exact: true }).click()
    await panel.getByLabel('Имя', { exact: true }).fill('Проверка')
    await panel.getByLabel('Фамилия', { exact: true }).fill('Клиент')
    await panel.getByRole('button', { name: 'Создать и привязать', exact: true }).click()
    await expect(panel.getByText('Проверка Клиент', { exact: true })).toBeVisible()
    await expect(draft).toHaveValue('Сохранённый черновик')
    await expect(page.locator('.conversation-list-client-link')).toHaveText('Клиент есть')
    expect(api.calls.filter(call => call.path === '/api/clients/from-conversation/conversation-1')).toHaveLength(1)
})

test.describe('быстрые периоды активности', () => {
    test.use({ timezoneId: 'Europe/Moscow' })

    test('выбор, применение, сохранение и ручное изменение периода', async ({ page, api }) => {
        await page.clock.setFixedTime(new Date('2026-10-19T12:00:00Z'))
        await page.goto('/inbox?tab=mine')
        await showWorkbench(page)
        const editor = page.getByRole('region', { name: 'Редактор фильтра' })
        await page.getByRole('button', { name: '+ Фильтр', exact: true }).click()
        for (const [label, start] of [
            ['Сегодня', '2026-10-19'], ['Последние 7 дней', '2026-10-13'], ['Последние 30 дней', '2026-09-20'],
        ]) {
            const searches = api.searches.length
            await editor.getByRole('button', { name: label, exact: true }).click()
            await expect(editor.getByLabel('Активность с', { exact: true })).toHaveValue(start)
            await expect(editor.getByLabel('Активность по', { exact: true })).toHaveValue('2026-10-19')
            expect(api.searches).toHaveLength(searches)
            await editor.getByRole('button', { name: 'Применить', exact: true }).click()
            const from = new Date(`${start}T00:00:00+03:00`).toISOString()
            await expect.poll(() => api.searches.at(-1)?.activityFrom).toBe(from)
            expect(api.searches.at(-1)?.activityTo).toBe('2026-10-19T20:59:59.999999Z')
            await showWorkbench(page)
        }
        const applied = api.searches.at(-1)!
        await editor.getByLabel('Название фильтра', { exact: true }).fill('Период активности')
        await editor.getByRole('button', { name: 'Сохранить', exact: true }).click()
        await expect.poll(() => api.filters.find(filter => filter.name === 'Период активности')?.filterDefinition.activityFrom).toBe(applied.activityFrom)
        expect(api.filters.find(filter => filter.name === 'Период активности')?.filterDefinition.activityTo).toBe(applied.activityTo)
        await editor.getByRole('button', { name: 'Сбросить', exact: true }).click()
        await expect.poll(() => api.searches.at(-1)?.activityFrom).toBeNull()
        await expect(editor.getByLabel('Активность с', { exact: true })).toHaveValue('')
        await page.getByRole('button', { name: 'Период активности', exact: true }).click()
        await expect.poll(() => api.searches.at(-1)?.activityFrom).toBe(applied.activityFrom)
        await showWorkbench(page)
        await editor.getByLabel('Активность с', { exact: true }).fill('2026-10-01')
        await editor.getByLabel('Активность по', { exact: true }).fill('2026-10-05')
        await editor.getByRole('button', { name: 'Применить', exact: true }).click()
        await expect.poll(() => api.searches.at(-1)?.activityFrom).toBe('2026-09-30T21:00:00.000Z')
        expect(api.searches.at(-1)?.activityTo).toBe('2026-10-05T20:59:59.999999Z')
    })
})

for (const role of ['employee', 'supervisor', 'admin'] as const) {
    test(`роль ${role}: видимость действий и областей`, async ({ page, api }) => {
        api.role = role
        await page.goto('/inbox')
        await expect(page.locator('.conversation-header')).toBeVisible()
        if (role === 'employee') {
            await expect(page.locator('.employee-assignment-trigger')).toHaveCount(0)
            await expect(page.getByRole('button', { name: 'Освободить', exact: true })).toBeVisible()
        } else {
            await expect(page.locator('.employee-assignment-trigger')).toBeVisible()
        }
        if (role === 'admin') await expect(page.getByPlaceholder('Введите сообщение...')).toHaveCount(0)
        await showList(page)
        const all = page.getByRole('navigation', { name: 'Область диалогов' }).getByRole('button', { name: 'Все', exact: true })
        if (role === 'employee') await expect(all).toHaveCount(0)
        else await expect(all).toBeVisible()
        await expect(page.locator('.app-user-role')).toHaveText(role === 'admin' ? 'Администратор' : role === 'supervisor' ? 'Супервизор' : 'Сотрудник')
    })
}

test('редактор помещается на экране, меню закрывается клавиатурой, черновик сохраняется', async ({ page }) => {
    await page.goto('/inbox?tab=mine')
    const draft = page.getByPlaceholder('Введите сообщение...')
    await draft.fill('Черновик при смене области')
    await showWorkbench(page)
    const apply = page.getByRole('button', { name: 'Применить', exact: true })
    await expect(apply).toBeInViewport()
    const editor = page.getByRole('region', { name: 'Редактор фильтра' })
    const box = await editor.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height + 1)
    const dialog = page.getByRole('dialog', { name: 'Меню Inbox' })
    if (await dialog.isVisible()) {
        await page.keyboard.press('Escape')
        await expect(dialog).toBeHidden()
        await expect(page.getByRole('button', { name: 'Меню Inbox', exact: true })).toBeFocused()
    }
    await page.locator('a.conversation-list-item').first().click()
    await expect(draft).toHaveValue('Черновик при смене области')
    const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
    expect(horizontalOverflow).toBe(false)
})
