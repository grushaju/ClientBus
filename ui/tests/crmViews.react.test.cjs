const assert = require('node:assert/strict')
const { test } = require('node:test')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const scope = load('../../api/events/uiScope.ts')
const state = { dataset: 'CLIENTS', status: 'PARTIAL', freshness: 'CURRENT', completeness: 'UNKNOWN', lastSuccessAt: '2026-11-08T10:00:00Z', generation: 1 }
const project = { id: 'p', workspaceId: 'w', displayName: 'Салон', zoneId: 'Europe/Moscow', archived: false, enabled: true, connectionState: 'CONNECTED' }
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
async function fixture(t, name, props = {}, overrides = {}, admin = false) {
    const invalidations = new Map(), calls = []
    const api = { crmError: error => error.message,
        getCrmClients: async () => page([client]), getCrmClient: async () => client,
        getCrmClientCandidates: async () => [{ id: 'core', firstName: 'Кандидат', lastName: 'Клиент', phoneList: ['+79991234567'], phoneMatch: true }],
        linkCrmClient: async (row, id) => { calls.push(['link', row.version, id]); return { ...row, clientId: id, version: 8 } },
        createLinkedCrmClient: async (...args) => { calls.push(['create', ...args]); return client },
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
        './uiScope': scope, '../../api/events/uiScope': scope,
        '../../auth/AuthContext': { useAuth: () => auth }, '../../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: { id: 'w' }, workspaces: [{ id: 'w', name: 'Рабочее пространство' }] }) },
        'react-router-dom': { Link: props => React.createElement('a', props) },
    }
    const filename = ['CrmClients', 'CrmClientCard'].includes(name) ? 'CrmClients' : ['CrmAppointmentCard', 'CrmAppointmentRows'].includes(name) ? 'CrmAppointments' : name
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
