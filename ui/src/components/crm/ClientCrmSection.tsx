import { useState } from 'react'
import { ClientCrmProvisionForm } from './FindOrCreateCrmClient'
import { CrmClientRecovery, needsCrmRecoveryView } from './CrmClientRecovery'
import * as api from '../../api/crmApi'
import { useAuth } from '../../auth/AuthContext'
import { CrmClientAvatar } from './CrmClientAvatar'
import { CrmClientCard } from './CrmClients'
import { useCrmRead } from './CrmReadShared'
import { LinkedCrmClientActions } from './LinkedCrmClientActions'

/**
 * Секция «CRM» панели диалога: карточки клиентов CRM, привязанных к этому клиенту, и ручная
 * привязка существующего клиента CRM. Визитов здесь нет — они перегружают панель, для них есть
 * `/crm?tab=appointments`. Связь ставит и снимает только человек: совпадение телефона — подсказка,
 * остальное (имя, аватар) ничего не доказывает.
 */
function LinkedClients({ clientId, workspaceId, canManage, openCard }: {
    clientId: string; workspaceId: string; canManage: boolean; openCard: (link: api.CrmClientLink) => void
}) {
    const data = useCrmRead('client-crm-links:' + clientId + ':' + workspaceId, workspaceId,
        signal => api.getClientCrmLinks(clientId, workspaceId, signal))
    const projects = useCrmRead('crm-projects:' + workspaceId, workspaceId, signal => api.getCrmProjects(workspaceId, signal))
    const [picking, setPicking] = useState(false)
    const [notice, setNotice] = useState('')
    const [projectId, setProjectId] = useState('')
    const available = projects.data?.filter(row => !row.archived) ?? []
    const project = available.find(row => row.id === projectId) ?? (available.length === 1 ? available[0] : undefined)
    const projectLinked = !!project && !!data.data?.some(link => link.project.id === project.id)
    return <>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p className="conversation-client-panel-state">Загрузка клиентов CRM…</p>}
        {!!data.data?.length && <ul className="crm-client-cards">{data.data.map(link => <li key={link.client.id}>
            <CrmClientAvatar id={link.client.id} avatarUrl={link.client.avatarUrl} displayName={link.client.displayName} size={36} />
            <div className="crm-client-card-main"><strong>{link.client.displayName}</strong>
                <small>{link.project.displayName}{link.project.archived ? ' · архив' : ''} · ID {link.client.externalId}</small>
                <small>{link.client.normalizedPhone ?? 'Телефон неизвестен'}</small></div>
            <LinkedCrmClientActions link={link} canManage={canManage} open={() => openCard(link)} changed={message => { setPicking(false); setNotice(message ?? 'Связь CRM обновлена'); data.reload() }} />
        </li>)}</ul>}
        {data.data && !data.data.length && !picking && <p><small>Клиент пока не связан с карточкой CRM.</small></p>}
        {notice && <p role="status">{notice}</p>}
        {projects.error && <p role="alert">{projects.error} <button type="button" onClick={projects.reload}>Повторить загрузку CRM</button></p>}
        {canManage && available.length > 1 && !picking && <label>CRM и филиал<select value={projectId} onChange={e => setProjectId(e.target.value)}><option value="">Выберите CRM</option>{available.map(row => <option key={row.id} value={row.id}>{row.provider} · {row.displayName}</option>)}</select></label>}
        {canManage && !picking && !projectLinked && <div className="crm-actions"><button type="button" className="ui-button ui-button-sm ui-button-secondary"
            disabled={data.loading || !!data.error || !project || !project.enabled || project.connectionState !== 'CONNECTED'}
            onClick={() => setPicking(true)}>Найти или создать в CRM</button></div>}
        {canManage && projects.data?.filter(needsCrmRecoveryView).map(row => <CrmClientRecovery key={row.id + ':' + clientId} project={row} clientId={clientId}
            done={() => { setNotice('Связь CRM восстановлена · ' + row.displayName); data.reload() }} />)}
        {canManage && picking && project && !needsCrmRecoveryView(project) && !projectLinked && <ClientCrmProvisionForm key={project.id + ':' + clientId} clientId={clientId} project={project}
            linked={!!data.data?.some(link => link.project.id === project.id)} close={() => setPicking(false)}
            done={() => { setNotice('Карточка CRM связана с клиентом ClientBus · ' + project.displayName); setPicking(false); data.reload() }} />}
    </>
}

export default function ClientCrmSection({ clientId, workspaceId, visible = true }: {
    clientId: string; workspaceId: string; phones: string[]; visible?: boolean
}) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const [open, setOpen] = useState(false), [selected, setSelected] = useState<api.CrmClientLink | null>(null)
    const canManage = isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === workspaceId && row.isSupervisor)
    return <details className="crm-panel crm-client-context" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
        <summary>CRM</summary>
        {open && visible && <LinkedClients clientId={clientId} workspaceId={workspaceId} canManage={canManage}
            openCard={setSelected} />}
        {selected && <CrmClientCard key={selected.client.id} project={selected.project} id={selected.client.id} canManage={canManage}
            close={() => setSelected(null)} changed={() => setSelected(null)} />}
    </details>
}
