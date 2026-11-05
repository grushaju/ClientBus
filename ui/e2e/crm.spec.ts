import { test, expect } from './fixtures/inbox'
import type { Page } from '@playwright/test'
import type { CrmConnection, CrmProject, CrmMaster, CrmSyncState } from '../src/api/crmApi'

test('Настройки: профиль и длинный список CRM прокручиваются до нижних действий', async ({ page, api }) => {
    api.role = 'admin'
    const fixture = await installCrm(page)
    for (let i = 1; i <= 15; i++) fixture.connections.push({ ...fixture.connections[0], id: 'scroll-account-' + i, displayName: 'Аккаунт ' + i })
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 600 })
    for (const path of ['/settings', '/settings?section=crm']) {
        await page.goto(path)
        const container = page.locator('.app-main')
        const bottom = path.includes('crm') ? page.locator('.crm-connection').last() : page.locator('.settings-actions').last()
        await expect(bottom).toBeAttached()
        await expect.poll(() => container.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
        const box = (await container.boundingBox())!
        await page.mouse.move(box.x + box.width / 2, box.y + Math.min(200, box.height / 2))
        await page.mouse.wheel(0, 20000)
        await expect.poll(() => container.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
        await expect(bottom).toBeInViewport()
        expect(await container.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    }
})

async function installCrm(page: Page, existing = true) {
    const connections: CrmConnection[] = existing ? [{ id: 'crm-connection', provider: 'DIKIDI', displayName: 'Основной аккаунт', loginHint: '••••0000',
        credentialConfigured: true, status: 'CONNECTED', lastCheckedAt: null, errorCode: null, version: 0 }] : []
    let project: CrmProject | null = existing ? makeProject() : null
    let state: CrmSyncState = { dataset: 'CATALOG', status: 'SUCCEEDED', lastAttemptAt: '2026-10-28T10:00:00Z', lastSuccessAt: '2026-10-28T10:00:00Z',
        freshness: 'CURRENT', completeness: 'COMPLETE', errorCode: null, generation: 1 }
    const masters = ['Один', 'Два', 'Три'].map((name, index): CrmMaster => ({ id: 'crm-master-' + index,
        projectId: 'crm-project', externalId: 'master-' + index, displayName: 'Мастер ' + name, employeeId: null,
        mappingUpdatedAt: null, mappingUpdatedBy: null, mappingPreviousId: null, mappingReason: null,
        lastObservedAt: '2026-10-28T10:00:00Z', version: 0 }))
    const counters = { creates: 0, checks: 0, bindings: 0, links: 0, credentialReads: 0, refreshes: 0, candidates: 0, deletions: 0, unknown: [] as string[] }
    const employee = { id: 'employee-2', organizationId: 'organization-1', username: 'crm-employee', firstName: 'Анна', lastName: 'Иванова', email: 'other@example.test', enabled: true, role: 'EMPLOYEE' }
    function makeProject(): CrmProject {
        return { id: 'crm-project', workspaceId: 'workspace-1', connectionId: 'crm-connection', provider: 'DIKIDI', externalId: '100', displayName: 'Салон №17',
            currency: 'RUB', zoneId: 'Europe/Moscow', zoneSource: 'MANUAL', zoneRevision: 1, contextVersion: 1, enabled: true, connectionState: 'CONNECTED', version: 0,
            capabilities: [{ code: 'READ_SERVICES', availability: 'AVAILABLE', reasonCode: null }] }
    }
    const services = ['Стрижка', 'Окрашивание', 'Укладка'].map((name, i) => ({ id: 's' + i, projectId: 'crm-project', externalId: String(i), displayName: name, category: 'Волосы',
        offers: [{ id: 'o' + i, serviceId: 's' + i, masterId: masters[i].id, masterDisplayName: masters[i].displayName, externalMasterId: masters[i].externalId,
            price: String(1500 + i * 500) + '.0000', currency: 'RUB', durationSeconds: 1800, offerState: { active: true, online: true }, attributes: {}, warnings: [] }] }))
    await page.route('**/api/**', async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
        const body = request.postData() ? request.postDataJSON() : null
        const reply = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
        if (path === '/api/workspaces/workspace-1') return reply({ id: 'workspace-1', organizationId: 'organization-1', name: 'Тестовый Workspace', avatarUrl: null, createdAt: '2026-10-28T10:00:00Z', updatedAt: '2026-10-28T10:00:00Z' })
        if (path === '/api/workspaces/workspace-1/memberships' || path === '/api/conversations/workspace/workspace-1') return reply([])
        if (path === '/api/employees/organization/organization-1') return reply([employee])
        if (!path.startsWith('/api/crm/')) return route.fallback()
        if (path === '/api/crm/types') return reply(['DIKIDI'])
        if (path === '/api/crm/connections') {
            if (method === 'GET') { counters.credentialReads++; return reply(connections) }
            expect(body.provider).toBe('DIKIDI'); expect(body.secret).toBe('test-only-password'); counters.creates++
            connections.push({ id: 'crm-connection', provider: 'DIKIDI', displayName: body.displayName, loginHint: '••••0000', credentialConfigured: true, status: 'CONFIGURED', lastCheckedAt: null, errorCode: null, version: 0 })
            return reply(connections[0], 201)
        }
        if (path === '/api/crm/projects') return reply(project ? [project] : [])
        if (path === '/api/crm/connections/crm-connection' && method === 'DELETE') {
            expect(Number(url.searchParams.get('expectedVersion'))).toBe(connections[0].version)
            if (project?.connectionId === connections[0].id) return reply({ code: 'CRM_CONNECTION_IN_USE', message: 'К аккаунту привязаны проекты.' },409)
            connections.splice(0,1); counters.deletions++; return route.fulfill({ status: 204 })
        }
        if (path === '/api/crm/connections/crm-connection/verify') { connections[0].status = 'CONNECTED'; connections[0].version++; return reply(connections[0]) }
        if (path === '/api/crm/connections/crm-connection' && method === 'PATCH') {
            expect(body.expectedVersion).toBe(connections[0].version)
            if (body.displayName !== undefined) connections[0].displayName = body.displayName
            if (body.enabled !== undefined) connections[0].status = body.enabled ? 'CONFIGURED' : 'DISABLED'
            if (body.secret) connections[0].status = 'CONFIGURED'
            if (project) project.connectionState = connections[0].status
            connections[0].version++; return reply(connections[0])
        }
        if (path === '/api/crm/connections/crm-connection/project-checks') { counters.checks++; return reply({ externalId: body.externalProjectId, displayName: 'Салон №17', currency: 'RUB', zoneId: null }) }
        if (path === '/api/crm/workspaces/workspace-1/projects') {
            if (method === 'GET') return reply(project ? [project] : [])
            counters.bindings++; expect(body.externalProjectId).toBe('100'); project = makeProject(); project.zoneId = body.zoneId ?? null; return reply(project, 201)
        }
        if (path === '/api/crm/projects/crm-project' && method === 'PATCH') { Object.assign(project!, body); project!.version++; return reply(project) }
        if (path === '/api/crm/projects/crm-project/masters') {
            const q = url.searchParams.get('q')?.toLocaleLowerCase()
            return reply({ items: masters.filter(row => (!q || row.displayName.toLocaleLowerCase().includes(q)) && (url.searchParams.get('unlinked') !== 'true' || !row.employeeId)), nextCursor: null, hasMore: false, dataState: state, revision: 1 })
        }
        if (path === '/api/crm/projects/crm-project/services') {
            const q = url.searchParams.get('q')?.toLocaleLowerCase(), masterId = url.searchParams.get('masterId')
            return reply({ items: services.filter(row => (!q || row.displayName.toLocaleLowerCase().includes(q)) && (!masterId || row.offers.some(offer => offer.masterId === masterId))), nextCursor: null, hasMore: false, dataState: state, revision: 1 })
        }
        if (path === '/api/crm/projects/crm-project/employee-candidates') { counters.candidates++; return reply([{ id: employee.id, displayName: 'Анна Иванова', username: 'anna.one', enabled: true }, { id: 'employee-3', displayName: 'Анна Иванова', username: 'anna.two', enabled: true }]) }
        const master = masters.find(row => path === '/api/crm/masters/' + row.id || path === '/api/crm/masters/' + row.id + '/employee-link')
        if (master) {
            if (method === 'GET') return reply(master)
            expect(method === 'DELETE' ? Number(url.searchParams.get('expectedVersion')) : body.expectedVersion).toBe(master.version)
            counters.links++; master.employeeId = method === 'DELETE' ? null : body.employeeId; master.version++; return reply(master)
        }
        if (path === '/api/crm/projects/crm-project/refresh') { expect(body.dataset).toBe('CATALOG'); counters.refreshes++;
            state = { ...state, status: 'FAILED', freshness: 'STALE', errorCode: 'CRM_TEMPORARILY_UNAVAILABLE' }; return reply(state, 202) }
        counters.unknown.push(method + ' ' + path); return reply({ code: 'TEST_ERROR', message: 'Unexpected CRM request' }, 501)
    })
    return { counters, connections, masters, services, get project() { return project } }
}

test('CRM: администратор удаляет неиспользуемый аккаунт после подтверждения', async ({ page, api }) => {
    api.role = 'admin'; const fixture = await installCrm(page, false)
    fixture.connections.push({ id: 'crm-connection', provider: 'DIKIDI', displayName: 'Неиспользуемый аккаунт', loginHint: '••••0000',
        credentialConfigured: true, status: 'DISABLED', lastCheckedAt: null, errorCode: null, version: 2 })
    await page.goto('/settings?section=crm')
    const settings = page.getByRole('region', { name: 'Подключения CRM', exact: true })
    await expect(settings.getByRole('button', { name: 'Удалить', exact: true })).toBeEnabled()
    await settings.getByRole('button', { name: 'Удалить', exact: true }).click()
    expect(fixture.counters.deletions).toBe(0)
    await settings.getByRole('button', { name: 'Отмена', exact: true }).click()
    await expect(settings.getByRole('button', { name: 'Подтвердить удаление' })).toHaveCount(0)
    await settings.getByRole('button', { name: 'Удалить', exact: true }).click()
    await settings.getByRole('button', { name: 'Подтвердить удаление' }).click()
    await expect(settings.getByText('Аккаунт CRM удалён.', { exact: true })).toBeVisible()
    await expect(settings.locator('.crm-connection')).toHaveCount(0)
    expect(fixture.counters.deletions).toBe(1); expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('CRM: администратор добавляет аккаунт, подключает проект и меняет связь в рабочем разделе', async ({ page, api }, info) => {
    api.role = 'admin'; const fixture = await installCrm(page, false)
    await page.goto('/settings?section=crm'); await page.getByRole('button', { name: 'Добавить аккаунт', exact: true }).click()
    await expect(page.getByLabel('Тип CRM').locator('option')).toHaveText(['DIKIDI'])
    await page.getByLabel('Название аккаунта').fill('Основной аккаунт'); await page.getByLabel('Телефон DIKIDI').fill('70000000000'); await page.getByLabel('Пароль DIKIDI').fill('test-only-password')
    await page.getByRole('button', { name: 'Добавить подключение', exact: true }).click()
    await expect(page.getByLabel('Пароль DIKIDI')).toHaveCount(0)
    await page.getByRole('button', { name: 'Проверить', exact: true }).click(); await expect(page.locator('.crm-state')).toHaveText('Подключено')
    await page.getByLabel('Рабочее пространство', { exact: true }).selectOption('workspace-1'); await page.getByRole('link', { name: 'Подключить проект', exact: true }).click()
    const settings = page.getByRole('region', { name: 'Настройки CRM рабочего пространства', exact: true })
    await expect(settings.getByLabel('Подключение DIKIDI')).toHaveValue('crm-connection')
    await settings.getByLabel('ID проекта DIKIDI').fill('100'); await settings.getByRole('button', { name: 'Проверить проект', exact: true }).click()
    expect(fixture.counters.bindings).toBe(0); await settings.getByLabel('Часовой пояс проекта').selectOption('Europe/Moscow')
    await settings.getByRole('button', { name: 'Привязать проект', exact: true }).click(); await settings.getByRole('link', { name: 'Открыть каталог' }).click()
    await expect(page.getByText('Стрижка', { exact: true })).toBeVisible(); await page.getByRole('tab', { name: 'Мастера', exact: true }).click()
    await page.getByRole('button', { name: 'Связи с сотрудниками', exact: true }).click()
    const select = page.getByLabel('Сотрудник: Мастер Один', { exact: true }); await expect(select).toBeEnabled(); await select.focus(); await select.selectOption('employee-2'); await expect(select).toHaveValue('employee-2')
    await expect(select).toBeFocused(); await page.getByRole('button', { name: 'Отменить изменение' }).click(); await expect(select).toHaveValue('')
    expect(fixture.counters.links).toBe(2); expect(fixture.counters.creates).toBe(1); expect(fixture.counters.bindings).toBe(1); expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    await page.screenshot({ path: info.outputPath('crm-mapping.png'), fullPage: true })
})
test('CRM: сотрудник работает с услугами и карточкой мастера без административных элементов', async ({ page, api }, info) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm'); await expect(page.getByRole('tab', { name: 'Услуги', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByText('Стрижка', { exact: true })).toBeVisible(); await expect(page.getByText('1500 RUB', { exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('crm-services.png'), fullPage: true })
    await page.getByRole('button', { name: 'Мастер Один', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: 'Мастер Один' }); await expect(drawer.getByText('Стрижка', { exact: true })).toBeVisible()
    await expect(drawer.getByText('Окрашивание', { exact: true })).toHaveCount(0); await page.keyboard.press('Escape')
    await expect(page.getByRole('button', { name: 'Мастер Один', exact: true })).toBeFocused()
    await page.getByLabel('Найти услугу').fill('Окрашивание'); await expect(page.locator('.crm-service')).toHaveCount(1)
    await page.getByLabel('Найти услугу').fill(''); await page.getByRole('button', { name: 'Мастер: все' }).click()
    await page.getByLabel('Найти мастера', { exact: true }).fill('Два'); await page.locator('.crm-picker').getByRole('button', { name: /Мастер Два/ }).click()
    await expect(page.locator('.crm-service')).toHaveCount(1); await expect(page.getByText('Окрашивание', { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: 'Услуги', exact: true }).focus(); await page.keyboard.press('ArrowRight')
    await expect(page.getByRole('tab', { name: 'Мастера', exact: true })).toBeFocused()
    await expect(page.getByRole('button', { name: 'Связи с сотрудниками' })).toHaveCount(0)
    await expect(page.getByText('Без связи с сотрудником', { exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: 'Обновить', exact: true }).click(); await expect(page.getByText(/Обновление не удалось/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Мастер Один', exact: true })).toBeVisible()
    await page.goto('/crm'); await expect(page.getByRole('tab', { name: 'Мастера', exact: true })).toHaveAttribute('aria-selected', 'true')
    expect(fixture.counters.credentialReads).toBe(0); expect(fixture.counters.candidates).toBe(0); expect(fixture.counters.refreshes).toBe(1); expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})
test('CRM: супервизор видит связи только в управляемом пространстве', async ({ page, api }) => {
    api.role = 'supervisor'; const fixture = await installCrm(page)
    const second = { id: 'workspace-2', organizationId: 'organization-1', name: 'Второй Workspace', avatarUrl: null }
    await page.route('**/api/workspaces/my', route => route.fulfill({ json: [{ ...second, id: 'workspace-1', name: 'Первый Workspace' }, second] }))
    await page.route('**/api/employees/me/workspaces', route => route.fulfill({ json: [{ employeeId: 'employee-1', workspaceId: 'workspace-1', isSupervisor: true }, { employeeId: 'employee-1', workspaceId: 'workspace-2', isSupervisor: false }] }))
    await page.route('**/api/crm/workspaces/workspace-2/projects', route => route.fulfill({ json: [{ ...fixture.project, workspaceId: 'workspace-2' }] }))
    await page.goto('/crm?tab=masters'); await page.getByRole('button', { name: 'Связи с сотрудниками', exact: true }).click()
    await expect(page.getByLabel('Сотрудник: Мастер Один', { exact: true })).toBeVisible()
    await page.locator('.workspace-switcher-trigger').click(); await page.locator('.workspace-switcher-option').filter({ hasText: 'Второй Workspace' }).click()
    await expect(page.getByRole('button', { name: 'Связи с сотрудниками', exact: true })).toHaveCount(0)
    await expect(page.getByLabel('Сотрудник: Мастер Один', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Мастер Один', exact: true })).toBeVisible()
    expect(fixture.counters.credentialReads).toBe(0)
})
test('CRM: переименование и восстановление аккаунта возвращают в исходный проект', async ({ page, api }) => {
    api.role = 'admin'; const fixture = await installCrm(page)
    fixture.connections[0].status = 'DISABLED'; fixture.project!.connectionState = 'DISABLED'
    await page.goto('/crm?tab=masters'); await page.getByRole('link', { name: 'Включить аккаунт', exact: true }).click()
    await page.getByRole('button', { name: 'Включить аккаунт', exact: true }).click(); await page.getByRole('button', { name: 'Изменить данные', exact: true }).click()
    await page.getByLabel('Название аккаунта').fill('Новый псевдоним'); await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByText('Новый псевдоним', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'Вернуться в CRM', exact: true }).click()
    await expect(page.getByRole('tab', { name: 'Мастера', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('button', { name: 'Мастер Один', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Включить аккаунт', exact: true })).toHaveCount(0)
    expect(fixture.counters.unknown).toEqual([])
})
test('CRM: 409 перезапускает страницы и снимает устаревшую ошибку', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    let generation = 1
    await page.route('**/api/crm/projects/crm-project/masters**', route => {
        const cursor = new URL(route.request().url()).searchParams.get('cursor')
        if (cursor && generation === 1) { generation = 2; return route.fulfill({ status: 409, json: { code: 'CRM_CONTEXT_CHANGED', message: 'Старый каталог' } }) }
        return route.fulfill({ json: { items: [{ ...fixture.masters[0], id: cursor ? 'next' : 'first', displayName: cursor ? 'Второй новый' : generation === 1 ? 'Прежний' : 'Первый новый' }],
            hasMore: !cursor, nextCursor: cursor ? null : 'next-' + generation, revision: generation, dataState: { dataset: 'CATALOG', status: 'SUCCEEDED', freshness: 'CURRENT', generation } } })
    })
    await page.goto('/crm?tab=masters'); await expect(page.getByRole('button', { name: 'Прежний', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Показать ещё мастеров' }).click(); await expect(page.getByRole('button', { name: 'Первый новый', exact: true })).toBeVisible()
    await expect(page.getByText('Старый каталог', { exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: 'Показать ещё мастеров' }).click(); await expect(page.locator('.crm-master')).toHaveCount(2)
    await expect(page.getByRole('button', { name: 'Второй новый', exact: true })).toBeVisible()
})
test('CRM: первый экран содержит рабочие строки на телефоне, планшете и desktop', async ({ page, api }, info) => {
    api.role = 'supervisor'; await installCrm(page)
    const sizes = info.project.name.startsWith('mobile') ? [{ width: 390, height: 844 }] : [{ width: 1366, height: 768 }, { width: 1024, height: 768 }]
    for (const size of sizes) {
        await page.setViewportSize(size); await page.goto('/crm?tab=services')
        await expect(page.locator('.crm-service')).toHaveCount(3)
        const visibleRows = async (selector: string) => page.evaluate(selector => {
            const main = document.querySelector('.app-main')!; main.scrollTop = 0
            const bounds = main.getBoundingClientRect()
            return [...document.querySelectorAll(selector)].map(row => row.getBoundingClientRect()).filter(rect => rect.top >= bounds.top && rect.bottom <= Math.min(innerHeight, bounds.bottom)).length
        }, selector)
        expect(await visibleRows('.crm-service')).toBeGreaterThanOrEqual(size.width <= 760 ? 1 : 3)
        await page.getByRole('tab', { name: 'Мастера', exact: true }).click(); await page.getByRole('button', { name: 'Связи с сотрудниками', exact: true }).click()
        await expect(page.locator('.crm-master')).toHaveCount(3)
        expect(await visibleRows('.crm-master')).toBeGreaterThanOrEqual(size.width <= 760 ? 1 : 3)
        expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
        await page.screenshot({ path: info.outputPath('crm-density-' + size.width + '.png') })
    }
})
