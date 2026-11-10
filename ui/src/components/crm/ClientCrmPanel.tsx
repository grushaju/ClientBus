import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import * as api from '../../api/crmApi'
import { CrmAppointmentRows } from './CrmAppointments'
import { CrmClientCard } from './CrmClients'
import { CrmPeriodFields, crmHistoryPeriod, crmPeriodValid, useCrmAction, useCrmRead } from './CrmReadShared'

function ClientCrmContent({ clientId, workspaceId, compact }: { clientId: string; workspaceId?: string; compact: boolean }) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const data = useCrmRead('client-crm-links:' + clientId + ':' + (workspaceId ?? 'all'), workspaceId, signal => api.getClientCrmLinks(clientId, workspaceId, signal))
    const action = useCrmAction('client-crm-history:' + clientId, workspaceId)
    const [direction, setDirection] = useState('future'), [period, setPeriod] = useState<api.CrmPeriod | null>(null)
    const [selected, setSelected] = useState<api.CrmClientLink | null>(null)
    const dates = period ?? crmHistoryPeriod(data.data?.map(link => link.project.zoneId) ?? [], direction)
    return <>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p>Загрузка связей CRM…</p>}
        {data.data && !data.data.length && <p>В этой области нет связи с клиентом CRM. <Link to={'/crm?' + new URLSearchParams({ ...(workspaceId ? { workspace: workspaceId } : {}), tab: 'clients' })}>Выбрать клиента CRM</Link></p>}
        <ul className="crm-client-links">{data.data?.map(link => <li key={link.client.id}><button type="button" className="crm-text-button" onClick={() => setSelected(link)}>{link.project.displayName} · {link.client.displayName}{link.project.archived ? ' · архив' : ''}</button>
            {!compact && [false, true].map(force => <button key={String(force)} type="button" className="ui-button ui-button-secondary" disabled={action.busy || link.project.archived || !link.project.enabled || !link.project.zoneId || !crmPeriodValid(dates) || ['DISABLED', 'REAUTH_REQUIRED'].includes(link.project.connectionState)}
                onClick={() => void action.run(signal => api.refreshCrmData(link.project.id, { dataset: 'CLIENT_HISTORY', crmClientId: link.client.id, ...dates, ...(force ? { force: true } : {}) }, signal), data.reload)}>{force ? 'Запросить свежую историю' : 'Обновить историю'}</button>)}
        </li>)}</ul>
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}
        {!!data.data?.length && <><div className="crm-actions"><button type="button" className="ui-button ui-button-secondary" aria-pressed={direction === 'future'} onClick={() => { setDirection('future'); setPeriod(null) }}>Будущие визиты</button>
            {!compact && <button type="button" className="ui-button ui-button-secondary" aria-pressed={direction === 'past'} onClick={() => { setDirection('past'); setPeriod(null) }}>Прошлые визиты</button>}</div>
            {!compact && <CrmPeriodFields value={dates} change={setPeriod} />}
            {crmPeriodValid(dates) && <CrmAppointmentRows key={clientId + ':' + workspaceId + ':' + direction + ':' + JSON.stringify(dates)} clientId={clientId} query={{ ...dates, workspaceId }} compact={compact} links={data.data} direction={period ? undefined : direction} />}
        </>}
        {selected && <CrmClientCard key={selected.client.id} project={data.data?.find(link => link.client.id === selected.client.id)?.project ?? selected.project} id={selected.client.id}
            canManage={isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === selected.project.workspaceId && row.isSupervisor)} close={() => setSelected(null)} changed={data.reload} />}
    </>
}
export default function ClientCrmPanel({ clientId, workspaceId, compact = false, visible = true }: { clientId: string; workspaceId?: string; compact?: boolean; visible?: boolean }) {
    const { currentWorkspace, workspaces } = useWorkspace(), { currentEmployee, sessionEpoch } = useAuth()
    const [open, setOpen] = useState(false), [selectedWorkspace, setSelectedWorkspace] = useState(workspaceId ?? currentWorkspace?.id ?? '')
    const workspace = compact ? workspaceId : selectedWorkspace || undefined
    return <details className="crm-panel crm-client-context" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
        <summary>CRM и визиты</summary>
        {open && visible && <>{!compact && <label>Рабочее пространство истории<select value={selectedWorkspace} onChange={event => setSelectedWorkspace(event.target.value)}><option value="">Все доступные</option>{workspaces.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</select></label>}
            <ClientCrmContent key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, clientId, workspace].join(':')} clientId={clientId} workspaceId={workspace} compact={compact} />
        </>}
    </details>
}
