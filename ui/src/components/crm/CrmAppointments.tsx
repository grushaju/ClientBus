import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import * as api from '../../api/crmApi'
import { useCrmPages } from './useCrmPages'
import { CrmAppointmentActions } from './CrmOperations'
import { CrmDialog, CrmPageStatus, CrmStatus, crmLocal, crmMoney, useCrmAction, useCrmRead } from './CrmReadShared'

export const crmStates: Record<string, string> = {
    APPOINTMENT: 'Визит', RESERVATION: 'Резерв', BLOCK: 'Служебный блок', UNKNOWN: 'Неизвестно',
    SCHEDULED: 'Запланирован', CANCELLED: 'Отменён', DELETED: 'Удалён в источнике',
    UNCONFIRMED: 'Не подтверждён', CONFIRMED: 'Подтверждён', NOT_RECORDED: 'Посещение не отмечено', ARRIVED: 'Пришёл', NO_SHOW: 'Не пришёл',
    UNPAID: 'Не оплачен', PARTIALLY_PAID: 'Частично оплачен', PAID: 'Оплачен',
}
function clientName(row: api.CrmAppointment) { return String(row.clientSnapshot?.displayName ?? row.clientSnapshot?.name ?? 'Клиент не указан') }
export function CrmCoverage({ rows, projects = [] }: { rows?: api.CrmCoverage[]; projects?: api.CrmProject[] }) {
    return <div className="crm-coverage">{!rows?.length ? <p>Покрытие периода не подтверждено. Пустой список не означает отсутствие визитов.</p> : rows.map(row => <div key={row.projectId}>
        <strong>{projects.find(project => project.id === row.projectId)?.displayName ?? 'Проект CRM'}</strong>
        {row.scopes.length ? row.scopes.map((scope, index) => <div key={index}><small>{scope.dataset === 'CLIENT_HISTORY' ? 'История клиента' : 'Журнал проекта'} · {String(scope.scope?.dateFrom ?? '')} — {String(scope.scope?.dateTo ?? '')}</small><CrmStatus state={scope} archived={projects.find(project => project.id === row.projectId)?.archived} /></div>) : <p>Для проекта нет подтверждённого покрытия этого периода.</p>}
    </div>)}</div>
}
const object = (value: unknown): Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
const records = (value: unknown) => Array.isArray(value) ? value.map(object) : []
function FinanceDetails({ row, archived, canRefresh }: { row: api.CrmAppointment; archived: boolean; canRefresh: boolean }) {
    const data = useCrmRead('crm-finance:' + row.id, row.workspaceId, signal => api.getCrmFinance(row.id, signal))
    const action = useCrmAction('crm-finance:' + row.id, row.workspaceId)
    const snapshot = data.data?.snapshot, totals = object(snapshot?.totals)
    return <section><h3>Финансовый контекст</h3>
        <button type="button" className="ui-button ui-button-secondary" disabled={!canRefresh || archived || action.busy} onClick={() => void action.run(signal => api.refreshCrmFinance(row.id, signal), data.reload)}>Обновить финансовые данные</button>
        {(data.error || action.error) && <p role="alert" className="crm-error">{action.error || data.error} <button type="button" onClick={data.reload}>Перечитать</button></p>}
        {data.data && <><CrmStatus state={data.data.sync} archived={archived} /><p>Полнота: {data.data.completeness === 'COMPLETE' ? 'подтверждена' : 'не подтверждена полностью'}</p>
            {snapshot ? <><dl className="crm-data-fields"><dt>Стоимость</dt><dd>{crmMoney(totals.cost, totals.currency)}</dd><dt>Оплачено</dt><dd>{crmMoney(totals.paid, totals.currency)}</dd><dt>Долг</dt><dd>{crmMoney(totals.debt, totals.currency)}</dd></dl>
                <h4>Услуги продажи</h4><ul>{records(snapshot.lines).map((line, i) => <li key={i}>Строка {i + 1}: цена {crmMoney(line.price, totals.currency)} · скидка {line.discountPercent == null ? 'неизвестна' : String(line.discountPercent) + '%'} · сумма скидки {crmMoney(line.discountAmount, totals.currency)} · итог {crmMoney(line.total, totals.currency)}</li>)}</ul>
                <h4>Оплаты</h4><ul>{records(snapshot.payments).map((payment, i) => <li key={i}>{String(payment.localDate ?? 'Дата неизвестна')}<ul>{records(payment.components).map((component, j) => <li key={j}>{String(component.method ?? 'Способ неизвестен')}: {crmMoney(component.amount, totals.currency)}</li>)}</ul></li>)}</ul>
            </> : <p>Сохранённых финансовых данных нет. Это не означает нулевую оплату.</p>}</>}
    </section>
}
export function CrmAppointmentCard({ id, workspaceId, archived, close, canRefresh = !archived, project }: { id: string; workspaceId?: string; archived: boolean; canRefresh?: boolean; close: () => void; project?: api.CrmProject }) {
    const data = useCrmRead('crm-appointment:' + id, workspaceId, signal => api.getCrmAppointment(id, signal))
    const [finance, setFinance] = useState(false), row = data.data
    return <CrmDialog title="Запись CRM" close={close}>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
        {row ? <><h3>{clientName(row)}</h3><p>{crmStates[row.kind] ?? row.kind} · {crmLocal(row.localStart)} — {crmLocal(row.localEnd)}</p><p>Часовой пояс: {row.zoneId ?? 'не указан'}</p>
            <p>{[row.appointmentState, row.confirmationState, row.attendanceState, row.paymentState].map(state => crmStates[state] ?? state).join(' · ')}</p>
            {row.clientId && <Link to={'/clients/' + encodeURIComponent(row.clientId)}>Карточка клиента ClientBus</Link>}
            <h3>Услуги визита</h3><ul>{row.services.map(item => <li key={item.id}>{item.displayName} · {crmMoney(item.price, item.currency)}{item.durationSeconds != null && ' · ' + item.durationSeconds / 60 + ' мин'}</li>)}</ul>
            {!row.servicesComplete && <p>Состав услуг получен не полностью.</p>}
            <h3>Мастера</h3><ul>{row.masters.map(item => <li key={item.id}>{item.displayName}{item.localStart && ' · ' + crmLocal(item.localStart)}</li>)}</ul>
            {!row.mastersComplete && <p>Состав мастеров получен не полностью.</p>}
            {row.comment && <p className="crm-prewrap">{row.comment}</p>}
            {row.requiresVerification && <p className="crm-notice">Данные требуют проверки источника.</p>}
            {row.observationState === 'NOT_OBSERVED' && <p>Запись отсутствует в последнем наблюдении. Это не подтверждает отмену.</p>}
            {row.warnings.length > 0 && <details><summary>Предупреждения источника</summary><ul>{row.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul></details>}
            {row.lastObservedAt && <p>Последнее наблюдение: {new Date(row.lastObservedAt).toLocaleString('ru-RU')}</p>}
            {row.kind === 'APPOINTMENT' && <><button type="button" className="ui-button ui-button-secondary" aria-expanded={finance} onClick={() => setFinance(!finance)}>Финансы визита</button>{finance && <FinanceDetails key={row.id} row={row} archived={archived} canRefresh={canRefresh} />}</>}
            {project && <CrmAppointmentActions project={project} row={row} changed={data.reload} />}
            {archived && <p>Архив: доступен только просмотр сохранённых данных.</p>}
        </> : !data.error && <p>Загрузка записи…</p>}
    </CrmDialog>
}
export function CrmAppointmentRows({ project, clientId, query, compact = false, links = [], direction, refresh = 0 }: { project?: api.CrmProject; clientId?: string; query: api.CrmAppointmentQuery; compact?: boolean; links?: api.CrmClientLink[]; direction?: string; refresh?: number }) {
    const data = useCrmPages('crm-appointments:' + (project?.id ?? clientId) + ':' + JSON.stringify(query), project?.workspaceId ?? query.workspaceId ?? '',
        (signal, cursor) => project ? api.getCrmAppointments(project.id, { ...query, cursor }, signal) : api.getClientCrmAppointments(clientId!, { ...query, cursor }, signal))
    const previousRefresh = useRef(refresh)
    useEffect(() => {
        // A new query already starts its own read; only a later command requires another one.
        if (previousRefresh.current !== refresh) { previousRefresh.current = refresh; data.reload() }
    }, [refresh])
    const [selected, setSelected] = useState<api.CrmAppointment | null>(null)
    const visible = data.page?.items.filter(row => {
        const time = row.startAt ? Date.parse(row.startAt) : NaN
        return !direction || !Number.isFinite(time) || (direction === 'future' ? time >= Date.now() : time < Date.now())
    })
    const searching = compact && !!data.page?.hasMore && (visible?.length ?? 0) < 3
    useEffect(() => {
        if (searching && !data.loading && !data.more && !data.error) void data.loadMore()
    }, [searching, data.page, data.loading, data.more, data.error, data.loadMore])
    const selectedProject = project ?? links.find(link => link.project.id === selected?.projectId)?.project
    const coverage = <CrmCoverage rows={data.page?.coverage} projects={project ? [project] : links.map(link => link.project)} />
    const fullQuery = new URLSearchParams({ tab: 'appointments', dateFrom: query.dateFrom, dateTo: query.dateTo })
    if (query.workspaceId) fullQuery.set('workspace', query.workspaceId)
    if (clientId) fullQuery.set('clientId', clientId)
    return <>{compact ? <details><summary>Загрузка и полнота данных</summary>{coverage}</details> : coverage}
        <ul className="crm-appointments">{(compact ? visible?.slice(0, 3) : visible)?.map((row, index, rows) => <li key={row.id}>
            {(index === 0 || rows[index - 1].localStart.slice(0, 10) !== row.localStart.slice(0, 10)) && <h3>{row.localStart.slice(0, 10)}</h3>}
            <button type="button" className="crm-appointment-row" onClick={() => setSelected(row)}><strong>{crmLocal(row.localStart)} · {clientName(row)}</strong>
                <span>{row.services.map(item => item.displayName).join(', ') || 'Услуги не указаны'}</span><span>{row.masters.map(item => item.displayName).join(', ') || 'Мастер не указан'}</span>
                <small>{crmStates[row.kind] ?? row.kind} · {crmStates[row.appointmentState] ?? row.appointmentState} · {crmStates[row.paymentState] ?? row.paymentState} · {row.zoneId ?? 'Часовой пояс неизвестен'}</small>
                {!row.startAt && <small>Точное время не подтверждено; принадлежность к прошлым или будущим визитам неизвестна.</small>}
                {!project && <small>{links.find(link => link.project.id === row.projectId)?.project.displayName ?? 'Проект CRM'}</small>}
            </button>
        </li>)}</ul>{compact && !!data.page?.items.length && <Link to={'/crm?' + fullQuery}>Все визиты во вкладке «Записи»</Link>}
        {!compact ? <CrmPageStatus data={{ ...data, page: data.page ? { ...data.page, items: visible ?? [] } : null }} empty="В загруженных данных подходящие записи не найдены." /> : <>{data.error && <p role="alert">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}{data.loading && !data.page && <p>Загрузка визитов…</p>}{searching && !data.error && <p role="status">Поиск ближайших визитов…</p>}{data.page && !searching && !visible?.length && !data.error && <p>В загруженных данных ближайшие визиты не найдены.</p>}</>}
        {selected && <CrmAppointmentCard key={selected.id} id={selected.id} workspaceId={selected.workspaceId} project={selectedProject} archived={selectedProject?.archived ?? true} canRefresh={!!selectedProject && !selectedProject.archived && selectedProject.enabled && !!selectedProject.zoneId && !['DISABLED', 'REAUTH_REQUIRED'].includes(selectedProject.connectionState)} close={() => setSelected(null)} />}
    </>
}
