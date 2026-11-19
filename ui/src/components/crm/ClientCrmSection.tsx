import { useState } from 'react'
import * as api from '../../api/crmApi'
import { useAuth } from '../../auth/AuthContext'
import { CrmClientAvatar } from './CrmClientAvatar'
import { CrmClientCard } from './CrmClients'
import { useCrmAction, useCrmRead, useCrmSearch } from './CrmReadShared'

/**
 * Секция «CRM» панели диалога: карточки клиентов CRM, привязанных к этому клиенту, и ручная
 * привязка существующего клиента CRM. Визитов здесь нет — они перегружают панель, для них есть
 * `/crm?tab=appointments`. Связь ставит и снимает только человек: совпадение телефона — подсказка,
 * остальное (имя, аватар) ничего не доказывает.
 */
const digits = (value: string) => value.replace(/\D/g, '').slice(-10)
const phoneHit = (phones: string[], candidate: string | null) =>
    !!candidate && phones.some(value => digits(value).length >= 10 && digits(value) === digits(candidate))

function LinkPicker({ clientId, project, phones, cancel, done }: {
    clientId: string; project: api.CrmProject; phones: string[]; cancel: () => void; done: () => void
}) {
    const [input, setInput] = useState(''), query = useCrmSearch(input)
    const data = useCrmRead('crm-clients-to-link:' + project.id + ':' + query, project.workspaceId,
        signal => api.getCrmClients(project.id, { q: query, unlinked: true }, signal))
    const action = useCrmAction('crm-client-link:' + project.id, project.workspaceId)
    return <div className="crm-form">
        <label>Найти клиента CRM<input type="search" maxLength={160} value={input} onChange={event => setInput(event.target.value)} /></label>
        <p><small>Привязка ручная. Совпадение телефона — подсказка, а не основание: связь подтверждает сотрудник.</small></p>
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}
        {data.data?.items.map(row => <div key={row.id} className="crm-client-pick-row">
            <span>{row.displayName} · {row.normalizedPhone ?? 'без телефона'} · ID {row.externalId}</span>
            {phoneHit(phones, row.normalizedPhone) && <span className="crm-notice">телефон совпадает</span>}
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy}
                onClick={() => void action.run(signal => api.linkCrmClient(row, clientId, signal), done)}>Привязать</button>
        </div>)}
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p><small>Ищем клиентов CRM…</small></p>}
        {data.data && !data.data.items.length && <p><small>Свободных клиентов CRM не найдено.</small></p>}
        <div className="crm-actions"><button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={cancel}>Отмена</button></div>
    </div>
}

function LinkedClients({ clientId, workspaceId, phones, canManage, openCard }: {
    clientId: string; workspaceId: string; phones: string[]; canManage: boolean; openCard: (link: api.CrmClientLink) => void
}) {
    const data = useCrmRead('client-crm-links:' + clientId + ':' + workspaceId, workspaceId,
        signal => api.getClientCrmLinks(clientId, workspaceId, signal))
    const projects = useCrmRead('crm-projects:' + workspaceId, workspaceId, signal => api.getCrmProjects(workspaceId, signal))
    const [picking, setPicking] = useState(false)
    const project = projects.data?.find(row => !row.archived)
    return <>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p className="conversation-client-panel-state">Загрузка клиентов CRM…</p>}
        {!!data.data?.length && <ul className="crm-client-cards">{data.data.map(link => <li key={link.client.id}>
            <CrmClientAvatar id={link.client.id} avatarUrl={link.client.avatarUrl} displayName={link.client.displayName} size={36} />
            <div className="crm-client-card-main"><strong>{link.client.displayName}</strong>
                <small>{link.project.displayName}{link.project.archived ? ' · архив' : ''} · ID {link.client.externalId}</small>
                <small>{link.client.normalizedPhone ?? 'Телефон неизвестен'}</small></div>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={() => openCard(link)}>Карточка</button>
        </li>)}</ul>}
        {data.data && !data.data.length && !picking && <p><small>Клиент пока не связан с карточкой CRM.</small></p>}
        {canManage && !picking && <div className="crm-actions"><button type="button" className="ui-button ui-button-sm ui-button-secondary"
            disabled={!project || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
            onClick={() => setPicking(true)}>Привязать клиента CRM</button></div>}
        {canManage && picking && project && <LinkPicker clientId={clientId} project={project} phones={phones}
            cancel={() => setPicking(false)} done={() => { setPicking(false); data.reload() }} />}
    </>
}

export default function ClientCrmSection({ clientId, workspaceId, phones, visible = true }: {
    clientId: string; workspaceId: string; phones: string[]; visible?: boolean
}) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const [open, setOpen] = useState(false), [selected, setSelected] = useState<api.CrmClientLink | null>(null)
    const canManage = isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === workspaceId && row.isSupervisor)
    return <details className="crm-panel crm-client-context" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
        <summary>CRM</summary>
        {open && visible && <LinkedClients clientId={clientId} workspaceId={workspaceId} phones={phones} canManage={canManage}
            openCard={setSelected} />}
        {selected && <CrmClientCard key={selected.client.id} project={selected.project} id={selected.client.id} canManage={canManage}
            close={() => setSelected(null)} changed={() => setSelected(null)} />}
    </details>
}
