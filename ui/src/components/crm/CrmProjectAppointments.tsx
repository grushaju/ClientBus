import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import * as api from '../../api/crmApi'
import { CrmAppointmentRows, crmStates } from './CrmAppointments'
import { CrmDirectoryPicker } from './CrmDirectoryPicker'
import { CrmOperationsPanel } from './CrmOperations'
import { CrmPeriodFields, crmPeriodValid, crmShift, crmToday, useCrmAction, useCrmRead } from './CrmReadShared'

function CoreAppointments({ clientId, project, query }: { clientId: string; project: api.CrmProject; query: api.CrmAppointmentQuery }) {
    const links = useCrmRead('appointment-client-links:' + clientId + ':' + project.workspaceId, project.workspaceId, signal => api.getClientCrmLinks(clientId, project.workspaceId, signal))
    return <><p>Визиты всех CRM-карточек этого клиента в выбранном Workspace. <Link to={'/clients/' + encodeURIComponent(clientId)}>Карточка клиента</Link></p>
        {links.error && <p role="alert">{links.error} <button type="button" onClick={links.reload}>Повторить</button></p>}
        {links.data && <CrmAppointmentRows clientId={clientId} query={{ ...query, workspaceId: project.workspaceId }} links={links.data} />}</>
}

export function CrmProjectAppointments({ project }: { project: api.CrmProject }) {
    const [refresh, setRefresh] = useState(0)
    const [params, setParams] = useSearchParams()
    const today = crmToday(project.zoneId), period = { dateFrom: params.get('dateFrom') ?? today, dateTo: params.get('dateTo') ?? crmShift(today, 6) }
    const change = (value: Record<string, string>) => { const next = new URLSearchParams(params); Object.entries(value).forEach(([key, item]) => item ? next.set(key, item) : next.delete(key)); setParams(next) }
    const filters = ['kind', 'appointmentState', 'confirmationState', 'attendanceState', 'paymentState', 'masterId', 'crmClientId', 'externalClientId'] as const
    const query: api.CrmAppointmentQuery = { ...period, ...Object.fromEntries(filters.map(key => [key, params.get(key) || undefined])) }
    const action = useCrmAction('refresh-appointments:' + project.id, project.workspaceId)
    const clientId = params.get('clientId')
    const choices: [string, string, string[]][] = [
        ['kind', 'Вид записи', ['APPOINTMENT', 'RESERVATION', 'BLOCK', 'UNKNOWN']],
        ['appointmentState', 'Состояние визита', ['SCHEDULED', 'CANCELLED', 'DELETED', 'UNKNOWN']],
        ['confirmationState', 'Подтверждение', ['UNCONFIRMED', 'CONFIRMED', 'UNKNOWN']],
        ['attendanceState', 'Посещение', ['NOT_RECORDED', 'ARRIVED', 'NO_SHOW', 'UNKNOWN']],
        ['paymentState', 'Оплата', ['UNPAID', 'PARTIALLY_PAID', 'PAID', 'UNKNOWN']],
    ]
    return <><CrmOperationsPanel project={project} /><CrmPeriodFields value={period} change={value => change({ ...value })} />
        {clientId && <button type="button" className="ui-button ui-button-secondary" onClick={() => change({ clientId: '' })}>Показать всех клиентов</button>}
        <div className="crm-toolbar crm-appointment-filters">{!clientId && <><CrmDirectoryPicker project={project} kind="master" value={query.masterId ?? ''} change={masterId => change({ masterId })} />
            <CrmDirectoryPicker project={project} kind="client" period={period} disabled={!crmPeriodValid(period)} value={query.externalClientId ?? ''} legacyClientId={query.crmClientId} change={externalClientId => change({ externalClientId, crmClientId: '' })} /></>}
            {choices.map(([key, label, values]) => <label key={key}>{label}<select value={params.get(key) ?? ''} onChange={event => change({ [key]: event.target.value })}><option value="">Все</option>{values.map(value => <option key={value} value={value}>{crmStates[value]}</option>)}</select></label>)}
        </div>
        <p>Часовой пояс: {project.zoneId ?? 'не указан; показано исходное местное время'}</p>
        {!clientId && <div className="crm-actions">{[false, true].map(force => <button key={String(force)} type="button" className="ui-button ui-button-secondary" disabled={!crmPeriodValid(period) || action.busy || project.archived || !project.enabled || !project.zoneId || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
            onClick={() => void action.run(signal => api.refreshCrmData(project.id, { dataset: 'APPOINTMENTS', ...period, ...(force ? { force: true } : {}) }, signal), () => setRefresh(value => value + 1))}>{force ? 'Запросить свежие данные' : 'Обновить записи периода'}</button>)}</div>}
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}
        {crmPeriodValid(period) && (clientId ? <CoreAppointments key={clientId + ':' + JSON.stringify(query)} clientId={clientId} project={project} query={query} /> : <CrmAppointmentRows key={project.id + ':' + JSON.stringify(query)} project={project} query={query} refresh={refresh} />)}
    </>
}
