import { test, expect } from './fixtures/inbox'
import type { Page } from '@playwright/test'
import type { CrmConnection, CrmProject, CrmMaster, CrmSyncState, CrmClient, CrmAppointment } from '../src/api/crmApi'
import type {} from '../src/api/events/uiDiagnostics'
import { randomUUID } from 'node:crypto'

test('CRM 12.6.1: обычное и принудительное обновление различаются; 429 сохраняет записи', async ({ page, api }) => {
    const fixture = await installCrm(page); const modes: boolean[] = []
    const retryAt = '2099-11-10T10:00:00Z'
    await page.route('**/api/crm/projects/crm-project/refresh', route => {
        modes.push(route.request().postDataJSON().force === true)
        return route.fulfill({ status: 429, headers: { 'Retry-After': '120' }, json: { code: 'CRM_COOLDOWN', message: 'DIKIDI временно приостановил обращения.', retryAt } })
    })
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await page.getByRole('button', { name: 'Обновить записи периода', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Повторить после' })).toBeVisible()
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await page.getByRole('button', { name: 'Запросить свежие данные', exact: true }).click()
    await expect.poll(() => modes).toEqual([false, true])
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6.2: история только в карточке и читается при раскрытии', async ({ page, api }) => {
    const fixture = await installCrm(page); let reads = 0
    await page.route('**/api/crm/appointments/visit-1/history?*', route => {
        reads++; expect(new URL(route.request().url()).searchParams.get('own')).toBe('true')
        return route.fulfill({ json: { items: [], hasMore: false, nextCursor: null } })
    })
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await expect(page.getByRole('button', { name: '+ Запись', exact: true })).toBeDisabled()
    await expect(page.getByText('Операции с визитами', { exact: true })).toHaveCount(0)
    expect(reads).toBe(0)
    await page.locator('.crm-appointment-row').first().click()
    const dialog = page.getByRole('dialog', { name: 'Запись CRM', exact: true })
    expect(reads).toBe(0)
    await dialog.getByText('История действий', { exact: true }).click()
    await expect(dialog.getByText('Действий в ClientBus пока нет.', { exact: true })).toBeVisible()
    expect(reads).toBeGreaterThan(0)
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6.2: незавершённое создание восстанавливается без локального ключа', async ({ page }) => {
    const fixture = await installCrm(page); let unknown = true, reconciles = 0
    const id = randomUUID(), key = randomUUID()
    const operation = () => ({ id, projectId: 'crm-project', type: 'CREATE', appointmentId: null, initiatorEmployeeId: 'employee-1', idempotencyKey: key,
        contextVersion: 1, zoneRevision: 1, status: unknown ? 'UNKNOWN' : 'CONFIRMED', createdAt: '2026-11-08T09:00:00Z', externalAppointmentId: null })
    await page.route('**/api/crm/projects/crm-project/pending-creations?*', route => route.fulfill({ json: { items: unknown ? [operation()] : [], hasMore: false, nextCursor: null } }))
    await page.route('**/api/crm/operations/' + id + '/reconcile', route => { unknown = false; reconciles++; return route.fulfill({ json: operation() }) })
    await page.goto('/crm?tab=appointments')
    await expect(page.getByRole('region', { name: 'Незавершённое создание записи' })).toBeVisible()
    await page.reload()
    await page.getByRole('button', { name: 'Сверить результат', exact: true }).click()
    await expect(page.getByRole('region', { name: 'Незавершённое создание записи' })).toHaveCount(0)
    expect(reconciles).toBe(1); expect(fixture.counters.creates).toBe(0); expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6.2: ключ прежнего контекста не раскрывает историю и не очищается', async ({ page }) => {
    const fixture = await installCrm(page), key = randomUUID()
    const storageKey = 'clientbus.crm.intent.v1:organization-1:employee-1:crm-project'
    await page.addInitScript(({ storageKey, key }) => localStorage.setItem(storageKey, JSON.stringify({ key, type: 'CREATE' })), { storageKey, key })
    await page.route('**/api/crm/projects/crm-project/operations/by-key/' + key, route => route.fulfill({ status: 409, json: {
        code: 'CRM_CONTEXT_CHANGED', message: 'Действие относится к прежнему подключению проекта. Ключ сохранён.',
    } }))
    await page.goto('/crm?tab=appointments')
    await expect(page.getByRole('alert')).toContainText('прежнему подключению проекта')
    await expect(page.getByText('PRIVATE OLD VISIT')).toHaveCount(0)
    expect(await page.evaluate(value => JSON.parse(localStorage.getItem(value)!).key, storageKey)).toBe(key)
    expect(fixture.counters.creates).toBe(0); expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6.2: супервизор сверяет чужое создание без карточки', async ({ page, api }) => {
    api.role = 'supervisor'; const fixture = await installCrm(page); let pending = true, reads = 0, reconciles = 0
    const id = randomUUID(), operation = { id, projectId: 'crm-project', type: 'CREATE', appointmentId: null, initiatorEmployeeId: 'another-employee',
        idempotencyKey: randomUUID(), contextVersion: 1, zoneRevision: 1, status: 'UNKNOWN', createdAt: '2026-11-08T09:00:00Z' }
    await page.route('**/api/crm/projects/crm-project/pending-creations?*', route => {
        reads++; expect(new URL(route.request().url()).searchParams.get('own')).toBe('false')
        return route.fulfill({ json: { items: pending ? [operation] : [], hasMore: false, nextCursor: null } })
    })
    await page.route('**/api/crm/operations/' + id + '/reconcile', route => { pending = false; reconciles++; return route.fulfill({ json: { ...operation, status: 'CONFIRMED' } }) })
    await page.goto('/crm?tab=appointments')
    await page.getByRole('button', { name: 'Сверить результат', exact: true }).click()
    await expect(page.getByRole('region', { name: 'Незавершённое создание записи' })).toHaveCount(0)
    expect(reads).toBeGreaterThan(1); expect(reconciles).toBe(1); expect(fixture.counters.creates).toBe(0)
})

test('CRM 12.6.2: обновление настроек сохраняет доступ к прежнему незавершённому действию', async ({ page }) => {
    const fixture = await installCrm(page); fixture.project!.contextVersion = 2
    const key = randomUUID(), storageKey = 'clientbus.crm.intent.v1:organization-1:employee-1:crm-project'
    const operation = { id: randomUUID(), projectId: 'crm-project', type: 'CREATE', initiatorEmployeeId: 'employee-1', idempotencyKey: key,
        contextVersion: 1, status: 'UNKNOWN', createdAt: '2026-11-08T09:00:00Z' }
    await page.addInitScript(({ key, storageKey }) => localStorage.setItem(storageKey, JSON.stringify({ key, type: 'CREATE' })), { key, storageKey })
    await page.route('**/api/crm/projects/crm-project/operations/by-key/' + key, route => route.fulfill({ json: operation }))
    await page.goto('/crm?tab=appointments')
    await expect(page.getByRole('button', { name: 'Сверить результат', exact: true })).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(await page.evaluate(value => JSON.parse(localStorage.getItem(value)!).key, storageKey)).toBe(key)
})

test('CRM 12.6: потерянный POST сохраняет ключ; reload и сверка не повторяют создание', async ({ page, api }) => {
    const fixture = await installCrm(page); let creates = 0, reconciles = 0, key = '', status = 'UNKNOWN'
    fixture.project!.capabilities.push({ code: 'CREATE_APPOINTMENT', availability: 'AVAILABLE', reasonCode: null })
    fixture.services.forEach(service => service.offers.forEach(offer => Object.assign(offer, { version: 0 })))
    const id = randomUUID(), storageKey = 'clientbus.crm.intent.v1:organization-1:employee-1:crm-project'
    const operation = () => ({ id, projectId: 'crm-project', type: 'CREATE', appointmentId: null, initiatorEmployeeId: 'employee-1', idempotencyKey: key,
        contextVersion: 1, zoneRevision: 1, status, createdAt: '2026-11-08T09:00:00Z', updatedAt: '2026-11-08T09:00:00Z', errorCode: null, externalAppointmentId: null })
    await page.route('**/api/crm/projects/crm-project/pending-creations?*', route => route.fulfill({ json: { items: status === 'UNKNOWN' && key ? [operation()] : [], nextCursor: null, hasMore: false } }))
    await page.route('**/api/crm/projects/crm-project/operations**', route => {
        const url = new URL(route.request().url())
        return route.fulfill({ json: url.pathname.includes('/by-key/') ? operation() : { items: status === 'UNKNOWN' || url.searchParams.get('active') !== 'true' ? [operation()] : [], nextCursor: null, hasMore: false } })
    })
    await page.route('**/api/crm/projects/crm-project/availability', route => route.fulfill({ json: { projectId: 'crm-project', masterId: 'crm-master-0', starts: ['2030-01-10T12:00:00'], zoneId: 'Europe/Moscow', contextVersion: 1, zoneRevision: 1, observedAt: new Date().toISOString() } }))
    await page.route('**/api/crm/projects/crm-project/appointments', async route => {
        if (route.request().method() !== 'POST') return route.fallback()
        creates++; key = route.request().headers()['idempotency-key']
        const stored = await page.evaluate(value => JSON.parse(localStorage.getItem(value)!), storageKey)
        expect(stored.key).toBe(key); expect(Object.keys(stored).sort()).toEqual(['key', 'type'])
        expect(route.request().postDataJSON().offers).toEqual([{ id: 'o0', expectedVersion: 0 }])
        return route.abort('failed')
    })
    await page.route('**/api/crm/operations/' + id + '/reconcile', route => { reconciles++; status = 'CONFIRMED'; return route.fulfill({ json: operation() }) })
    await page.goto('/crm?tab=appointments')
    await page.getByRole('button', { name: '+ Запись', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Создать запись CRM', exact: true })
    await dialog.getByRole('button', { name: 'Мастер Выберите мастера', exact: true }).click()
    await dialog.getByRole('option', { name: 'Мастер Один', exact: true }).click()
    await dialog.getByRole('button', { name: 'Клиент CRM Выберите клиента CRM', exact: true }).click()
    await dialog.getByRole('option', { name: 'Иван CRM', exact: true }).click()
    await dialog.getByRole('checkbox', { name: /Стрижка/ }).check()
    await selectCrmWorkingTime(dialog)
    await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeEnabled()
    await dialog.getByRole('button', { name: 'Создать запись', exact: true }).click()
    await expect(dialog.getByRole('alert')).toContainText('При потере ответа проверьте незавершённое действие или историю записи.')
    await page.reload()
    await expect(page.locator('.crm-operation')).toContainText('Результат неизвестен')
    expect(creates).toBe(1)
    await page.getByRole('button', { name: 'Сверить результат', exact: true }).click()
    await expect.poll(() => page.evaluate(value => localStorage.getItem(value), storageKey)).toBe(null)
    expect(creates).toBe(1); expect(reconciles).toBe(1); expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6: lookup 404 сохраняет намерение и не отправляет новую команду', async ({ page, api }) => {
    const fixture = await installCrm(page); const key = randomUUID(); let lookups = 0
    const storageKey = 'clientbus.crm.intent.v1:organization-1:employee-1:crm-project'
    await page.addInitScript(({ storageKey, key }) => localStorage.setItem(storageKey, JSON.stringify({ key, type: 'CREATE' })), { storageKey, key })
    await page.route('**/api/crm/projects/crm-project/operations?*', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null } }))
    await page.route('**/api/crm/projects/crm-project/operations/by-key/' + key, route => { lookups++; return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'Not found' } }) })
    await page.goto('/crm?tab=appointments')
    await expect(page.getByText(/Это не подтверждает отказ/)).toBeVisible()
    await page.getByRole('button', { name: 'Проверить результат', exact: true }).click()
    await expect.poll(() => lookups).toBeGreaterThan(1)
    expect(await page.evaluate(value => JSON.parse(localStorage.getItem(value)!).key, storageKey)).toBe(key)
    expect(fixture.counters.creates).toBe(0); expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.6: перенос и отмена используют актуальную версию, разные ключи и показывают результат', async ({ page }) => {
    const fixture = await installCrm(page)
    fixture.visits[0].servicesComplete = true
    fixture.visits[0].services = [fixture.visits[0].services[0]]
    fixture.project!.capabilities.push(...['MOVE_APPOINTMENT', 'CANCEL_APPOINTMENT'].map(code => ({ code, availability: 'AVAILABLE' as const, reasonCode: null })))
    const requests: { type: string; key: string; version: number }[] = []
    await page.route('**/api/crm/appointments/visit-1/availability', route => {
        const body = route.request().postDataJSON()
        expect(body.expectedVersion).toBe(fixture.visits[0].version)
        expect(body.offers).toBeUndefined()
        return route.fulfill({ json: { projectId: 'crm-project', masterId: 'crm-master-0', starts: [body.date + 'T14:00:00', body.date + 'T17:59:00'], zoneId: 'Europe/Moscow', contextVersion: 1, zoneRevision: 1, observedAt: new Date().toISOString() } })
    })
    await page.route('**/api/crm/appointments/*/*', route => {
        const type = new URL(route.request().url()).pathname.split('/').pop()!
        if (route.request().method() !== 'POST' || !['move', 'cancel'].includes(type)) return route.fallback()
        const body = route.request().postDataJSON(), row = fixture.visits[0], key = route.request().headers()['idempotency-key']
        expect(body.expectedVersion).toBe(row.version)
        requests.push({ type, key, version: body.expectedVersion }); row.version++
        if (type === 'move') row.localStart = body.localStart
        else { row.appointmentState = 'CANCELLED'; expect(body.reason).toBe('Тест отмены') }
        return route.fulfill({ json: { id: randomUUID(), type: type === 'move' ? 'MOVE' : 'CANCEL', projectId: 'crm-project', initiatorEmployeeId: 'employee-1', idempotencyKey: key, status: 'CONFIRMED' } })
    })
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await page.locator('.crm-appointment-row').first().click()
    const dialog = page.getByRole('dialog', { name: 'Запись CRM', exact: true })
    await dialog.getByRole('button', { name: 'Перенести запись', exact: true }).click()
    await expect(dialog.getByRole('button', { name: 'Подтвердить перенос', exact: true })).toBeDisabled()
    await expect(dialog.locator('input[type=datetime-local]')).toHaveCount(0)
    await dialog.getByLabel('Рабочий интервал', { exact: true }).selectOption('shift-1')
    await expect(dialog.getByLabel('Свободное время', { exact: true })).toBeEnabled()
    await expect(dialog.getByLabel('Свободное время', { exact: true }).locator('option')).toHaveCount(2)
    await dialog.getByLabel('Свободное время', { exact: true }).selectOption('2026-11-08T14:00:00')
    await dialog.getByRole('button', { name: 'Подтвердить перенос', exact: true }).click()
    await expect(dialog.getByRole('status')).toContainText('Операция принята')
    await expect.poll(() => requests.length).toBe(1)
    await expect(dialog.getByText('2026-11-08 14:00', { exact: false }).first()).toBeVisible()
    await dialog.getByRole('button', { name: 'Отменить запись', exact: true }).click()
    await dialog.getByLabel('Комментарий к отмене (сохранится в ClientBus)', { exact: true }).fill('Тест отмены')
    await dialog.getByRole('button', { name: 'Подтвердить отмену', exact: true }).click()
    await expect.poll(() => requests.length).toBe(2)
    await expect(dialog.getByRole('button', { name: 'Отменить запись', exact: true })).toBeDisabled()
    expect(requests.map(row => row.type)).toEqual(['move', 'cancel']); expect(requests[0].key).not.toBe(requests[1].key)
    expect(fixture.counters.unknown).toEqual([])
})

async function enableCrmCreate(page: Page) {
    const fixture = await installCrm(page)
    fixture.project!.capabilities.push({ code: 'CREATE_APPOINTMENT', availability: 'AVAILABLE', reasonCode: null })
    fixture.services.forEach(service => service.offers.forEach(offer => Object.assign(offer, { version: 0 })))
    await page.route('**/api/crm/projects/crm-project/availability', route => route.fulfill({ json: { projectId: 'crm-project', masterId: 'crm-master-0', starts: ['2030-01-10T12:00:00'], zoneId: 'Europe/Moscow', contextVersion: 1, zoneRevision: 1, observedAt: new Date().toISOString() } }))
    return fixture
}
test('CRM 12.6: перенос неполной записи не предлагает ручной обход графика', async ({ page }) => {
    const fixture = await installCrm(page)
    fixture.project!.capabilities.push({ code: 'MOVE_APPOINTMENT', availability: 'AVAILABLE', reasonCode: null })
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await page.locator('.crm-appointment-row').first().click()
    const dialog = page.getByRole('dialog', { name: 'Запись CRM', exact: true })
    await dialog.getByRole('button', { name: 'Перенести запись', exact: true }).click()
    await expect(dialog.getByText('Для выбора свободного времени нужны полные услуги и мастер записи. Обновите запись и каталог мастеров.', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Подтвердить перенос', exact: true })).toBeDisabled()
    await expect(dialog.locator('input[type=datetime-local]')).toHaveCount(0)
    expect(fixture.counters.unknown).toEqual([])
})
async function openCrmCreate(page: Page) {
    await page.getByRole('button', { name: '+ Запись', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Создать запись CRM', exact: true })
    await dialog.getByRole('button', { name: 'Мастер Выберите мастера', exact: true }).click()
    await dialog.getByRole('option', { name: 'Мастер Один', exact: true }).click()
    await dialog.getByRole('button', { name: 'Клиент CRM Выберите клиента CRM', exact: true }).click()
    await dialog.getByRole('option', { name: 'Иван CRM', exact: true }).click()
    await dialog.getByRole('checkbox', { name: /Стрижка/ }).check()
    await selectCrmWorkingTime(dialog)
    await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeEnabled()
    return dialog
}
async function selectCrmWorkingTime(dialog: ReturnType<Page['getByRole']>) {
    await dialog.getByLabel('График с', { exact: true }).fill('2030-01-10')
    await expect(dialog.getByLabel('Рабочий интервал', { exact: true })).toBeEnabled()
    await dialog.getByLabel('Рабочий интервал', { exact: true }).selectOption('shift-1')
    await expect(dialog.getByLabel('Свободное время', { exact: true })).toBeEnabled()
    await dialog.getByLabel('Свободное время', { exact: true }).selectOption('2030-01-10T12:00:00')
}
test('CRM 12.6: время выбирается только в рабочем интервале с учётом длительности услуги', async ({ page }) => {
    const fixture = await enableCrmCreate(page); let availabilityReads = 0
    await page.route('**/api/crm/projects/crm-project/work-intervals?*', route => {
        const date = new URL(route.request().url()).searchParams.get('dateFrom')!
        return route.fulfill({ json: { items: [
            { id: 'shift-1', masterId: 'crm-master-0', localStart: date + 'T09:00:00', localEnd: date + 'T13:00:00', kind: 'WORK', version: 0 },
            { id: 'break-1', masterId: 'crm-master-0', localStart: date + 'T14:00:00', localEnd: date + 'T15:00:00', kind: 'BREAK', version: 0 },
        ], nextCursor: null, hasMore: false, revision: 1, dataState: { freshness: 'CURRENT' } } })
    })
    await page.route('**/api/crm/projects/crm-project/availability', route => {
        availabilityReads++; const date = route.request().postDataJSON().date
        return route.fulfill({ json: { projectId: 'crm-project', masterId: 'crm-master-0', starts: date === '2030-01-10'
            ? ['2030-01-10T08:00:00', '2030-01-10T12:00:00', '2030-01-10T12:59:00', '2030-01-10T13:00:00'] : [],
            zoneId: 'Europe/Moscow', contextVersion: 1, zoneRevision: 1, observedAt: new Date().toISOString() } })
    })
    await page.goto('/crm?tab=appointments'); const dialog = await openCrmCreate(page)
    await expect(dialog.getByRole('button', { name: 'Проверить время', exact: true })).toHaveCount(0)
    await expect(dialog.locator('input[type=datetime-local]')).toHaveCount(0)
    await expect(dialog.getByLabel('Рабочий интервал', { exact: true }).locator('option')).toHaveCount(2)
    await expect(dialog.getByLabel('Свободное время', { exact: true }).locator('option')).toHaveCount(2)
    await dialog.getByLabel('График с', { exact: true }).fill('2030-01-11')
    await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeDisabled()
    await dialog.getByLabel('Рабочий интервал', { exact: true }).selectOption('shift-1')
    await expect(dialog.getByText('В этом рабочем интервале нет доступного времени для выбранных услуг.', { exact: true })).toBeVisible()
    await expect(dialog.getByLabel('Свободное время', { exact: true })).toBeDisabled()
    expect(availabilityReads).toBe(2); expect(fixture.counters.unknown).toEqual([])
})
test('CRM 12.6: ошибка загрузки времени не разрешает создание и допускает повторное чтение', async ({ page }) => {
    await enableCrmCreate(page); let requests = 0
    await page.route('**/api/crm/projects/crm-project/availability', route => {
        requests++
        return requests === 1 ? route.fulfill({ status: 503, json: { code: 'CRM_TEMPORARILY_UNAVAILABLE' } })
            : route.fulfill({ json: { projectId: 'crm-project', masterId: 'crm-master-0', starts: ['2030-01-10T12:00:00'], zoneId: 'Europe/Moscow', contextVersion: 1, zoneRevision: 1, observedAt: new Date().toISOString() } })
    })
    await page.goto('/crm?tab=appointments'); await page.getByRole('button', { name: '+ Запись', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Создать запись CRM', exact: true })
    await dialog.getByRole('button', { name: 'Мастер Выберите мастера', exact: true }).click()
    await dialog.getByRole('option', { name: 'Мастер Один', exact: true }).click()
    await dialog.getByRole('checkbox', { name: /Стрижка/ }).check()
    await dialog.getByLabel('График с', { exact: true }).fill('2030-01-10')
    await dialog.getByLabel('Рабочий интервал', { exact: true }).selectOption('shift-1')
    await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeDisabled()
    await dialog.getByRole('button', { name: 'Повторить загрузку времени', exact: true }).click()
    await expect(dialog.getByLabel('Свободное время', { exact: true })).toBeEnabled()
    expect(requests).toBe(2)
})
test('CRM 12.6: закрытие формы в очереди Web Lock не блокирует следующую запись', async ({ page }) => {
    const fixture = await enableCrmCreate(page); let posts = 0
    const storageKey = 'clientbus.crm.intent.v1:organization-1:employee-1:crm-project'
    await page.route('**/api/crm/projects/crm-project/appointments', route => {
        if (route.request().method() !== 'POST') return route.fallback()
        posts++
        return route.fulfill({ json: { id: randomUUID(), projectId: 'crm-project', initiatorEmployeeId: 'employee-1', type: 'CREATE', idempotencyKey: route.request().headers()['idempotency-key'], status: 'CONFIRMED' } })
    })
    await page.route('**/api/crm/projects/crm-project/operations?*', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null } }))
    await page.goto('/crm?tab=appointments')
    const dialog = await openCrmCreate(page)
    await page.evaluate(key => new Promise<void>(acquired => {
        void navigator.locks.request(key, async () => { acquired(); await new Promise<void>(resolve => window.addEventListener('test:release-crm-lock', () => resolve(), { once: true })) })
    }), storageKey)
    try {
        await dialog.getByRole('button', { name: 'Создать запись', exact: true }).click()
        await expect.poll(() => page.evaluate(async () => (await navigator.locks.query()).pending?.length ?? 0)).toBe(1)
        await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click()
        await expect.poll(() => page.evaluate(async () => (await navigator.locks.query()).pending?.length ?? 0)).toBe(0)
    } finally { await page.evaluate(() => window.dispatchEvent(new Event('test:release-crm-lock'))) }
    expect(posts).toBe(0)
    expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe(null)
    await (await openCrmCreate(page)).getByRole('button', { name: 'Создать запись', exact: true }).click()
    await expect(dialog).toHaveCount(0); expect(posts).toBe(1)
    expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe(null)
    expect(fixture.counters.unknown).toEqual([])
})
for (const mutation of ['удалено', 'выключено', 'без цены и длительности']) {
    test('CRM 12.6: выбранное предложение ' + mutation + ' можно убрать после обновления', async ({ page }) => {
        const fixture = await enableCrmCreate(page)
        await enableCrmPickerEvents(page)
        await page.goto('/crm?tab=appointments')
        const dialog = await openCrmCreate(page)
        await expect.poll(() => page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.transport.state)).toMatch(/^(open|degraded)$/)
        const offer = fixture.services[0].offers[0], original = { ...offer, offerState: { ...offer.offerState } }
        if (mutation === 'удалено') fixture.services[0].offers.splice(0)
        else if (mutation === 'выключено') offer.offerState.active = false
        else Object.assign(offer, { price: null, durationSeconds: null, version: 1 })
        await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
        await expect(dialog.getByRole('alert')).toContainText('Условия выбранных предложений изменились')
        await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeDisabled()
        if (mutation === 'без цены и длительности') {
            const selected = dialog.getByRole('checkbox', { name: /Стрижка/ })
            await expect(selected).toBeEnabled(); await selected.uncheck()
        } else await dialog.getByRole('button', { name: 'Сбросить выбранные услуги', exact: true }).click()
        await expect(dialog.getByText(/^Выбрано:/)).toHaveCount(0)
        await expect(dialog.getByRole('alert')).toHaveCount(0)
        fixture.services[0].offers.splice(0, fixture.services[0].offers.length, original)
        await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
        const available = dialog.getByRole('checkbox', { name: /Стрижка/ })
        await expect(available).toBeEnabled(); await available.check()
        await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeDisabled()
        await selectCrmWorkingTime(dialog)
        await expect(dialog.getByRole('button', { name: 'Создать запись', exact: true })).toBeEnabled()
        expect(await dialog.getByLabel('Свободное время', { exact: true }).inputValue()).toBe('2030-01-10T12:00:00')
        expect(fixture.counters.unknown).toEqual([])
    })
}

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
    const archives: CrmProject[] = []
    const projects = () => [...archives, ...(project ? [project] : [])]
    let state: CrmSyncState = { dataset: 'CATALOG', status: 'SUCCEEDED', lastAttemptAt: '2026-10-28T10:00:00Z', lastSuccessAt: '2026-10-28T10:00:00Z',
        freshness: 'CURRENT', completeness: 'COMPLETE', errorCode: null, generation: 1 }
    const masters = ['Один', 'Два', 'Три'].map((name, index): CrmMaster => ({ id: 'crm-master-' + index,
        projectId: 'crm-project', externalId: 'master-' + index, displayName: 'Мастер ' + name, employeeId: null,
        mappingUpdatedAt: null, mappingUpdatedBy: null, mappingPreviousId: null, mappingReason: null,
        lastObservedAt: '2026-10-28T10:00:00Z', version: 0 }))
    const counters = { creates: 0, checks: 0, bindings: 0, links: 0, credentialReads: 0, refreshes: 0, candidates: 0, deletions: 0, detachments: 0, projectLists: 0, projectReads: 0,
        clientLinks: 0, coreCreates: 0, appointmentReads: 0, intervalReads: 0, financeReads: 0, historyReads: 0, unknown: [] as string[] }
    const crmClients: CrmClient[] = [0,1].map(i => ({ id: 'crm-client-' + i, projectId: 'crm-project', externalId: 'client-' + i, identityKind: 'PROJECT', displayName: i ? 'Другой клиент CRM' : 'Иван CRM',
        contacts: { phone: '70000000001' }, normalizedPhone: '70000000001', clientId: i ? 'client-1' : null,
        mappingUpdatedAt: null, mappingUpdatedBy: null, mappingPreviousId: null, mappingReason: null, lastObservedAt: '2026-11-08T09:00:00Z', version: 0 }))
    const visits: CrmAppointment[] = [{ id: 'visit-1', projectId: 'crm-project', workspaceId: 'workspace-1', externalId: 'visit-external', kind: 'APPOINTMENT', crmClientId: 'crm-client-1', clientId: 'client-1',
        clientIdentityKind: 'PROJECT', externalClientId: 'client-1',
        clientSnapshot: { displayName: 'Иван CRM' }, localStart: '2026-11-08T12:00:00', localEnd: '2026-11-08T13:00:00', zoneId: 'Europe/Moscow', startAt: '2026-11-08T09:00:00Z', endAt: '2026-11-08T10:00:00Z',
        appointmentState: 'SCHEDULED', confirmationState: 'CONFIRMED', attendanceState: 'NOT_RECORDED', paymentState: 'UNKNOWN', comment: 'Комментарий визита',
        services: [{ id: 'visit-service-1', displayName: 'Стрижка', price: '1200.0000', currency: 'RUB', durationSeconds: 1800 }, { id: 'visit-service-2', displayName: 'Укладка', price: null, currency: null, durationSeconds: null }],
        masters: [{ id: 'visit-master-1', masterId: 'crm-master-0', displayName: 'Мастер Один', localStart: null, localEnd: null }], servicesComplete: false, mastersComplete: true,
        observationState: 'OBSERVED', lastObservedAt: '2026-11-08T09:00:00Z', requiresVerification: true, warnings: ['SERVICES_PARTIAL'], version: 0 }]
    const appointmentPage = (items: CrmAppointment[], query: URLSearchParams) => ({ items, nextCursor: null, hasMore: false, revision: 'appointments-1',
        coverage: [{ projectId: 'crm-project', workspaceId: 'workspace-1', scopes: [{ ...state, dataset: 'APPOINTMENTS', completeness: 'UNKNOWN', scope: { dateFrom: query.get('dateFrom'), dateTo: query.get('dateTo') } }] }] })
    const employee = { id: 'employee-2', organizationId: 'organization-1', username: 'crm-employee', firstName: 'Анна', lastName: 'Иванова', email: 'other@example.test', enabled: true, role: 'EMPLOYEE' }
    function makeProject(): CrmProject {
        return { id: 'crm-project', workspaceId: 'workspace-1', connectionId: 'crm-connection', provider: 'DIKIDI', externalId: '100', displayName: 'Салон №17',
            currency: 'RUB', zoneId: 'Europe/Moscow', zoneSource: 'MANUAL', zoneRevision: 1, contextVersion: 1, enabled: true, archived: false, connectionState: 'CONNECTED', version: 0,
            capabilities: ['READ_SERVICES', 'READ_CLIENTS', 'READ_WORK_INTERVALS'].map(code => ({ code, availability: 'AVAILABLE', reasonCode: null })) }
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
        if (path === '/api/clients/client-1/conversations') return reply([])
        if (path === '/api/clients/client-1/crm/appointments') { counters.historyReads++; return reply(appointmentPage(visits, url.searchParams)) }
        if (!path.startsWith('/api/crm/')) return route.fallback()
        if (path === '/api/crm/clientbus-clients/client-1/links') return reply(crmClients.filter(row => row.clientId === 'client-1').map(client => ({ client, project: projects().find(row => row.id === client.projectId) })))
        if (path === '/api/crm/projects/crm-project/appointment-clients') {
            const q = url.searchParams.get('q')?.toLocaleLowerCase() ?? '', exact = url.searchParams.get('externalClientId')
            const clients = new Map<string, { id: string; displayName: string; phone: string | null }>()
            visits.filter(row => row.kind === 'APPOINTMENT' && row.clientIdentityKind === 'PROJECT' && row.externalClientId
                && row.localStart.slice(0, 10) >= url.searchParams.get('dateFrom')! && row.localStart.slice(0, 10) <= url.searchParams.get('dateTo')!)
                .forEach(row => clients.set(row.externalClientId!, { id: row.externalClientId!, displayName: String(row.clientSnapshot.displayName ?? row.clientSnapshot.name ?? 'Клиент #' + row.externalClientId), phone: null }))
            const filtered = [...clients.values()].filter(row => (!exact || row.id === exact) && row.displayName.toLocaleLowerCase().includes(q)).sort((a, b) => a.id.localeCompare(b.id))
            const after = url.searchParams.get('cursor') ? 1 : 0
            return reply({ items: filtered.slice(after, after + 1), nextCursor: filtered.length > after + 1 ? 'next-client' : null, hasMore: filtered.length > after + 1,
                dataState: { ...state, dataset: 'APPOINTMENTS' }, revision: 'period-clients-1' })
        }
        if (path === '/api/crm/projects/crm-project/clients') {
            const q = url.searchParams.get('q')?.toLocaleLowerCase()
            const filtered = crmClients.filter(row => (!q || (row.displayName + ' ' + row.normalizedPhone).toLocaleLowerCase().includes(q)) && (url.searchParams.get('unlinked') !== 'true' || !row.clientId))
            const after = url.searchParams.get('cursor') ? 1 : 0
            return reply({ items: filtered.slice(after, after + 1), nextCursor: filtered.length > after + 1 ? 'next-client' : null, hasMore: filtered.length > after + 1, syncState: { ...state, dataset: 'CLIENTS', completeness: 'UNKNOWN' }, revision: 1 })
        }
        const crmClient = crmClients.find(row => path === '/api/crm/clients/' + row.id || path.startsWith('/api/crm/clients/' + row.id + '/'))
        if (crmClient) {
            if (path.endsWith('/client-candidates')) return reply([{ id: 'client-1', firstName: 'Иван', lastName: 'Клиент', phoneList: ['70000000001'], phoneMatch: true }])
            if (method === 'GET') return reply(crmClient)
            expect(method === 'DELETE' ? Number(url.searchParams.get('expectedVersion')) : body.expectedVersion).toBe(crmClient.version)
            if (path.endsWith('/client')) { counters.coreCreates++; expect(body.firstName).toBeTruthy(); crmClient.clientId = 'client-1' }
            else { counters.clientLinks++; crmClient.clientId = method === 'DELETE' ? null : body.clientId }
            crmClient.version++; return reply(crmClient)
        }
        if (path === '/api/crm/projects/crm-project/appointments') { counters.appointmentReads++; return reply(appointmentPage(visits.filter(row =>
            row.localStart.slice(0, 10) >= url.searchParams.get('dateFrom')! && row.localStart.slice(0, 10) <= url.searchParams.get('dateTo')! &&
            (!url.searchParams.get('externalClientId') || row.externalClientId === url.searchParams.get('externalClientId')) &&
            (!url.searchParams.get('crmClientId') || row.crmClientId === url.searchParams.get('crmClientId')) &&
            (!url.searchParams.get('masterId') || row.masters.some(master => master.masterId === url.searchParams.get('masterId'))) &&
            ['kind', 'appointmentState', 'confirmationState', 'attendanceState', 'paymentState'].every(key => !url.searchParams.get(key) || row[key as keyof CrmAppointment] === url.searchParams.get(key))), url.searchParams)) }
        if (path === '/api/crm/projects/crm-project/pending-creations' || path === '/api/crm/appointments/visit-1/history') return reply({ items: [], nextCursor: null, hasMore: false })
        if (path === '/api/crm/appointments/visit-1') return reply(visits[0])
        if (path === '/api/crm/appointments/visit-1/finance' || path === '/api/crm/appointments/visit-1/finance/refresh') {
            counters.financeReads++; return reply({ appointmentId: 'visit-1', observedAt: '2026-11-08T09:00:00Z', completeness: 'UNKNOWN', sync: { ...state, dataset: 'FINANCE' },
                snapshot: { totals: { cost: '1200.0000', paid: null, debt: null, currency: 'RUB' }, lines: [{ price: '1200', discountPercent: 10, discountAmount: '120', total: '1080' }], payments: [] } })
        }
        if (path === '/api/crm/projects/crm-project/work-intervals') { counters.intervalReads++; return reply({ items: [{ id: 'shift-1', projectId: 'crm-project', masterId: url.searchParams.get('masterId'),
            localStart: url.searchParams.get('dateFrom') + 'T09:00:00', localEnd: url.searchParams.get('dateFrom') + 'T18:00:00', kind: 'WORK', version: 0 }], nextCursor: null, hasMore: false, dataState: { ...state, dataset: 'WORK_INTERVALS', completeness: 'UNKNOWN' }, revision: 1 }) }
        if (path === '/api/crm/types') return reply(['DIKIDI'])
        if (path === '/api/crm/connections') {
            if (method === 'GET') { counters.credentialReads++; return reply(connections) }
            expect(body.provider).toBe('DIKIDI'); expect(body.secret).toBe('test-only-password'); counters.creates++
            connections.push({ id: 'crm-connection', provider: 'DIKIDI', displayName: body.displayName, loginHint: '••••0000', credentialConfigured: true, status: 'CONFIGURED', lastCheckedAt: null, errorCode: null, version: 0 })
            return reply(connections[0], 201)
        }
        if (path === '/api/crm/projects') return reply(projects())
        if (path === '/api/crm/connections/crm-connection' && method === 'DELETE') {
            expect(Number(url.searchParams.get('expectedVersion'))).toBe(connections[0].version)
            if (projects().some(row => row.connectionId === connections[0].id)) return reply({ code: 'CRM_CONNECTION_IN_USE', message: 'К аккаунту привязаны проекты.' },409)
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
        if (path === '/api/crm/connections/crm-connection/projects') {
            counters.projectLists++
            expect(url.searchParams.get('workspaceId')).toBe('workspace-1')
            return reply(['100','200'].filter(id => !projects().some(row => row.externalId === id && !row.archived))
                .map(id => ({ externalId: id, displayName: id === '100' ? 'Салон №17' : 'Другой проект', currency: null, zoneId: null })))
        }
        if (path === '/api/crm/workspaces/workspace-1/projects') {
            if (method === 'GET') { counters.projectReads++; return reply(projects()) }
            counters.bindings++
            const index = archives.findIndex(row => row.externalId === body.externalProjectId)
            if (index >= 0) {
                const archive = archives.splice(index, 1)[0]; expect(body.expectedArchiveVersion).toBe(archive.version)
                project = { ...archive, archived: false, enabled: true, contextVersion: archive.contextVersion + 1, version: archive.version + 1 }
            } else {
                project = { ...makeProject(), externalId: body.externalProjectId,
                    id: body.externalProjectId === '100' ? 'crm-project' : 'crm-project-' + body.externalProjectId }
            }
            project.zoneId = body.zoneId ?? project.zoneId; return reply(project, 201)
        }
        if (path === '/api/crm/projects/crm-project/detach' && method === 'POST') {
            expect(Number(url.searchParams.get('expectedVersion'))).toBe(project!.version)
            const archive = { ...project!, archived: true, enabled: false, contextVersion: project!.contextVersion + 1, version: project!.version + 1 }
            archives.push(archive); project = null; counters.detachments++; return reply(archive)
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
        if (path === '/api/crm/projects/crm-project/refresh') { expect(['CATALOG', 'CLIENTS', 'WORK_INTERVALS', 'APPOINTMENTS', 'CLIENT_HISTORY']).toContain(body.dataset); counters.refreshes++;
            if (body.dataset !== 'CATALOG') return reply({ ...state, dataset: body.dataset, status: 'QUEUED' }, 202)
            state = { ...state, status: 'FAILED', freshness: 'STALE', errorCode: 'CRM_TEMPORARILY_UNAVAILABLE' }; return reply(state, 202) }
        counters.unknown.push(method + ' ' + path); return reply({ code: 'TEST_ERROR', message: 'Unexpected CRM request' }, 501)
    })
    return { counters, connections, masters, services, crmClients, visits, get project() { return project } }
}

test('CRM 12.5: сотрудник ищет клиентов, подтверждает первую связь и не заменяет существующую', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=clients')
    await page.getByRole('button', { name: 'Иван CRM', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Клиент CRM', exact: true })
    await dialog.getByRole('button', { name: 'Выбрать клиента ClientBus' }).click()
    await dialog.getByRole('button', { name: /Иван Клиент.*телефон совпадает/ }).click()
    expect(fixture.counters.clientLinks).toBe(0)
    await expect(dialog.getByText(/Связать с Иван Клиент/)).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('crm-125-client-link.png') })
    await dialog.getByRole('button', { name: 'Подтвердить связь', exact: true }).click()
    await expect(dialog.getByRole('link', { name: 'клиентом ClientBus' })).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Изменить связь' })).toHaveCount(0)
    expect(fixture.counters.clientLinks).toBe(1)
    await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click()
    await page.getByRole('button', { name: 'Показать ещё', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Другой клиент CRM', exact: true })).toBeVisible()
    await page.getByLabel('Найти клиента CRM', { exact: true }).fill('Другой')
    await expect(page.locator('.crm-master')).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Другой клиент CRM', exact: true })).toBeVisible()
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.5: супервизор снимает связь только после подтверждения', async ({ page, api }) => {
    api.role = 'supervisor'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=clients')
    await page.getByRole('button', { name: 'Показать ещё', exact: true }).click()
    await page.getByRole('button', { name: 'Другой клиент CRM', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Клиент CRM', exact: true })
    await dialog.getByRole('button', { name: 'Снять связь', exact: true }).click()
    expect(fixture.counters.clientLinks).toBe(0)
    await dialog.getByRole('button', { name: 'Подтвердить снятие', exact: true }).click()
    await expect(dialog.getByText('Без связи с ClientBus', { exact: true })).toBeVisible()
    expect(fixture.counters.clientLinks).toBe(1); expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.5: создание ClientBus со связью требует ввода данных', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=clients')
    await page.getByRole('button', { name: 'Иван CRM', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Клиент CRM', exact: true })
    await dialog.getByRole('button', { name: 'Создать клиента ClientBus', exact: true }).click()
    await expect(dialog.getByRole('button', { name: 'Создать и связать', exact: true })).toBeDisabled()
    await dialog.getByLabel('Имя', { exact: true }).fill('Подтверждённый')
    await dialog.getByLabel('Фамилия', { exact: true }).fill('Клиент')
    await dialog.getByLabel('Телефоны через запятую').fill('70000000001')
    await dialog.getByRole('button', { name: 'Создать и связать', exact: true }).click()
    await expect(dialog.getByRole('link', { name: 'клиентом ClientBus' })).toBeVisible()
    expect(fixture.counters.coreCreates).toBe(1); expect(fixture.counters.unknown).toEqual([])
})

test('CRM: смена Workspace обновляет URL и не возвращает прежний Workspace при выборе вкладки', async ({ page, api }) => {
    api.role = 'admin'
    const fixture = await installCrm(page)
    const workspaces = [1, 2].map(index => ({ id: 'workspace-' + index, organizationId: 'organization-1', name: 'Workspace ' + index,
        avatarUrl: null, createdAt: '2026-11-08T09:00:00Z', updatedAt: '2026-11-08T09:00:00Z' }))
    const second = { ...fixture.project!, id: 'second-project', workspaceId: 'workspace-2', displayName: 'Другой салон' }
    await page.route('**/api/workspaces/my', route => route.fulfill({ json: workspaces }))
    await page.route('**/api/crm/workspaces/workspace-2/projects', route => route.fulfill({ json: [second] }))
    await page.route('**/api/crm/projects/second-project/**', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null,
        revision: 'second-revision', coverage: [], dataState: { dataset: 'CATALOG', status: 'IDLE', freshness: 'NEVER_LOADED', completeness: 'UNKNOWN' } } }))
    const path = '/crm?workspace=workspace-1&project=crm-project&tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-09&masterId=crm-master-0&externalClientId=client-0'
    await page.goto(path)
    await expect(page.getByRole('tab', { name: 'Записи', exact: true })).toHaveAttribute('aria-selected', 'true')
    await page.locator('.workspace-switcher-trigger').click()
    await page.locator('.workspace-switcher-menu').getByRole('button', { name: 'Workspace 2', exact: false }).click()
    await expect(page).toHaveURL(/workspace=workspace-2/)
    const query = new URL(page.url()).searchParams
    expect(query.get('dateFrom')).toBe('2026-11-08'); expect(query.get('dateTo')).toBe('2026-11-09')
    for (const key of ['project', 'master', 'masterId', 'crmClientId', 'externalClientId']) expect(query.has(key)).toBe(false)
    await expect(page.locator('.crm-catalog-heading')).toContainText('Другой салон')
    await page.getByRole('tab', { name: 'Мастера', exact: true }).click()
    await expect(page.locator('.workspace-switcher-trigger')).toContainText('Workspace 2')
    await expect(page.locator('.crm-catalog-heading')).toContainText('Другой салон')
    await page.goBack()
    await expect(page.getByRole('tab', { name: 'Записи', exact: true })).toHaveAttribute('aria-selected', 'true')
    await page.goBack()
    expect(new URL(page.url()).searchParams.get('externalClientId')).toBe('client-0')
    await expect(page.locator('.workspace-switcher-trigger')).toContainText('Workspace 1')
    await expect(page.locator('.crm-catalog-heading')).toContainText('Салон №17')
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM: порядок вкладок и сохранение фильтров записей между вкладками', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=appointments&workspace=workspace-1&project=crm-project&dateFrom=2026-11-08&dateTo=2026-11-08&appointmentState=SCHEDULED')
    const tabs = page.getByRole('tablist', { name: 'Каталог CRM' })
    await expect(tabs.getByRole('tab')).toHaveText(['Записи', 'Мастера', 'Услуги', 'Клиенты', 'График'])
    await expect(tabs.getByRole('tab', { name: 'Записи', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.sidebar-nav a[href="/appointments"]')).toHaveCount(0)
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await tabs.getByRole('tab', { name: 'Мастера', exact: true }).click()
    await expect(page.locator('.crm-appointment-row')).toHaveCount(0)
    await tabs.getByRole('tab', { name: 'Записи', exact: true }).click()
    await expect(page.getByLabel('С даты', { exact: true })).toHaveValue('2026-11-08')
    await expect(page.getByRole('combobox', { name: 'Состояние визита', exact: true })).toHaveValue('SCHEDULED')
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await page.reload()
    await expect(tabs.getByRole('tab', { name: 'Записи', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await page.screenshot({ path: test.info().outputPath('crm-appointments-tab.png') })
    expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('CRM: шапка и ширина содержимого не сдвигаются между короткими и длинными вкладками', async ({ page, api }) => {
    api.role = 'supervisor'; const fixture = await installCrm(page)
    const visit = fixture.visits[0]
    for (let i = 1; i <= 12; i++) fixture.visits.push({ ...visit, id: 'layout-visit-' + i })
    await page.goto('/crm?tab=masters&dateFrom=2026-11-08&dateTo=2026-11-08')
    await expect(page.locator('.crm-master')).toHaveCount(3)
    const geometry = () => page.evaluate(() => {
        const main = document.querySelector('.app-main')!
        main.scrollTop = 0
        return ['.crm-page', '.crm-page h1', '.crm-catalog-heading', '.crm-tab-heading', '#crm-tabpanel'].map(selector => {
            const rect = document.querySelector(selector)!.getBoundingClientRect()
            return { x: rect.x, y: rect.y, width: rect.width }
        })
    })
    const baseline = await geometry()
    for (const [name, ready] of [['Услуги', '.crm-service'], ['Клиенты', '.crm-master'], ['Записи', '.crm-appointment-row'], ['График', '.crm-directory-trigger'], ['Мастера', '.crm-master']]) {
        await page.getByRole('tab', { name, exact: true }).click()
        await expect(page.locator(ready).first()).toBeVisible()
        await expect.poll(geometry).toEqual(baseline)
        const size = await page.locator('.crm-page').evaluate(element => ({ height: element.getBoundingClientRect().height, available: element.parentElement!.clientHeight }))
        expect(size.height).toBeGreaterThanOrEqual(size.available - 1)
        if (['Записи', 'График'].includes(name)) await page.screenshot({ path: test.info().outputPath('crm-layout-' + (name === 'Записи' ? 'appointments' : 'schedule') + '.png') })
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.5: записи показывают исторические услуги, неизвестную оплату и границы периода', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await page.locator('.crm-appointment-row').click()
    const dialog = page.getByRole('dialog', { name: 'Запись CRM', exact: true })
    await expect(dialog.getByText(/Стрижка.*1200 RUB/)).toBeVisible()
    await expect(dialog.getByText(/Укладка.*Неизвестно/)).toBeVisible()
    expect(fixture.counters.financeReads).toBe(0)
    await dialog.getByRole('button', { name: 'Финансы визита' }).click()
    await expect(dialog.locator('dd').nth(0)).toHaveText('1200 RUB')
    await expect(dialog.locator('dd').nth(1)).toHaveText('Неизвестно')
    await expect(dialog.getByText('Полнота: не подтверждена полностью', { exact: true })).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('crm-125-appointment.png') })
    await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click()
    const reads = fixture.counters.appointmentReads
    await page.getByLabel('По дату', { exact: true }).fill('2026-12-20')
    await expect(page.getByRole('alert')).toContainText('от 1 до 31 дня')
    await expect(page.getByRole('button', { name: 'Обновить записи периода' })).toBeDisabled()
    expect(fixture.counters.appointmentReads).toBe(reads)
    expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('CRM 12.5: клиенты периода доступны из визитов при пустом справочнике CRM', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    fixture.crmClients.splice(0)
    fixture.visits.push({ ...fixture.visits[0], id: 'anonymous', externalClientId: null, clientIdentityKind: null, clientSnapshot: { name: 'Без идентификатора' } },
        { ...fixture.visits[0], id: 'tomorrow', localStart: '2026-11-09T12:00:00', externalClientId: 'client-2', clientSnapshot: { name: 'Завтрашний клиент' } })
    const directories: string[] = []
    page.on('request', request => { if (new URL(request.url()).pathname === '/api/crm/projects/crm-project/clients') directories.push(request.url()) })
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    const trigger = page.getByRole('button', { name: 'Клиент CRM Все клиенты', exact: true })
    await trigger.click()
    const popup = page.getByRole('dialog', { name: 'Выбор клиента CRM' })
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toBeVisible()
    await expect(popup.getByRole('option', { name: 'Без идентификатора', exact: true })).toHaveCount(0)
    await expect(popup.getByRole('option', { name: 'Завтрашний клиент', exact: true })).toHaveCount(0)
    await popup.getByRole('option', { name: 'Иван CRM', exact: true }).click()
    await expect(page).toHaveURL(/externalClientId=client-1/)
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await page.getByLabel('С даты', { exact: true }).fill('2026-11-09')
    await page.getByLabel('По дату', { exact: true }).fill('2026-11-09')
    await page.getByRole('button', { name: 'Клиент CRM Иван CRM', exact: true }).click()
    await expect(popup.getByRole('option', { name: 'Завтрашний клиент', exact: true })).toBeVisible()
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toHaveCount(0)
    await popup.getByRole('option', { name: 'Завтрашний клиент', exact: true }).click()
    await expect(page).toHaveURL(/externalClientId=client-2/)
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    expect(directories).toEqual([]); expect(fixture.counters.unknown).toEqual([])
})

async function enableCrmPickerEvents(page: Page) {
    await page.addInitScript(() => {
        const original = window.fetch.bind(window)
        window.fetch = async (...args) => {
            if (new URL(String(args[0]), location.href).pathname !== '/api/events') return original(...args)
            const body = new ReadableStream<Uint8Array>({ start(controller) {
                const emit = (type: string) => controller.enqueue(new TextEncoder().encode(`event: ${type}\ndata: ${JSON.stringify({
                    eventId: crypto.randomUUID(), schemaVersion: 1, type, occurredAt: new Date().toISOString(), workspaceId: null, resource: null, correlationId: null,
                    stream: type === 'stream.ready' ? { streamId: crypto.randomUUID(), alertsAfter: new Date().toISOString(), heartbeatSeconds: 60 } : null,
                })}\n\n`))
                const invalidate = () => emit('crm.invalidated')
                window.addEventListener('test:crm-picker-update', invalidate)
                args[1]?.signal?.addEventListener('abort', () => { window.removeEventListener('test:crm-picker-update', invalidate); controller.close() }, { once: true })
                emit('stream.ready')
            } })
            return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
        }
    })
}

test('CRM 12.5: обновление периода и нотификации не сдвигают фильтры и не закрывают поиск клиента', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    let empty = false, reads = 0
    await page.route('**/api/crm/projects/crm-project/appointment-clients?*', route => {
        reads++
        const query = new URL(route.request().url()).searchParams.get('q')?.toLocaleLowerCase() ?? ''
        const exact = new URL(route.request().url()).searchParams.get('externalClientId')
        return route.fulfill({ json: { items: empty ? [] : fixture.crmClients.filter(row => (!exact || row.externalId === exact) && row.displayName.toLocaleLowerCase().includes(query)).map(row => ({ id: row.externalId, displayName: row.displayName, phone: null })),
            nextCursor: null, hasMore: false, revision: 1, dataState: { dataset: 'APPOINTMENTS', freshness: 'CURRENT' } } })
    })
    await enableCrmPickerEvents(page)
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    await expect.poll(() => page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.transport.state)).toMatch(/^(open|degraded)$/)
    const trigger = page.getByRole('button', { name: 'Клиент CRM Все клиенты', exact: true })
    const refresh = page.getByRole('button', { name: 'Обновить записи периода' })
    const fields = page.locator('.crm-appointment-filters')
    // Layout offsets exclude the browser's normal scrolling when a mobile field gains focus.
    const layout = () => fields.evaluate(node => [node, ...node.children].map(element => {
        const field = element as HTMLElement
        return { top: field.offsetTop, left: field.offsetLeft, width: field.offsetWidth, height: field.offsetHeight }
    }))
    const before = await layout()
    expect(reads).toBe(0)
    await refresh.click(); await trigger.click()
    const popup = page.getByRole('dialog', { name: 'Выбор клиента CRM' }), search = popup.getByLabel('Поиск клиента CRM')
    await expect(search).toBeFocused(); await search.fill('Иван')
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toBeVisible()
    empty = true
    const previous = reads
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
    await expect.poll(() => reads).toBeGreaterThan(previous)
    await expect(popup.getByText('По запросу ничего не найдено.', { exact: true })).toBeVisible()
    await expect(search).toBeFocused(); await expect(search).toHaveValue('Иван')
    expect(await layout()).toEqual(before)
    empty = false
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
    await popup.getByRole('option', { name: 'Иван CRM', exact: true }).click()
    await expect(page).toHaveURL(/externalClientId=client-0/)
    await expect(popup).toHaveCount(0)
    const selected = page.getByRole('button', { name: 'Клиент CRM Иван CRM', exact: true })
    await expect(selected).toBeFocused()
    await selected.press('ArrowDown'); await expect(search).toBeFocused()
    await search.press('ArrowDown'); await page.keyboard.press('Enter')
    await expect(page).not.toHaveURL(/externalClientId=/)
    await trigger.press('ArrowDown'); await search.press('Escape')
    await expect(trigger).toBeFocused(); await expect(popup).toHaveCount(0)
    expect(await layout()).toEqual(before)
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    expect(fixture.counters.unknown).toEqual([])
    await page.screenshot({ path: test.info().outputPath('crm-directory-stable.png') })
})

test('CRM 12.5: удаление активного варианта возвращает фокус в поиск, Tab выходит из списка', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    fixture.visits.push({ ...fixture.visits[0], id: 'visit-other', externalClientId: 'client-2', clientSnapshot: { name: 'Другой клиент CRM' } })
    await enableCrmPickerEvents(page)
    await page.goto('/crm?tab=appointments&dateFrom=2026-11-08&dateTo=2026-11-08')
    const trigger = page.getByRole('button', { name: 'Клиент CRM Все клиенты', exact: true })
    const nextFilter = page.getByRole('combobox', { name: 'Вид записи', exact: true })
    await trigger.click()
    const popup = page.getByRole('dialog', { name: 'Выбор клиента CRM' }), search = popup.getByLabel('Поиск клиента CRM')
    await popup.getByRole('button', { name: 'Показать ещё' }).click()
    await expect(popup.getByRole('option', { name: 'Другой клиент CRM', exact: true })).toBeVisible()
    await expect(search).toBeFocused()
    await search.press('ArrowDown'); await page.keyboard.press('ArrowDown')
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(popup).toHaveCount(0); await expect(nextFilter).toBeFocused()

    await trigger.click()
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toBeVisible()
    await search.press('ArrowDown'); await page.keyboard.press('ArrowDown')
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toBeFocused()
    fixture.visits.splice(0, 1)
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
    await expect(popup.getByRole('option', { name: 'Иван CRM', exact: true })).toHaveCount(0)
    await expect(search).toBeFocused(); await expect(popup).toBeVisible()
    await search.press('ArrowDown'); await page.keyboard.press('ArrowDown')
    const remaining = popup.getByRole('option', { name: 'Другой клиент CRM', exact: true })
    await expect(remaining).toBeFocused()
    const updated = page.waitForResponse(response => new URL(response.url()).pathname === '/api/crm/projects/crm-project/appointment-clients')
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
    await updated; await expect(remaining).toBeFocused()

    let release!: () => void, started = false
    const gate = new Promise<void>(resolve => { release = resolve })
    await page.route('**/api/crm/projects/crm-project/appointment-clients?*', async route => { started = true; await gate; await route.fallback() })
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-picker-update')))
    await expect.poll(() => started).toBe(true)
    try { await remaining.press('Tab'); await expect(popup).toHaveCount(0) } finally { release() }
    await expect(nextFilter).toBeFocused()
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM 12.5: график показывает интервалы и обновляет только выбранного мастера', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    await page.goto('/crm?tab=schedule&master=crm-master-0')
    await expect(page.getByText('Рабочий интервал', { exact: true })).toBeVisible()
    await expect(page.getByText(/не подтверждают свободное время/)).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('crm-125-schedule.png') })
    expect(fixture.counters.refreshes).toBe(0)
    const sent = page.waitForRequest(request => request.url().endsWith('/crm-project/refresh') && request.method() === 'POST')
    await page.getByRole('button', { name: 'Обновить график', exact: true }).click()
    const payload = (await sent).postDataJSON()
    expect(payload.dataset).toBe('WORK_INTERVALS'); expect(payload.masterId).toBe('crm-master-0')
    expect(payload.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/); expect(payload.dateTo).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(fixture.counters.refreshes).toBe(1); expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('CRM 12.5: история клиента загружается при раскрытии и учитывает выбранный Workspace', async ({ page, api }) => {
    api.role = 'employee'; const fixture = await installCrm(page)
    const upcoming = new Date(Date.now() + 3600000)
    fixture.visits[0].startAt = upcoming.toISOString()
    fixture.visits[0].localStart = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', dateStyle: 'short', timeStyle: 'medium' }).format(upcoming).replace(' ', 'T')
    await page.goto('/clients/client-1')
    expect(fixture.counters.historyReads).toBe(0)
    await page.getByText('CRM и визиты', { exact: true }).click()
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    expect(fixture.counters.historyReads).toBeGreaterThan(0)
    await expect(page.getByLabel('Рабочее пространство истории')).toHaveValue('workspace-1')
    await page.getByRole('button', { name: 'Прошлые визиты', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Прошлые визиты', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.crm-appointment-row')).toHaveCount(0)
    await expect(page.getByText(/В загруженных данных подходящие записи не найдены/)).toBeVisible()
    expect(fixture.counters.unknown).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('CRM 12.5: панель диалога показывает ближайшие визиты только своего Workspace', async ({ page, api }) => {
    api.role = 'employee'; api.account.clientId = 'client-1'
    const fixture = await installCrm(page)
    const upcoming = new Date(Date.now() + 3600000)
    fixture.visits[0].startAt = upcoming.toISOString()
    fixture.visits[0].localStart = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', dateStyle: 'short', timeStyle: 'medium' }).format(upcoming).replace(' ', 'T')
    await page.goto('/inbox/conversation-1')
    const panel = page.locator('.conversation-client-panel')
    await expect(panel.getByText('CRM и визиты', { exact: true })).toBeVisible()
    expect(fixture.counters.historyReads).toBe(0)
    const request = page.waitForRequest(request => request.url().includes('/clients/client-1/crm/appointments'))
    await panel.getByText('CRM и визиты', { exact: true }).click()
    expect(new URL((await request).url()).searchParams.get('workspaceId')).toBe('workspace-1')
    await expect(panel.locator('.crm-appointment-row')).toHaveCount(1)
    await panel.getByRole('link', { name: 'Все визиты во вкладке «Записи»' }).click()
    await expect(page).toHaveURL(/\/crm\?tab=appointments&.*clientId=client-1/)
    await expect(page.locator('.crm-appointment-row')).toHaveCount(1)
    expect(fixture.counters.unknown).toEqual([])
})

test('CRM: health и нотификации сохраняют доступность списка проектов и не повторяют чтение DIKIDI', async ({ page, api }) => {
    api.role = 'admin'; const fixture = await installCrm(page, false)
    fixture.connections.push({ id: 'crm-connection', provider: 'DIKIDI', displayName: 'Основной аккаунт', loginHint: '••••0000',
        credentialConfigured: true, status: 'CONNECTED', lastCheckedAt: null, errorCode: null, version: 0 })
    await page.addInitScript(() => {
        const original = window.fetch.bind(window)
        window.fetch = async (...args) => {
            if (new URL(String(args[0]), location.href).pathname !== '/api/events') return original(...args)
            const body = new ReadableStream<Uint8Array>({ start(controller) {
                const emit = (type: string) => {
                    const envelope = { eventId: crypto.randomUUID(), schemaVersion: 1, type, occurredAt: new Date().toISOString(),
                        workspaceId: null, resource: null, correlationId: null,
                        stream: type === 'stream.ready' ? { streamId: crypto.randomUUID(), alertsAfter: new Date().toISOString(), heartbeatSeconds: 60 } : null }
                    controller.enqueue(new TextEncoder().encode(`event: ${type}\ndata: ${JSON.stringify(envelope)}\n\n`))
                }
                const invalidate = (event: Event) => emit((event as CustomEvent<string>).detail || 'crm.invalidated')
                window.addEventListener('test:crm-health', invalidate)
                args[1]?.signal?.addEventListener('abort', () => {
                    window.removeEventListener('test:crm-health', invalidate); controller.close()
                }, { once: true })
                emit('stream.ready')
            } })
            return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
        }
    })
    await page.goto('/workspaces/workspace-1#crm')
    await expect.poll(() => page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.transport.state)).toMatch(/^(open|degraded)$/)
    const settings = page.getByRole('region', { name: 'Настройки CRM рабочего пространства', exact: true })
    await settings.getByLabel('Подключение DIKIDI').selectOption('crm-connection')
    await settings.getByLabel('Проект DIKIDI').selectOption('100')
    const select = settings.getByLabel('Проект DIKIDI')
    await select.evaluate(node => {
        node.setAttribute('data-test-disabled-changes', '0')
        new MutationObserver(records => node.setAttribute('data-test-disabled-changes', String(Number(node.getAttribute('data-test-disabled-changes')) + records.length)))
            .observe(node, { attributes: true, attributeFilter: ['disabled'] })
    })
    let release!: () => void, started = false
    const gate = new Promise<void>(resolve => { release = resolve })
    await page.route('**/api/crm/workspaces/workspace-1/projects', async route => { started = true; await gate; await route.fallback() })
    await select.focus()
    try {
        await page.evaluate(() => window.dispatchEvent(new Event('test:crm-health')))
        await expect.poll(() => started).toBe(true)
        await expect(settings.getByRole('button', { name: 'Проверить проект', exact: true })).toBeDisabled()
        await expect(select).toBeEnabled(); await expect(select).toBeFocused()
        await expect(select).toHaveAttribute('data-test-disabled-changes', '0')
    } finally { release() }
    await expect(settings.getByRole('button', { name: 'Проверить проект', exact: true })).toBeEnabled()
    await expect(select).toHaveAttribute('data-test-disabled-changes', '0')
    const beforeNotification = fixture.counters.projectReads
    const startedBeforeNotification = await page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.reconciliation.started ?? 0)
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('test:crm-health', { detail: 'notifications.invalidated' })))
    await expect.poll(() => page.evaluate(() => window.clientbusNotificationDiagnostics?.()?.reconciliation.started ?? 0)).toBeGreaterThan(startedBeforeNotification)
    await expect.poll(() => page.evaluate(() => {
        const state = window.clientbusNotificationDiagnostics?.()?.reconciliation
        return state && state.running === 0 && state.dirty === 0
    })).toBe(true)
    expect(fixture.counters.projectReads).toBe(beforeNotification)
    await expect(select).toHaveAttribute('data-test-disabled-changes', '0')
    await settings.getByRole('button', { name: 'Проверить проект', exact: true }).click()
    await expect(settings.getByRole('button', { name: 'Привязать проект', exact: true })).toBeEnabled()
    const reads = fixture.counters.projectReads
    await page.evaluate(() => window.dispatchEvent(new Event('test:crm-health')))
    await expect.poll(() => fixture.counters.projectReads).toBeGreaterThan(reads)
    await expect.poll(() => page.evaluate(() => {
        const state = window.clientbusNotificationDiagnostics?.()?.reconciliation
        return state && state.running === 0 && state.dirty === 0
    })).toBe(true)
    await expect(settings.getByRole('button', { name: 'Привязать проект', exact: true })).toBeEnabled()
    await expect(settings.getByLabel('Проект DIKIDI')).toHaveValue('100')
    expect(fixture.counters.projectLists).toBe(1); expect(fixture.counters.unknown).toEqual([])
})

test('CRM: резервный опрос нотификаций не закрывает список проектов и не опрашивает DIKIDI', async ({ page, api }) => {
    test.setTimeout(45_000)
    api.role = 'admin'; const fixture = await installCrm(page, false)
    fixture.connections.push({ id: 'crm-connection', provider: 'DIKIDI', displayName: 'Основной аккаунт', loginHint: '••••0000',
        credentialConfigured: true, status: 'CONNECTED', lastCheckedAt: null, errorCode: null, version: 0 })
    await page.goto('/workspaces/workspace-1#crm')
    const settings = page.getByRole('region', { name: 'Настройки CRM рабочего пространства', exact: true })
    await settings.getByLabel('Подключение DIKIDI').selectOption('crm-connection')
    const select = settings.getByLabel('Проект DIKIDI')
    await select.selectOption('100')
    await select.evaluate(node => {
        node.setAttribute('data-test-disabled-changes', '0')
        new MutationObserver(records => node.setAttribute('data-test-disabled-changes', String(Number(node.getAttribute('data-test-disabled-changes')) + records.length)))
            .observe(node, { attributes: true, attributeFilter: ['disabled'] })
    })
    let release!: () => void, started = false
    const gate = new Promise<void>(resolve => { release = resolve })
    await page.route('**/api/crm/workspaces/workspace-1/projects', async route => { started = true; await gate; await route.fallback() })
    await select.focus()
    try {
        await expect.poll(() => started, { timeout: 18_000 }).toBe(true)
        await expect(settings.getByRole('button', { name: 'Проверить проект', exact: true })).toBeDisabled()
        await expect(select).toBeEnabled(); await expect(select).toBeFocused()
        await expect(select).toHaveAttribute('data-test-disabled-changes', '0')
        expect(fixture.counters.projectLists).toBe(1)
    } finally { release() }
    await expect(settings.getByRole('button', { name: 'Проверить проект', exact: true })).toBeEnabled()
    await expect(select).toHaveAttribute('data-test-disabled-changes', '0')
    await expect(select).toHaveValue('100')
    expect(fixture.counters.projectLists).toBe(1); expect(fixture.counters.unknown).toEqual([])
})

test('CRM: отвязка сохраняет архив и связи, а восстановление требует подтверждения', async ({ page, api }) => {
    api.role = 'admin'; const fixture = await installCrm(page)
    fixture.masters[0].employeeId = 'employee-2'
    await page.goto('/workspaces/workspace-1#crm')
    const settings = page.getByRole('region', { name: 'Настройки CRM рабочего пространства', exact: true })
    await settings.getByRole('button', { name: 'Отвязать проект', exact: true }).click()
    await settings.getByRole('button', { name: 'Отмена', exact: true }).click()
    expect(fixture.counters.detachments).toBe(0)
    await settings.getByRole('button', { name: 'Отвязать проект', exact: true }).click()
    await settings.getByRole('button', { name: 'Подтвердить отвязку' }).click()
    await expect(settings.getByText(/В архиве\. История/)).toBeVisible()
    await expect(settings.getByRole('button', { name: 'Настроить проект' })).toHaveCount(0)
    await expect(settings.getByLabel('Подключение DIKIDI')).toBeVisible()
    await settings.getByRole('link', { name: 'Открыть каталог' }).click()
    await page.getByRole('tab', { name: 'Услуги', exact: true }).click()
    await expect(page.getByText('Стрижка', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Обновить', exact: true })).toBeDisabled()
    await page.getByRole('tab', { name: 'Мастера', exact: true }).click()
    await page.getByRole('button', { name: 'Связи с сотрудниками', exact: true }).click()
    await expect(page.getByLabel('Сотрудник: Мастер Один', { exact: true })).toBeDisabled()
    await expect(page.getByLabel('Сотрудник: Мастер Один', { exact: true })).toHaveValue('employee-2')
    await page.goto('/workspaces/workspace-1#crm')
    await settings.getByLabel('Подключение DIKIDI').selectOption('crm-connection')
    await settings.getByLabel('Проект DIKIDI').selectOption('100')
    await settings.getByRole('button', { name: 'Проверить проект', exact: true }).click()
    expect(fixture.counters.bindings).toBe(0)
    await settings.getByRole('button', { name: 'Восстановить проект' }).click()
    await expect(settings.getByRole('button', { name: 'Настроить проект' })).toBeVisible()
    expect(fixture.project!.id).toBe('crm-project'); expect(fixture.project!.archived).toBe(false)
    expect(fixture.masters[0].employeeId).toBe('employee-2')
    expect(fixture.counters.detachments).toBe(1); expect(fixture.counters.refreshes).toBe(0); expect(fixture.counters.links).toBe(0)
    expect(fixture.counters.unknown).toEqual([])
})
test('CRM: после отвязки можно подключить другой проект, архив остаётся доступен', async ({ page, api }) => {
    api.role = 'admin'; const fixture = await installCrm(page)
    await page.goto('/workspaces/workspace-1#crm')
    const settings = page.getByRole('region', { name: 'Настройки CRM рабочего пространства', exact: true })
    await settings.getByRole('button', { name: 'Отвязать проект', exact: true }).click()
    await settings.getByRole('button', { name: 'Подтвердить отвязку' }).click()
    await settings.getByLabel('Подключение DIKIDI').selectOption('crm-connection')
    await expect(settings.getByLabel('Проект DIKIDI').locator('option')).toHaveText(['Выберите проект', 'Салон №17 · восстановить', 'Другой проект'])
    await settings.getByLabel('Проект DIKIDI').selectOption('200')
    await settings.getByRole('button', { name: 'Проверить проект', exact: true }).click()
    await settings.getByRole('button', { name: 'Привязать проект', exact: true }).click()
    await expect(settings.locator('.crm-project-card')).toHaveCount(2)
    await expect(settings.getByRole('button', { name: 'Настроить проект' })).toHaveCount(1)
    await expect(settings.getByText(/В архиве\. История/)).toBeVisible()
    expect(fixture.project!.externalId).toBe('200'); expect(fixture.counters.bindings).toBe(1)
    expect(fixture.counters.unknown).toEqual([])
})
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
    await settings.getByLabel('Проект DIKIDI').selectOption('100'); await settings.getByRole('button', { name: 'Проверить проект', exact: true }).click()
    expect(fixture.counters.bindings).toBe(0); await settings.getByLabel('Часовой пояс проекта').selectOption('Europe/Moscow')
    await settings.getByRole('button', { name: 'Привязать проект', exact: true }).click(); await settings.getByRole('link', { name: 'Открыть каталог' }).click()
    await page.getByRole('tab', { name: 'Услуги', exact: true }).click()
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
    await page.goto('/crm'); await expect(page.getByRole('tab', { name: 'Записи', exact: true })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('tab', { name: 'Услуги', exact: true }).click(); await expect(page.getByRole('tab', { name: 'Услуги', exact: true })).toHaveAttribute('aria-selected', 'true')
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
    await page.getByRole('tab', { name: 'Услуги', exact: true }).focus(); await page.keyboard.press('ArrowLeft')
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
