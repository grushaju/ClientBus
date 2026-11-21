const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const scope = load('../../api/events/uiScope.ts')
const state = { dataset: 'CLIENTS', status: 'PARTIAL', freshness: 'CURRENT', completeness: 'UNKNOWN', lastSuccessAt: '2026-11-08T10:00:00Z', generation: 1 }
const project = { id: 'p', workspaceId: 'w', provider: 'DIKIDI', displayName: 'Салон', zoneId: 'Europe/Moscow', archived: false, enabled: true, connectionState: 'CONNECTED', capabilities: [{ code: 'CREATE_CLIENT', availability: 'AVAILABLE' }] }
const client = { id: 'external', projectId: 'p', displayName: 'Внешний клиент', contacts: { phone: '+79991234567' }, externalId: '1', clientId: null, version: 7 }
const visit = { id: 'a', projectId: 'p', workspaceId: 'w', kind: 'APPOINTMENT', clientSnapshot: { displayName: 'Гость' }, localStart: '2026-11-08T12:00:00', localEnd: '2026-11-08T13:00:00', zoneId: 'Europe/Moscow',
    appointmentState: 'SCHEDULED', confirmationState: 'CONFIRMED', attendanceState: 'ARRIVED', paymentState: 'UNKNOWN',
    services: [{ id: 's1', displayName: 'Стрижка', price: '1200.0000', currency: 'RUB', durationSeconds: 1800 }, { id: 's2', displayName: 'Укладка', price: null, currency: null, durationSeconds: null }],
    masters: [{ id: 'm1', displayName: 'Анна' }, { id: 'm2', displayName: 'Ирина' }], servicesComplete: false, mastersComplete: true, warnings: [], requiresVerification: true }
const page = items => ({ items, dataState: state, revision: 1, hasMore: false, nextCursor: null })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const text = ui => JSON.stringify(ui.toJSON())
const button = (ui, name) => ui.root.findAllByType('button').find(row => typeof name === 'string' ? row.children.join('') === name : name.test(row.children.join('')))
const click = (ui, name) => act(async () => { const row = button(ui, name); assert.ok(row, 'Missing button ' + name); row.props.onClick() })
const field = (ui, label) => ui.root.findAllByType('label').find(row => row.children[0] === label).findAll(node => ['input', 'select'].includes(node.type))[0]
const change = (ui, label, value) => act(async () => field(ui, label).props.onChange({ target: { value } }))
async function fixture(t, name, props = {}, overrides = {}, admin = false, intentMocks) {
    const invalidations = new Map(), calls = []
    const api = { crmError: error => error.message,
        getCrmProjects: async () => [],
        getLatestClientProvision: async () => null,
        searchCrmClients: async () => ({ items: [], hasMore: false }),
        getCrmClients: async () => page([client]), getCrmClient: async () => client,
        getCrmClientCandidates: async () => [{ id: 'core', firstName: 'Кандидат', lastName: 'Клиент', phoneList: ['+79991234567'], phoneMatch: true }],
        linkCrmClient: async (row, id) => { calls.push(['link', row.version, id]); return { ...row, clientId: id, version: 8 } },
        createLinkedCrmClient: async (...args) => { calls.push(['create', ...args]); return client },
        getLatestClientProvision: async () => null,
        getCrmMasters: async () => page([{ id: 'master', displayName: 'Анна' }]),
        getCrmMaster: async () => ({ id: 'master', displayName: 'Анна' }),
        getCrmWorkIntervals: async () => page([{ id: 'shift', localStart: '2026-11-08T10:00:00', localEnd: '2026-11-08T18:00:00', kind: 'WORK' }]),
        refreshCrmData: async (...args) => { calls.push(['refresh', ...args]); return state },
        getCrmAppointment: async () => visit, getCrmAppointments: async () => ({ ...page([visit]), coverage: [] }),
        getCrmAppointmentClients: async () => page([{ id: 'client-1', displayName: 'Клиент из визитов', phone: null }]),
        getClientCrmAppointments: async (...args) => { calls.push(['history', ...args]); return { ...page([visit]), coverage: [] } },
        getClientCrmLinks: async (...args) => { calls.push(['links', ...args]); return [{ client: { ...client, clientId: 'core' }, project }] },
        getCrmFinance: async () => { calls.push(['finance']); return { snapshot: null, completeness: 'UNKNOWN', sync: state } },
        refreshCrmFinance: async () => { calls.push(['finance-refresh']); return {} }, ...overrides,
    }
    const auth = { currentEmployee: { id: 'actor', role: admin ? 'SUPER_ADMIN' : 'EMPLOYEE' }, sessionEpoch: 1, isSuperAdmin: admin, employeeWorkspaces: [] }
    const invalidation = { useUiInvalidation: (key, enabled, matches, read, reset) => { if (enabled) invalidations.set(key, { read, reset }) } }
    const mocks = { '../../api/crmApi': api, './InvalidationContext': invalidation, '../../api/events/InvalidationContext': invalidation,
        '../../api/clientApi': { getClient: async id => ({ id, firstName: 'Кандидат', lastName: 'Клиент', phoneList: ['+7 999 123-45-67'], avatarUrl: overrides.sourceAvatar ?? null }), getClientAvatarContent: async () => new Blob(['image'], { type: 'image/png' }) },
        './uiScope': scope, '../../api/events/uiScope': scope,
        '../../auth/AuthContext': { useAuth: () => auth }, '../../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: { id: 'w' }, workspaces: [{ id: 'w', name: 'Рабочее пространство' }] }) },
        'react-router-dom': { Link: props => React.createElement('a', props) },
    }
    if (intentMocks) mocks['./crmIntent'] = intentMocks
    const filename = ['CrmAppointmentHistory', 'CrmOperationsPanel'].includes(name) ? 'CrmOperations' : ['CrmClients', 'CrmClientCard'].includes(name) ? 'CrmClients' : ['CrmAppointmentCard', 'CrmAppointmentRows'].includes(name) ? 'CrmAppointments' : name
    const module = load('../crm/' + filename + '.tsx', mocks), Component = module[name] || module.default
    let ui, attributes = { project, id: 'external', canManage: admin, close() {}, changed() {}, ...props }
    const render = () => React.createElement(Component, attributes)
    await act(async () => { ui = create(render()) })
    t.after(() => act(async () => ui.unmount()))
    return { ui, calls, api, auth,
        read: prefix => invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).read(),
        refresh: prefix => act(async () => invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).read()),
        revoke: prefix => act(async () => invalidations.get([...invalidations.keys()].find(key => key.startsWith(prefix))).reset()),
        update: async value => { attributes = { ...attributes, ...value }; await act(async () => ui.update(render())) },
    }
}

for (const component of ['ClientCrmPanel', 'ClientCrmSection']) {
    for (const unavailable of [{ archived: true }, { enabled: false }, { connectionState: 'REAUTH_REQUIRED' }]) {
        test(`${component} recovers a CREATED client locally when project is ${JSON.stringify(unavailable)}`, async t => {
            let recoveries = 0, searches = 0, creates = 0, completed = false
            const f = await fixture(t, component, { clientId: 'core', workspaceId: 'w', compact: true, phones: [] }, {
                getClientCrmLinks: async () => [],
                getCrmProjects: async () => [{ ...project, ...unavailable }],
                getLatestClientProvision: async () => ({ id: 'saved', status: completed ? 'CONFIRMED' : 'CREATED' }),
                recoverClientProvision: async id => { assert.equal(id, 'saved'); recoveries++; completed = true; return { id, status: 'CONFIRMED' } },
                searchCrmClients: async () => { searches++; throw new Error('external search forbidden') },
                provisionCrmClient: async () => { creates++; throw new Error('external creation forbidden') },
            }, true)
            await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
            assert.match(text(f.ui), /Клиент уже создан в CRM/)
            await click(f.ui, 'Восстановить связь')
            assert.equal(recoveries, 1); assert.equal(searches, 0); assert.equal(creates, 0)
            assert.match(text(f.ui), /Связь CRM восстановлена/)
            assert.equal(button(f.ui, 'Восстановить связь'), undefined)
        })
    }
    test(`${component} does not read recovery operations for an ordinary member`, async t => {
        let reads = 0
        const f = await fixture(t, component, { clientId: 'core', workspaceId: 'w', compact: true, phones: [] }, {
            getClientCrmLinks: async () => [], getCrmProjects: async () => [{ ...project, archived: true }],
            getLatestClientProvision: async () => { reads++; return { id: 'saved', status: 'CREATED' } },
        })
        await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
        assert.equal(reads, 0)
        assert.equal(button(f.ui, 'Восстановить связь'), undefined)
    })
}

test('CRM client card groups contacts, statistics and link history without technical codes or actor IDs', async t => {
    const actor = 'abcfc013-2101-4cab-9326-861404e8d578'
    const f = await fixture(t, 'CrmClientCard', {}, { getCrmClient: async () => ({ ...client,
        visitCount: 5, averageCheck: '10000.00', totalSpent: '50000.00', currency: 'RUB',
        mappingUpdatedAt: '2026-11-20T05:39:17Z', mappingReason: 'UNLINK', mappingUpdatedBy: actor }) })
    const content = text(f.ui)
    assert.match(content, /Телефон/)
    assert.match(content, /Связь снята/)
    assert.match(content, /50.?000,00 RUB/)
    assert.doesNotMatch(content, /UNLINK|phone:|abcfc013/)
    assert.deepEqual(f.ui.root.findAllByType('section').map(section => section.props['aria-label']), ['Контакты', 'Статистика клиента', 'Связь с ClientBus'])
    assert.equal(f.ui.root.findByType('details').props.open, undefined)
})

test('CRM clients show provider aggregates and send sort changes to the paged API', async t => {
    const reads = []
    const f = await fixture(t, 'CrmClients', {}, { getCrmClients: async (_, query) => {
        reads.push(query)
        return page([{ ...client, visitCount: 5, totalSpent: '3500.25', averageCheck: '700.05', currency: 'RUB' },
            { ...client, id: 'missing', displayName: 'Без статистики', visitCount: null, totalSpent: null }])
    } })
    assert.match(text(f.ui), /Кол-во визитов/)
    assert.match(text(f.ui), /3.?500,25 RUB/)
    assert.match(text(f.ui), /Нет данных/)
    assert.equal(reads.at(-1).sort, 'NAME'); assert.equal(reads.at(-1).direction, 'ASC')
    assert.equal(f.ui.root.findAllByType('select').length, 0)
    assert.match(text(f.ui), /Средний чек/)
    assert.match(text(f.ui), /700,05 RUB/)
    await click(f.ui, /^Кол-во визитов/)
    assert.equal(reads.at(-1).sort, 'VISITS'); assert.equal(reads.at(-1).direction, 'DESC')
    await click(f.ui, /^Потраченная сумма/)
    assert.equal(reads.at(-1).sort, 'SPENT')
    await click(f.ui, /^Потраченная сумма/)
    assert.equal(reads.at(-1).direction, 'ASC')
    assert.equal(reads.at(-1).cursor, undefined)
})

test('client column headers change rendered order through the actual API wrapper', async t => {
    const rows = [
        { ...client, id: 'a', displayName: 'Анна', visitCount: 2, totalSpent: '9000.00', averageCheck: '4500.00', currency: 'RUB' },
        { ...client, id: 'b', displayName: 'Борис', visitCount: 8, totalSpent: '1000.00', averageCheck: '125.00', currency: 'RUB' },
        { ...client, id: 'c', displayName: 'Вера', visitCount: 5, totalSpent: '4000.00', averageCheck: '800.00', currency: 'RUB' },
    ]
    const requests = []
    const realApi = load('../../api/crmApi.ts', { './apiClient': { apiFetch: async url => {
        const query = new URL(url, 'http://test').searchParams
        requests.push(query)
        const sorted = [...rows].sort((a, b) => {
            const difference = query.get('sort') === 'VISITS' ? a.visitCount - b.visitCount
                : query.get('sort') === 'SPENT' ? Number(a.totalSpent) - Number(b.totalSpent)
                : query.get('sort') === 'AVERAGE_CHECK' ? Number(a.averageCheck) - Number(b.averageCheck)
                : a.displayName.localeCompare(b.displayName, 'ru')
            return query.get('direction') === 'DESC' ? -difference : difference
        })
        return { json: async () => ({ ...page(sorted), syncState: state }) }
    } } })
    const f = await fixture(t, 'CrmClients', {}, { getCrmClients: realApi.getCrmClients })
    const names = () => f.ui.root.findByType('tbody').findAllByType('tr').map(row => row.findByType('button').children.join(''))
    assert.deepEqual(names(), ['Анна', 'Борис', 'Вера'])
    const headers = f.ui.root.findAllByType('th')
    assert.deepEqual(headers.slice(0, 4).map(header => header.findByType('button').children[0]), ['Клиент', 'Кол-во визитов', 'Средний чек', 'Потраченная сумма'])
    await click(f.ui, /^Кол-во визитов/)
    assert.deepEqual(names(), ['Борис', 'Вера', 'Анна'])
    assert.equal(f.ui.root.findAllByType('th')[1].props['aria-sort'], 'descending')
    await click(f.ui, /^Кол-во визитов/)
    assert.deepEqual(names(), ['Анна', 'Вера', 'Борис'])
    await click(f.ui, /^Потраченная сумма/)
    assert.deepEqual(names(), ['Анна', 'Вера', 'Борис'])
    await click(f.ui, /^Потраченная сумма/)
    assert.deepEqual(names(), ['Борис', 'Вера', 'Анна'])
    await click(f.ui, /^Средний чек/)
    assert.deepEqual(names(), ['Анна', 'Вера', 'Борис'])
    await click(f.ui, /^Средний чек/)
    assert.deepEqual(names(), ['Борис', 'Вера', 'Анна'])
    await click(f.ui, /^Клиент/)
    assert.deepEqual(names(), ['Анна', 'Борис', 'Вера'])
    assert.equal(requests.at(-1).get('sort'), 'NAME')
    assert.equal(requests.at(-1).get('direction'), 'ASC')
})
test('a member explicitly confirms the first client link; matching phones never link automatically', async t => {
    const { ui, calls } = await fixture(t, 'CrmClientCard')
    await click(ui, 'Выбрать клиента ClientBus'); assert.deepEqual(calls, [])
    await click(ui, /Кандидат.*телефон совпадает/); assert.deepEqual(calls, [])
    assert.match(text(ui), /Связать с Кандидат Клиент/)
    await click(ui, 'Подтвердить связь'); assert.deepEqual(calls[0], ['link', 7, 'core'])
})
test('an existing client link is read-only to a member and can be changed by a manager', async t => {
    const row = { ...client, clientId: 'core' }
    const member = await fixture(t, 'CrmClientCard', {}, { getCrmClient: async () => row })
    assert.equal(button(member.ui, 'Изменить связь'), undefined); assert.equal(button(member.ui, 'Снять связь'), undefined)
    const admin = await fixture(t, 'CrmClientCard', {}, { getCrmClient: async () => row }, true)
    assert.ok(button(admin.ui, 'Изменить связь')); assert.ok(button(admin.ui, 'Снять связь'))
    const archive = await fixture(t, 'CrmClientCard', { project: { ...project, archived: true } }, {}, true)
    assert.equal(button(archive.ui, 'Выбрать клиента ClientBus'), undefined)
})
test('a background update cannot replace the version captured for client-link confirmation', async t => {
    let row = client
    const f = await fixture(t, 'CrmClientCard', {}, { getCrmClient: async () => row,
        linkCrmClient: async (value, id) => { f.calls.push(['link', value.version, id]); throw Error('Конфликт версии') } })
    await click(f.ui, 'Выбрать клиента ClientBus'); await click(f.ui, /Кандидат.*телефон совпадает/)
    row = { ...client, version: 8 }; await f.refresh('crm-client:')
    await click(f.ui, 'Подтвердить связь')
    assert.deepEqual(f.calls[0], ['link', 7, 'core']); assert.match(text(f.ui), /Конфликт версии/)
    assert.equal(button(f.ui, 'Подтвердить связь'), undefined)
})
test('creating a linked client sends explicit fields once and access revoke aborts the old command', async t => {
    const pending = deferred(); let signal, applied = 0
    const f = await fixture(t, 'CrmClientCard', { changed() { applied++ } }, { createLinkedCrmClient: (row, value, s) => { signal = s; f.calls.push(['create', row.version, value]); return pending.promise } })
    await click(f.ui, 'Создать клиента ClientBus'); await change(f.ui, 'Имя', ' Имя '); await change(f.ui, 'Фамилия', 'Фамилия'); await change(f.ui, 'Телефоны через запятую', '79991234567, 79997654321')
    await act(async () => { const submit = f.ui.root.findByType('form').props.onSubmit; submit({ preventDefault() {} }); submit({ preventDefault() {} }) })
    assert.equal(f.calls.length, 1); assert.deepEqual(f.calls[0], ['create', 7, { firstName: 'Имя', lastName: 'Фамилия', phoneList: ['79991234567', '79997654321'] }])
    await f.revoke('crm-command:'); assert.equal(signal.aborted, true)
    await act(async () => pending.resolve(client)); assert.equal(applied, 0)
})
test('appointment details retain all historical services and masters and load finance only when opened', async t => {
    const f = await fixture(t, 'CrmAppointmentCard', { id: 'a', archived: true })
    assert.match(text(f.ui), /Стрижка/); assert.match(text(f.ui), /Укладка/); assert.match(text(f.ui), /Анна/); assert.match(text(f.ui), /Ирина/)
    assert.match(text(f.ui), /1200 RUB/); assert.doesNotMatch(text(f.ui), /1200\.0000/)
    assert.deepEqual(f.calls, []); await click(f.ui, 'Финансы визита')
    assert.equal(f.calls.filter(row => row[0] === 'finance').length, 1)
    assert.match(text(f.ui), /не означает нулевую оплату/)
    assert.equal(button(f.ui, 'Обновить финансовые данные').props.disabled, true)
})
test('service blocks never offer appointment finance', async t => {
    const f = await fixture(t, 'CrmAppointmentCard', { id: 'a', archived: false }, { getCrmAppointment: async () => ({ ...visit, kind: 'BLOCK' }) })
    assert.match(text(f.ui), /Служебный блок/); assert.equal(button(f.ui, 'Финансы визита'), undefined)
})
test('compact CRM context loads only while expanded and visible, scopes every read and keeps at most three visits', async t => {
    const f = await fixture(t, 'ClientCrmPanel', { clientId: 'core', workspaceId: 'w', compact: true, visible: false }, {
        getClientCrmAppointments: async (id, query) => { f.calls.push(['history', id, query]); return { ...page(Array.from({ length: 5 }, (_, i) => ({ ...visit, id: 'a' + i }))), coverage: [] } },
    })
    assert.deepEqual(f.calls, [])
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.deepEqual(f.calls, [])
    await f.update({ visible: true })
    assert.equal(f.calls[0][0], 'links'); assert.equal(f.calls[0][1], 'core'); assert.equal(f.calls[0][2], 'w')
    assert.equal(f.calls.find(row => row[0] === 'history')[2].workspaceId, 'w')
    assert.equal(f.ui.root.findAllByProps({ className: 'crm-appointment-row' }).length, 3)
    const more = f.ui.root.findAllByType('a').find(row => row.children.join('').includes('Все визиты'))
    assert.match(more.props.to, /^\/crm\?tab=appointments&/); assert.match(more.props.to, /clientId=core/); assert.match(more.props.to, /workspace=w/)
})
test('schedule refresh is one explicit master and exact period; intervals do not claim bookable slots', async t => {
    const f = await fixture(t, 'CrmSchedule', { initialMaster: 'master' })
    assert.match(text(f.ui), /не подтверждают свободное время/); assert.match(text(f.ui), /Рабочий интервал/)
    assert.deepEqual(f.calls, []); await click(f.ui, 'Обновить график')
    assert.equal(f.calls.length, 1); assert.equal(f.calls[0][2].dataset, 'WORK_INTERVALS'); assert.equal(f.calls[0][2].masterId, 'master')
    assert.match(f.calls[0][2].dateFrom, /^\d{4}-\d{2}-\d{2}$/)
})
test('date scopes respect leap dates, UTC date arithmetic and the 31-day API limit', () => {
    const { crmPeriodValid, crmShift } = load('../crm/CrmReadShared.tsx')
    assert.equal(crmPeriodValid({ dateFrom: '2026-02-30', dateTo: '2026-03-01' }), false)
    assert.equal(crmPeriodValid({ dateFrom: '2028-02-29', dateTo: '2028-02-29' }), true)
    assert.equal(crmPeriodValid({ dateFrom: '2026-03-01', dateTo: '2026-03-31' }), true)
    assert.equal(crmPeriodValid({ dateFrom: '2026-03-01', dateTo: '2026-04-01' }), false)
    assert.equal(crmPeriodValid({ dateFrom: '2026-03-02', dateTo: '2026-03-01' }), false)
    assert.equal(crmShift('2026-03-29', 1), '2026-03-30')
})
test('nearest visits omit confirmed past instants but retain explicitly unverified times', async t => {
    const now = Date.now()
    const f = await fixture(t, 'CrmAppointmentRows', { project, query: { dateFrom: '2026-11-08', dateTo: '2026-11-08' }, direction: 'future', compact: true }, {
        getCrmAppointments: async () => ({ ...page([
            { ...visit, id: 'past', startAt: new Date(now - 60000).toISOString(), clientSnapshot: { displayName: 'Прошедший' } },
            { ...visit, id: 'future', startAt: new Date(now + 60000).toISOString(), clientSnapshot: { displayName: 'Предстоящий' } },
            { ...visit, id: 'unknown', startAt: null, clientSnapshot: { displayName: 'Непроверенный' } },
        ]), coverage: [] }),
    })
    assert.doesNotMatch(text(f.ui), /Прошедший/); assert.match(text(f.ui), /Предстоящий/); assert.match(text(f.ui), /Непроверенный/)
    assert.match(text(f.ui), /Точное время не подтверждено/)
})
test('archived and disabled project finance stays read-only while local snapshots remain visible', async t => {
    const f = await fixture(t, 'CrmAppointmentCard', { id: 'a', archived: false, canRefresh: false })
    await click(f.ui, 'Финансы визита')
    assert.equal(button(f.ui, 'Обновить финансовые данные').props.disabled, true)
    assert.match(text(f.ui), /Сохранённых финансовых данных нет/)
})
test('new API wrappers normalize client sync state and preserve aggregate coverage and string revision', async () => {
    const requests = []
    const api = load('../../api/crmApi.ts', { './apiClient': { apiFetch: async (url, init) => {
        requests.push([url, init]); return { json: async () => url.includes('/clients?') ? { items: [], syncState: state, revision: 5 } : { items: [], coverage: [{ projectId: 'p', scopes: [state] }], revision: 'revision-token' } }
    } } })
    assert.equal((await api.getCrmClients('p', { q: 'Имя + телефон', unlinked: true })).dataState.generation, 1)
    const result = await api.getClientCrmAppointments('core', { dateFrom: '2026-11-01', dateTo: '2026-11-02', workspaceId: 'w' })
    assert.equal(result.revision, 'revision-token'); assert.equal(result.coverage.length, 1)
    assert.equal(result.dataState.completeness, 'UNKNOWN')
    assert.equal(new URL(requests[0][0], 'http://test').searchParams.get('q'), 'Имя + телефон')
    assert.match(requests[1][0], /workspaceId=w/)
})

test('compact nearest visits paginate past filtered rows and stop after three matches', async t => {
    const now = Date.now(), cursors = []
    const upcoming = i => ({ ...visit, id: 'future-' + i, startAt: new Date(now + i * 60000).toISOString() })
    const f = await fixture(t, 'CrmAppointmentRows', { project: undefined, clientId: 'core', compact: true, direction: 'future', query: { dateFrom: '2026-11-08', dateTo: '2026-11-09' } }, {
        getClientCrmAppointments: async (id, query) => {
            cursors.push(query.cursor)
            const items = !query.cursor ? Array.from({ length: 100 }, (_, i) => ({ ...visit, id: 'past-' + i, startAt: new Date(now - 60000).toISOString() }))
                : query.cursor === 'next-1' ? [upcoming(1)] : [upcoming(2), upcoming(3)]
            return { ...page(items), hasMore: true, nextCursor: !query.cursor ? 'next-1' : query.cursor === 'next-1' ? 'next-2' : 'next-3' }
        },
    })
    assert.deepEqual(cursors, [undefined, 'next-1', 'next-2'])
    assert.equal(f.ui.root.findAllByProps({ className: 'crm-appointment-row' }).length, 3)
    assert.doesNotMatch(text(f.ui), /ближайшие визиты не найдены|Поиск ближайших визитов/)
})

test('compact pagination stops on failure and distinguishes it from an empty result', async t => {
    let reads = 0
    const f = await fixture(t, 'CrmAppointmentRows', { project, compact: true, direction: 'future', query: { dateFrom: '2026-11-08', dateTo: '2026-11-09' } }, {
        getCrmAppointments: async () => {
            if (++reads > 1) throw Error('Ошибка следующей страницы')
            return { ...page([{ ...visit, startAt: new Date(Date.now() - 60000).toISOString() }]), hasMore: true, nextCursor: 'next' }
        },
    })
    assert.equal(reads, 2)
    assert.match(text(f.ui), /Ошибка следующей страницы/)
    assert.doesNotMatch(text(f.ui), /ближайшие визиты не найдены/)
})

test('hiding the inbox panel cancels automatic pagination and ignores its late response', async t => {
    const pending = deferred(); let signal
    const f = await fixture(t, 'ClientCrmPanel', { clientId: 'core', workspaceId: 'w', compact: true }, {
        getClientCrmAppointments: async (id, query, requestSignal) => {
            if (query.cursor) { signal = requestSignal; return pending.promise }
            return { ...page([{ ...visit, startAt: new Date(Date.now() - 60000).toISOString() }]), hasMore: true, nextCursor: 'next' }
        },
    })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.ok(signal); assert.equal(signal.aborted, false)
    await f.update({ visible: false }); assert.equal(signal.aborted, true)
    await act(async () => pending.resolve(page([{ ...visit, startAt: new Date(Date.now() + 60000).toISOString() }])))
    assert.equal(f.ui.root.findAllByProps({ className: 'crm-appointment-row' }).length, 0)
})

test('«CRM» section of the inbox panel shows linked crm clients and never reads appointments', async t => {
    const f = await fixture(t, 'ClientCrmSection', { clientId: 'core', workspaceId: 'w', phones: ['+7 999 123-45-67'] }, { getCrmProjects: async () => [project] }, true)
    assert.deepEqual(f.calls, [])
    assert.doesNotMatch(text(f.ui), /Внешний клиент/)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.deepEqual(f.calls[0].slice(0, 3), ['links', 'core', 'w'])
    assert.match(text(f.ui), /Внешний клиент/); assert.match(text(f.ui), /Салон/); assert.match(text(f.ui), /· ID /)
    assert.equal(f.calls.filter(row => row[0] === 'history').length, 0)
    assert.equal(f.ui.root.findAllByProps({ className: 'crm-appointment-row' }).length, 0)
    await click(f.ui, 'Карточка'); assert.match(text(f.ui), /Клиент CRM/)
})

test('a member cannot link crm clients from the inbox panel; a supervisor links a free card by hand', async t => {
    const member = await fixture(t, 'ClientCrmSection', { clientId: 'core', workspaceId: 'w', phones: [] }, { getCrmProjects: async () => [project] })
    await act(async () => member.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.equal(button(member.ui, 'Найти или создать в CRM'), undefined)
    const boss = await fixture(t, 'ClientCrmSection', { clientId: 'core', workspaceId: 'w', phones: ['+7 999 123-45-67'] }, {
        getCrmProjects: async () => [project],
        getClientCrmLinks: async () => [],
        searchCrmClients: async (...args) => { boss.calls.push(['clients', ...args]); return { items: [{ ...client, normalizedPhone: '79991234567' }], hasMore: false } },
    }, true)
    await act(async () => boss.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    await click(boss.ui, 'Найти или создать в CRM')
    const requested = boss.calls.find(row => row[0] === 'clients')
    assert.equal(requested[1], 'p'); assert.equal(requested[2], 'core'); assert.equal(requested[3], '79991234567')
    assert.match(text(boss.ui), /Внешний клиент/); assert.match(text(boss.ui), /79991234567/); assert.match(text(boss.ui), /телефон совпадает/)
    assert.deepEqual(boss.calls.filter(row => row[0] === 'link'), [])
    await click(boss.ui, 'Выбрать')
    assert.deepEqual(boss.calls.filter(row => row[0] === 'link'), [])
    await click(boss.ui, 'Связать существующего')
    assert.deepEqual(boss.calls.find(row => row[0] === 'link'), ['link', 7, 'core'])
})

test('a linked CRM card has explicit unlink confirmation and does not open an empty search', async t => {
    let linked = true
    const row = { ...client, clientId: 'core', mappingUpdatedAt: null, mappingUpdatedBy: null, mappingReason: null }
    const f = await fixture(t, 'ClientCrmSection', { clientId: 'core', workspaceId: 'w', phones: [] }, {
        getCrmProjects: async () => [project], getCrmClient: async () => row,
        getClientCrmLinks: async () => linked ? [{ client: row, project }] : [],
        linkCrmClient: async (_, id) => { f.calls.push(['unlink', id]); linked = false },
    }, true)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.equal(button(f.ui, 'Найти или создать в CRM'), undefined)
    await click(f.ui, 'Отвязать')
    assert.deepEqual(f.calls, [])
    assert.match(f.ui.root.findByProps({ className: 'crm-unlink-confirm' }).findByType('p').children.join(''), /Карточка и данные в DIKIDI сохранятся/)
    await click(f.ui, 'Подтвердить отвязку')
    assert.deepEqual(f.calls, [['unlink', null]])
    assert.ok(button(f.ui, 'Найти или создать в CRM'))
})

test('unlink never removes a concurrently changed association', async t => {
    const row = { ...client, clientId: 'core', mappingUpdatedAt: null, mappingUpdatedBy: null, mappingReason: null }
    const f = await fixture(t, 'ClientCrmSection', { clientId: 'core', workspaceId: 'w', phones: [] }, {
        getCrmProjects: async () => [project], getCrmClient: async () => ({ ...row, clientId: 'other-core' }),
        getClientCrmLinks: async () => [{ client: row, project }],
    }, true)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    await click(f.ui, 'Отвязать'); await click(f.ui, 'Подтвердить отвязку')
    assert.equal(f.calls.filter(row => row[0] === 'link').length, 0)
    assert.match(text(f.ui), /Связь уже изменена/)
})

test('avatar transfer previews the source and writes only after explicit confirmation', async t => {
    const row = { ...client, clientId: 'core' }
    const f = await fixture(t, 'CrmClientCard', {}, {
        sourceAvatar: 'source.png', getCrmProjects: async () => [project], getCrmClient: async () => ({ ...row, version: 9 }),
        getClientCrmLinks: async () => [{ client: row, project }],
        transferCrmClientAvatar: async (target, source) => { f.calls.push(['photo', target.version, source.id, source.avatarUrl]); return row },
    }, true)
    await click(f.ui, 'Аватар из ClientBus'); assert.deepEqual(f.calls, [])
    const section = f.ui.root.findByProps({ 'aria-label': 'Связь с ClientBus' })
    assert.ok(section.findAllByType('button').some(node => node.children.join('') === 'Перенести в DIKIDI'))
    assert.match(text(f.ui), /Текущий аватар CRM будет заменён/)
    await click(f.ui, 'Перенести в DIKIDI')
    assert.deepEqual(f.calls, [['photo', 9, 'core', 'source.png']]); assert.match(text(f.ui), /Аватар перенесён в DIKIDI/)
})

test('missing source photo is reported before any external transfer', async t => {
    const f = await fixture(t, 'CrmClientCard', {}, {
        getCrmClient: async () => ({ ...client, clientId: 'core' }),
    }, true)
    await click(f.ui, 'Аватар из ClientBus')
    assert.equal(button(f.ui, 'Перенести в DIKIDI'), undefined); assert.match(text(f.ui), /У клиента ClientBus нет аватара/)
})

for (const core of [false, true]) test(`changing appointment project preserves dates and states${core ? ' and core client' : ''} but clears project IDs`, async t => {
    let changed
    const params = new URLSearchParams('tab=appointments&workspace=w&project=p&dateFrom=2026-10-01&dateTo=2026-10-02&paymentState=PAID&kind=APPOINTMENT&masterId=m&crmClientId=external&externalClientId=client-1')
    if (core) params.set('clientId', 'core')
    const Component = load('../../pages/CrmPage.tsx', {
        'react-router-dom': { useSearchParams: () => [params, next => { changed = next }], useLocation: () => ({ key: 'test' }), Link: props => React.createElement('a', props) },
        '../auth/AuthContext': { useAuth: () => ({ currentEmployee: { id: 'actor', role: 'EMPLOYEE' }, sessionEpoch: 1, isSuperAdmin: false, employeeWorkspaces: [] }) },
        '../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: { id: 'w' }, workspaces: [{ id: 'w' }], setCurrentWorkspaceId() {} }) },
        '../api/events/useUiResource': { useUiResource: (key, enabled, matches, read, apply, fail, reset, pending) => {
            React.useEffect(() => { apply([project, { ...project, id: 'p2' }].map(row => ({ ...row, capabilities: [] }))); pending(false) }, [key])
            return { reload() {} }
        } },
        '../api/events/uiScope': { useUiScope: () => null },
        '../components/crm/CrmCatalog': {},
        '../components/crm/CrmClients': {},
        '../components/crm/CrmSchedule': {},
        '../components/crm/CrmProjectAppointments': { CrmProjectAppointments: () => null },
        '../components/crm/crm.css': {},
    }).default
    let ui
    await act(async () => { ui = create(React.createElement(Component)) })
    t.after(() => act(async () => ui.unmount()))
    await act(async () => ui.root.findByProps({ className: 'crm-project-select' }).findByType('select').props.onChange({ target: { value: 'p2' } }))
    const next = new URLSearchParams(changed)
    assert.equal(next.get('workspace'), 'w'); assert.equal(next.get('project'), 'p2'); assert.equal(next.get('tab'), 'appointments')
    assert.equal(next.get('clientId'), core ? 'core' : null)
    assert.equal(next.get('dateFrom'), '2026-10-01'); assert.equal(next.get('dateTo'), '2026-10-02')
    assert.equal(next.get('paymentState'), 'PAID'); assert.equal(next.get('kind'), 'APPOINTMENT')
    assert.equal(next.has('masterId'), false); assert.equal(next.has('crmClientId'), false)
    assert.equal(next.has('externalClientId'), false)
})

test('aggregate automatic history covers today in every time zone for both future and past visits', async t => {
    const RealDate = global.Date, instant = RealDate.parse('2026-11-08T10:30:00Z')
    global.Date = class extends RealDate { constructor(...args) { super(...(args.length ? args : [instant])) } static now() { return instant } }
    t.after(() => { global.Date = RealDate })
    const east = { ...project, id: 'east', zoneId: 'Pacific/Kiritimati' }, west = { ...project, id: 'west', workspaceId: 'w2', zoneId: 'America/Los_Angeles' }
    const rows = [
        { ...visit, id: 'future-west', projectId: west.id, workspaceId: west.workspaceId, localStart: '2026-11-08T03:00:00', startAt: '2026-11-08T11:00:00Z', clientSnapshot: { name: 'Будущий запад' } },
        { ...visit, id: 'past-east', projectId: east.id, localStart: '2026-11-09T00:29:00', startAt: '2026-11-08T10:29:00Z', clientSnapshot: { name: 'Прошлый восток' } },
    ]
    const queries = [], { crmPeriodValid } = load('../crm/CrmReadShared.tsx')
    const f = await fixture(t, 'ClientCrmPanel', { clientId: 'core' }, {
        getClientCrmLinks: async (id, workspace) => (workspace ? [east] : [east, west]).map(project => ({ client: { ...client, id: project.id }, project })),
        getClientCrmAppointments: async (id, query) => {
            queries.push(query)
            return page(rows.filter(row => (!query.workspaceId || row.workspaceId === query.workspaceId) && row.localStart.slice(0, 10) >= query.dateFrom && row.localStart.slice(0, 10) <= query.dateTo))
        },
    })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    await change(f.ui, 'Рабочее пространство истории', '')
    assert.match(text(f.ui), /Будущий запад/); assert.doesNotMatch(text(f.ui), /Прошлый восток/)
    assert.equal(queries.at(-1).dateFrom, '2026-11-08')
    await click(f.ui, 'Прошлые визиты')
    assert.match(text(f.ui), /Прошлый восток/); assert.doesNotMatch(text(f.ui), /Будущий запад/)
    assert.equal(queries.at(-1).dateTo, '2026-11-09')
    assert.ok(queries.every(crmPeriodValid))
    // An explicitly entered period is kept even when its dates differ from the automatic window.
    await change(f.ui, 'По дату', '2026-11-08')
    assert.equal(queries.at(-1).dateTo, '2026-11-08')
})

test('directory search and empty states stay inside a lazy popup and never change the selected filter', async t => {
    let reads = 0
    const changes = []
    const f = await fixture(t, 'CrmDirectoryPicker', { kind: 'client', value: '', change: value => changes.push(value) }, {
        getCrmClients: async () => { reads++; return { ...page([]), dataState: { ...state, freshness: 'NEVER_LOADED' } } },
    })
    assert.equal(reads, 0); assert.equal(f.ui.root.findAllByType('input').length, 0)
    assert.match(text(f.ui), /Все клиенты/); assert.doesNotMatch(text(f.ui), /не загружен|не найдены/)
    await act(async () => f.ui.root.findByProps({ className: 'crm-directory-trigger' }).props.onClick())
    assert.equal(reads, 1)
    const popup = f.ui.root.findByProps({ role: 'dialog' })
    assert.match(popup.findByProps({ className: 'crm-directory-status' }).findByType('p').children.join(''), /не загружен/)
    await change(f.ui, 'Поиск клиента CRM', 'Нет такого')
    await act(async () => new Promise(resolve => setTimeout(resolve, 280)))
    assert.match(text(f.ui), /По запросу ничего не найдено/); assert.deepEqual(changes, [])
    await act(async () => f.ui.root.findByProps({ className: 'crm-directory-picker' }).props.onKeyDown({ key: 'Escape', preventDefault() {}, stopPropagation() {} }))
    assert.equal(f.ui.root.findAllByProps({ role: 'dialog' }).length, 0)
    assert.doesNotMatch(text(f.ui), /не найдено|не загружен/)
})

test('directory background reads keep its popup, input, selected name and options during loading', async t => {
    let pending
    const changes = []
    const f = await fixture(t, 'CrmDirectoryPicker', { kind: 'client', value: 'external', change: value => changes.push(value) }, {
        getCrmClients: () => pending ? pending.promise : Promise.resolve(page([client])),
    })
    await act(async () => f.ui.root.findByProps({ className: 'crm-directory-trigger' }).props.onClick())
    const popup = f.ui.root.findByProps({ role: 'dialog' }), input = field(f.ui, 'Поиск клиента CRM')
    pending = deferred()
    let refresh
    await act(async () => { refresh = f.read('crm-filter:'); await Promise.resolve() })
    assert.equal(f.ui.root.findByProps({ role: 'dialog' }), popup)
    assert.equal(field(f.ui, 'Поиск клиента CRM'), input)
    assert.equal(f.ui.root.findAllByProps({ role: 'option' }).length, 2)
    await act(async () => pending.resolve(page([]))); await refresh
    assert.equal(field(f.ui, 'Поиск клиента CRM'), input)
    assert.match(text(f.ui), /В проекте нет сохранённых клиентов CRM/)
    assert.match(text(f.ui), /Внешний клиент/); assert.deepEqual(changes, [])
})

test('directory selection and clearing are explicit and errors never masquerade as empty results', async t => {
    const changes = []
    const f = await fixture(t, 'CrmDirectoryPicker', { kind: 'client', value: '', change: value => changes.push(value) })
    const open = () => act(async () => f.ui.root.findByProps({ className: 'crm-directory-trigger' }).props.onClick())
    await open(); await click(f.ui, 'Внешний клиент')
    assert.deepEqual(changes, ['external']); assert.equal(f.ui.root.findAllByProps({ role: 'dialog' }).length, 0)
    await f.update({ value: 'external' }); assert.match(text(f.ui), /Внешний клиент/)
    await open(); await click(f.ui, 'Все клиенты'); assert.deepEqual(changes, ['external', ''])
    await f.update({ value: '' }); f.api.getCrmClients = async () => { throw Error('Не удалось прочитать список') }
    await open()
    assert.match(text(f.ui), /Не удалось прочитать список/)
    assert.doesNotMatch(text(f.ui), /ничего не найдено|нет сохранённых|ещё не загружен/)
})

test('appointment client picker reads the selected period without requiring a CRM client directory', async t => {
    const requests = [], selected = [], period = { dateFrom: '2026-11-08', dateTo: '2026-11-09' }
    const f = await fixture(t, 'CrmDirectoryPicker', { kind: 'client', period, value: '', change: id => selected.push(id) }, {
        getCrmClients: async () => { throw Error('Client directory must not be read') },
        getCrmClient: async () => { throw Error('External reference is not a CRM UUID') },
        getCrmAppointmentClients: async (id, query) => { requests.push([id, query]); return page([{ id: 'client-1', displayName: 'Клиент из визитов', phone: '+79991234567' }]) },
    })
    await act(async () => f.ui.root.findByProps({ className: 'crm-directory-trigger' }).props.onClick())
    assert.deepEqual(requests[0], ['p', { ...period, q: '', cursor: undefined }])
    await click(f.ui, 'Клиент из визитов'); assert.deepEqual(selected, ['client-1'])
    await f.update({ value: 'client-1' })
    assert.match(text(f.ui), /Клиент из визитов/)
    assert.deepEqual(requests.at(-1), ['p', { ...period, externalClientId: 'client-1' }])
    await f.update({ period: { dateFrom: '2026-11-10', dateTo: '2026-11-10' } })
    assert.equal(requests.at(-1)[1].dateFrom, '2026-11-10')
})

test('period refresh rereads saved appointments immediately after the queued response', async t => {
    let reads = 0
    const f = await fixture(t, 'CrmAppointmentRows', { project, query: { dateFrom: '2026-11-08', dateTo: '2026-11-08' } }, {
        getCrmAppointments: async () => { reads++; return { ...page([visit]), coverage: [] } },
    })
    assert.equal(reads, 1)
    await f.update({ refresh: 1 }); assert.equal(reads, 2)
})

test('a new appointment query reads once after an earlier period refresh', async t => {
    let reads = 0
    const f = await fixture(t, 'CrmAppointmentRows', { project, refresh: 1, query: { dateFrom: '2026-11-08', dateTo: '2026-11-08' } }, {
        getCrmAppointments: async () => { reads++; return { ...page([visit]), coverage: [] } },
    })
    assert.equal(reads, 1)
    await f.update({ refresh: 2 })
    assert.equal(reads, 2)
})

test('appointment action history is lazy, card-scoped and hides technical IDs until details', async t => {
    const calls = []
    const operation = { id: 'operation-id', type: 'MOVE', status: 'CONFIRMED', createdAt: '2026-11-08T09:00:00Z', externalAppointmentId: 'external-visit' }
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, { getCrmAppointmentHistory: async (...args) => {
        calls.push(args); return { items: [{ operation, authorName: 'Катя Катина', oldStart: '2026-11-08T12:00:00', newStart: '2026-11-09T14:00:00', zoneId: 'Europe/Moscow', cancelComment: null }], hasMore: false }
    } })
    assert.equal(calls.length, 0)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.equal(calls.length, 1); assert.equal(calls[0][0], 'a'); assert.equal(calls[0][1].own, true)
    assert.match(text(f.ui), /Катя Катина/); assert.match(text(f.ui), /Прежнее начало/); assert.match(text(f.ui), /Новое начало/)
    const technical = f.ui.root.findAllByType('details')[1]
    assert.equal(technical.props.open, undefined)
    await act(async () => f.ui.root.findAllByType('details')[0].props.onToggle({ currentTarget: { open: false } }))
    assert.equal(calls.length, 1)
})
test('manager history paginates and reconciles UNKNOWN without resending the command', async t => {
    const calls = [], operation = { id: 'unknown-id', type: 'CANCEL', status: 'UNKNOWN', createdAt: '2026-11-08T09:00:00Z' }
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, {
        getCrmAppointmentHistory: async (id, query) => { calls.push(['history', id, query]); return { items: [{ operation, authorName: 'Катя', cancelComment: 'Причина отмены' }], hasMore: !query.cursor, nextCursor: 'next' } },
        reconcileCrmOperation: async id => { calls.push(['reconcile', id]); return operation },
    }, true)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.equal(calls[0][2].own, false); assert.match(text(f.ui), /Причина отмены/)
    await click(f.ui, 'Следующая страница истории'); assert.equal(calls[1][2].cursor, 'next')
    await click(f.ui, 'Сверить результат'); assert.deepEqual(calls[2], ['reconcile', 'unknown-id'])
    assert.equal(calls.filter(row => row[0] === 'history').length, 3)
})
test('late card history cannot populate the next appointment', async t => {
    const old = deferred()
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, { getCrmAppointmentHistory: async id => id === 'a' ? old.promise : { items: [], hasMore: false } })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    await f.update({ row: { ...visit, id: 'next' } })
    await act(async () => old.resolve({ items: [{ operation: { id: 'old', type: 'MOVE', status: 'CONFIRMED', createdAt: '2026-11-08T09:00:00Z' }, authorName: 'OLD AUTHOR' }], hasMore: false }))
    assert.doesNotMatch(text(f.ui), /OLD AUTHOR/); assert.match(text(f.ui), /Действий в ClientBus пока нет/)
})

test('reconciliation from a card resolves the authors durable pointer without the project panel', async t => {
    const resolved = [], operation = { id: 'own-operation', initiatorEmployeeId: 'actor', type: 'MOVE', status: 'UNKNOWN', createdAt: '2026-11-08T09:00:00Z' }
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, {
        getCrmAppointmentHistory: async () => ({ items: [{ operation, authorName: 'Автор' }], hasMore: false }),
        reconcileCrmOperation: async () => ({ ...operation, status: 'CONFIRMED' }),
    }, false, { resolveIntent: async (scope, result) => resolved.push([scope.employeeId, result.id, result.status]) })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    await click(f.ui, 'Сверить результат')
    assert.deepEqual(resolved, [['actor', 'own-operation', 'CONFIRMED']])
})

for (const status of ['CONFIRMED', 'REJECTED']) test('reading own ' + status + ' from card resolves the intent without project panel', async t => {
    const resolved = [], operation = { id: 'own-done', idempotencyKey: 'pending-key', initiatorEmployeeId: 'actor', type: 'MOVE', status, createdAt: '2026-11-08T09:00:00Z' }
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, {
        getCrmAppointmentHistory: async () => ({ items: [{ operation, authorName: 'Автор' }, { operation: { ...operation, id: 'other', initiatorEmployeeId: 'another' }, authorName: 'Другой' }], hasMore: false }),
    }, false, { readIntent: () => ({ key: 'pending-key' }), resolveIntent: async (scope, result) => resolved.push([scope.employeeId, result.id]) })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.deepEqual(resolved, [['actor', 'own-done']])
})

test('own completed history stays readable when local storage is unavailable', async t => {
    const f = await fixture(t, 'CrmAppointmentHistory', { row: visit }, {
        getCrmAppointmentHistory: async () => ({ items: [{ operation: { id: 'done', initiatorEmployeeId: 'actor', type: 'CREATE', status: 'CONFIRMED', createdAt: '2026-11-08T09:00:00Z' }, authorName: 'Читаемый автор' }], hasMore: false }),
    }, false, { readIntent() { throw Error('Storage unavailable') }, resolveIntent() { throw Error('Must not resolve without a matching pointer') } })
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.match(text(f.ui), /Читаемый автор/); assert.doesNotMatch(text(f.ui), /Storage unavailable/)
})

test('sync overview loads on demand and exposes errors and statistics coverage', async t => {
    let reads = 0
    const f = await fixture(t, 'CrmSyncOverview', {}, { getCrmSyncOverview: async () => {
        reads++
        return { enabled: true, clients: { total: 100, missing: 20, fresh: 75, oldestat: null },
            stored: { masters: 7, services: 19, appointments: 123, intervals: 42 },
            datasets: [{ dataset: 'CLIENTS', scopes: 10, completed: 4, pending: 3, running: 2, failed: 1, due: 3,
                lastattemptat: '2026-11-20T01:00:00Z', nextat: '2026-11-20T01:10:00Z',
                datefrom: '2026-11-01', dateto: '2026-11-30', lastsuccessat: null, errorcode: 'CRM_REAUTH_REQUIRED' }] }
    } })
    assert.equal(reads, 0)
    await act(async () => f.ui.root.findByType('details').props.onToggle({ currentTarget: { open: true } }))
    assert.equal(reads, 1)
    assert.match(text(f.ui), /75.*100/)
    assert.match(text(f.ui), /Загружено в проект/)
    const cells = f.ui.root.findByType('tbody').findAllByType('td')
    assert.deepEqual(cells.slice(1, 6).map(cell => cell.children[0]), ['10', '4', '3', '2', '1'])
    assert.match(text(f.ui), /123/)
    assert.match(text(f.ui), /CRM_REAUTH_REQUIRED/)
    assert.equal(cells[6].children[0], '3')
    assert.match(text(f.ui), /2026-11-01/)
    assert.match(text(f.ui), /Наступил срок запуска/)
    await click(f.ui, 'Обновить состояние синхронизации')
    assert.equal(reads, 2)
})

test('client statistics refresh requests a separate batch', async t => {
    const f = await fixture(t, 'CrmClients')
    await click(f.ui, 'Обновить статистику')
    assert.equal(f.calls.filter(c => c[0] === 'refresh').length, 1)
    assert.ok(JSON.stringify(f.calls).includes('CLIENT_STATISTICS'))
})

test('CRM card avatar shows mirrored bytes and revokes the object URL on unmount', async t => {
    const created = [], revoked = []
    t.mock.method(URL, 'createObjectURL', () => { const url = `blob:${created.length}`; created.push(url); return url })
    t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url))
    const delivered = deferred()
    const Avatar = load('../crm/CrmClientAvatar.tsx', { '../../api/crmApi': { getCrmClientAvatarContent: () => delivered.promise } }).CrmClientAvatar
    let ui
    await act(async () => { ui = create(React.createElement(Avatar, { id: 'crm-1', avatarUrl: 'crm/avatars/1.gif', displayName: 'Иван CRM', size: 36 })) })
    assert.deepEqual(created, [], 'без байтов аватара object URL не создаётся')
    await act(async () => { delivered.resolve(new Blob(['gif'], { type: 'image/gif' })) })
    assert.equal(ui.root.findAllByType('img').length, 1)
    assert.deepEqual(created, ['blob:0'])
    await act(async () => { ui.unmount() })
    assert.deepEqual(revoked, ['blob:0'])
})

test('CRM card avatar keeps an initial when the mirror is absent or its bytes fail', async t => {
    const api = { '../../api/crmApi': { getCrmClientAvatarContent: async () => { throw Error('нет зеркала') } } }
    const Avatar = load('../crm/CrmClientAvatar.tsx', api).CrmClientAvatar
    let ui
    await act(async () => { ui = create(React.createElement(Avatar, { id: 'crm-2', avatarUrl: 'crm/avatars/2.gif', displayName: 'Иван CRM' })) })
    assert.equal(ui.root.findAllByType('img').length, 0)
    assert.match(text(ui), /И/)
    await act(async () => { ui.unmount() })
    await act(async () => { ui = create(React.createElement(Avatar, { id: 'crm-3', avatarUrl: null, displayName: null })) })
    assert.match(text(ui), /•/)
    await act(async () => { ui.unmount() })
})
