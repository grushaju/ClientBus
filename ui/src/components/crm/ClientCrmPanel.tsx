import { useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { ClientCrmProvisionForm } from './FindOrCreateCrmClient'
import { CrmClientRecovery, needsCrmRecoveryView } from './CrmClientRecovery'
import * as api from '../../api/crmApi'
import { CrmAppointmentRows } from './CrmAppointments'
import { CrmClientCard } from './CrmClients'
import { LinkedCrmClientActions } from './LinkedCrmClientActions'
import { CrmPeriodFields, crmHistoryPeriod, crmPeriodValid, useCrmAction, useCrmRead } from './CrmReadShared'

function ClientCrmContent({ clientId, workspaceId, compact }: { clientId: string; workspaceId?: string; compact: boolean }) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const data = useCrmRead('client-crm-links:' + clientId + ':' + (workspaceId ?? 'all'), workspaceId, signal => api.getClientCrmLinks(clientId, workspaceId, signal))
    const action = useCrmAction('client-crm-history:' + clientId, workspaceId)
    const [direction, setDirection] = useState('future'), [period, setPeriod] = useState<api.CrmPeriod | null>(null)
    const [selected, setSelected] = useState<api.CrmClientLink | null>(null)
    const [picking, setPicking] = useState(false), [projectId, setProjectId] = useState('')
    const [notice, setNotice] = useState('')
    const projects = useCrmRead('crm-projects:' + workspaceId, workspaceId, signal => workspaceId ? api.getCrmProjects(workspaceId, signal) : Promise.resolve([]))
    const available = projects.data?.filter(row => !row.archived) ?? []
    const project = available.find(row => row.id === projectId) ?? (available.length === 1 ? available[0] : undefined)
    const projectLinked = !!project && !!data.data?.some(link => link.project.id === project.id)
    const canManage = !!workspaceId && (isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === workspaceId && row.isSupervisor))
    const dates = period ?? crmHistoryPeriod(data.data?.map(link => link.project.zoneId) ?? [], direction)
    return <>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p>Загрузка связей CRM…</p>}
        {data.data && !data.data.length && <p>В этой области нет связи с клиентом CRM.</p>}
        {notice && <p role="status">{notice}</p>}
        {!workspaceId && <p><small>Выберите рабочее пространство, чтобы найти или создать клиента CRM.</small></p>}
        {canManage && !picking && <>
            {available.length > 1 && <label>CRM и филиал<select value={projectId} onChange={e => setProjectId(e.target.value)}><option value="">Выберите CRM</option>{available.map(row => <option key={row.id} value={row.id}>{row.provider} · {row.displayName}</option>)}</select></label>}
            {!projectLinked && <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={data.loading || !!data.error || !project || !project.enabled || project.connectionState !== 'CONNECTED'} onClick={() => setPicking(true)}>Найти или создать в CRM</button>}
        </>}
        {projects.error && <p role="alert">{projects.error} <button type="button" onClick={projects.reload}>Повторить загрузку CRM</button></p>}
        {canManage && projects.data?.filter(needsCrmRecoveryView).map(row => <CrmClientRecovery key={row.id + ':' + clientId} project={row} clientId={clientId}
            done={() => { setNotice('Связь CRM восстановлена · ' + row.displayName); data.reload() }} />)}
        {canManage && picking && project && !needsCrmRecoveryView(project) && !projectLinked && <ClientCrmProvisionForm key={project.id + ':' + clientId} clientId={clientId} project={project}
            linked={!!data.data?.some(link => link.project.id === project.id)} close={() => setPicking(false)} done={() => { setNotice('Карточка CRM связана с клиентом ClientBus · ' + project.displayName); setPicking(false); data.reload() }} />}
        <ul className="crm-client-links">{data.data?.map(link => <li key={link.client.id}><button type="button" className="crm-text-button" onClick={() => setSelected(link)}>{link.project.displayName} · {link.client.displayName}{link.project.archived ? ' · архив' : ''}</button>
            <LinkedCrmClientActions link={link} canManage={isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === link.project.workspaceId && row.isSupervisor)} open={() => setSelected(link)} changed={message => { setPicking(false); setNotice(message ?? 'Связь CRM обновлена'); data.reload() }} />
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
