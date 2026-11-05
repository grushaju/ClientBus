import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { getApiErrorCode } from '../../api/apiClient'
import * as api from '../../api/crmApi'
import { crmLocation, ReturnToCrm, recoveryQuery } from './CrmShared'
import CrmTimeZoneSelect from './CrmTimeZoneSelect'
import './crm.css'

interface Props { workspaceId: string }
function WorkspaceCrmContent({ workspaceId }: Props) {
    const { isSuperAdmin } = useAuth(), scope = useUiScope()
    const [params] = useSearchParams()
    const [projects, setProjects] = useState<api.CrmProject[]>([]), [organizationProjects, setOrganizationProjects] = useState<api.CrmProject[]>([])
    const [connections, setConnections] = useState<api.CrmConnection[]>([])
    const [connectionId, setConnectionId] = useState(params.get('connection') ?? ''), [externalId, setExternalId] = useState(''), [zone, setZone] = useState('')
    const [preview, setPreview] = useState<api.CrmProjectCheck | null>(null), [editing, setEditing] = useState<api.CrmProject | null>(null)
    const [confirm, setConfirm] = useState<string | null>(null)
    const [busy, setBusy] = useState(false), [loading, setLoading] = useState(true)
    const [readError, setReadError] = useState<string | null>(null), [actionError, setActionError] = useState<string | null>(null), [notice, setNotice] = useState('')
    const mutation = useRef<AbortController>(), panel = useRef<HTMLElement>(null), scrolled = useRef(false)
    const resource = useUiResource('workspace-crm-settings:' + workspaceId, isSuperAdmin,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) || event.type === 'crm.invalidated'),
        async signal => {
            const [projects, connections, organizationProjects] = await Promise.all([api.getCrmProjects(workspaceId, signal), api.getCrmConnections(signal), api.getOrganizationCrmProjects(signal)])
            return { projects, connections, organizationProjects }
        }, value => { setProjects(value.projects); setConnections(value.connections); setOrganizationProjects(value.organizationProjects); setReadError(null) },
        error => setReadError(api.crmError(error)),
        () => { setProjects([]); setConnections([]); setOrganizationProjects([]); setEditing(null); setPreview(null); setBusy(false); setConfirm(null); mutation.current?.abort(); mutation.current = undefined }, setLoading)
    useEffect(() => {
        setBusy(false); setActionError(null); setEditing(null); setPreview(null); setConfirm(null)
        return () => { mutation.current?.abort(); mutation.current = undefined }
    }, [scope])
    useEffect(() => {
        if (!loading && !scrolled.current && typeof location !== 'undefined' && location.hash === '#crm') {
            scrolled.current = true; panel.current?.scrollIntoView({ block: 'start' }); panel.current?.focus({ preventScroll: true })
        }
    }, [loading])
    async function run<T,>(action: (signal: AbortSignal) => Promise<T>, apply?: (value: T) => void) {
        if (mutation.current) return
        const current = resource.capture(), controller = new AbortController()
        mutation.current = controller; setBusy(true); setActionError(null); setNotice('')
        try {
            const result = await action(controller.signal)
            if (current() && !controller.signal.aborted) { apply?.(result); resource.reload() }
        } catch (error) { if (current() && !controller.signal.aborted) {
            if (getApiErrorCode(error) === 'CRM_VERSION_CONFLICT') { setEditing(null); setConfirm(null) }
            setActionError(api.crmError(error)); resource.reload()
        } }
        finally { if (mutation.current === controller) mutation.current = undefined; if (current() && !controller.signal.aborted) setBusy(false) }
    }
    const occupied = projects.some(row => row.provider === 'DIKIDI')
    const connection = connections.find(row => row.id === connectionId)
    const unusable = !connection || ['DISABLED', 'REAUTH_REQUIRED'].includes(connection.status)
    const conflict = organizationProjects.find(row => row.provider === connection?.provider && row.externalId === externalId.trim() && row.workspaceId !== workspaceId)
    function check(event: FormEvent) {
        event.preventDefault(); setPreview(null)
        if (unusable || conflict || occupied) return
        void run(signal => api.checkCrmProject(connectionId, externalId.trim(), signal), setPreview)
    }
    function saveZone(project: api.CrmProject) {
        void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, zoneId: zone.trim() }, signal),
            () => { setEditing(null); setNotice('Часовой пояс сохранён.') })
    }
    if (!isSuperAdmin) return null
    return <section id="crm" ref={panel} tabIndex={-1} className="crm-panel" aria-label="Настройки CRM рабочего пространства">
        <div className="crm-heading"><h2>Проекты CRM</h2><ReturnToCrm /></div>
        {(actionError || readError) && <p role="alert" className="crm-error">{actionError || readError}</p>}
        {notice && <p role="status">{notice}</p>}
        {loading && !projects.length && <p role="status">Загрузка CRM…</p>}
        {projects.map(project => <article className="crm-project-card" key={project.id}>
            <div className="crm-heading"><h3>{project.displayName}</h3><Link to={crmLocation(workspaceId, project.id)}>Открыть каталог</Link></div>
            <p>{project.provider} · ID {project.externalId} · {connections.find(row => row.id === project.connectionId) ? api.crmAccountName(connections.find(row => row.id === project.connectionId)!) : 'Аккаунт CRM'}
                <span className="crm-state">{api.crmConnectionLabel(project.connectionState)}</span></p>
            <p>Обновление: {project.enabled ? 'включено' : 'выключено'} · Часовой пояс: {project.zoneId ?? 'не указан'}</p>
            {!project.zoneId && editing?.id !== project.id && <div className="crm-notice crm-time-zone-field">
                <label>Часовой пояс проекта<CrmTimeZoneSelect label="Часовой пояс проекта" value={zone} disabled={busy || loading} onChange={setZone} /></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading || !zone} onClick={() => saveZone(project)}>Сохранить часовой пояс</button>
            </div>}
            {['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState) && <Link to={`/settings?section=crm&account=${encodeURIComponent(project.connectionId)}&${recoveryQuery(project, params.get('returnTab') ?? 'services')}`}>
                {project.connectionState === 'DISABLED' ? 'Включить аккаунт' : 'Восстановить доступ к аккаунту'}</Link>}
            <div className="crm-actions">
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => { setEditing(project); setZone(project.zoneId ?? ''); setConnectionId(project.connectionId) }}>Настроить проект</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => project.enabled ? setConfirm(project.id)
                    : void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, enabled: true }, signal), () => setNotice('Обновление проекта включено.'))}>{project.enabled ? 'Выключить обновление' : 'Включить обновление'}</button>
            </div>
            {confirm === project.id && <div className="crm-notice"><p>Выключить обновление проекта? Привязка, история и связи сохранятся. Другой проект DIKIDI подключить сюда не получится.</p>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, enabled: false }, signal), () => { setConfirm(null); setNotice('Обновление проекта выключено.') })}>Подтвердить выключение</button>{' '}
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setConfirm(null)}>Отмена</button></div>}
            {editing?.id === project.id && <div className="crm-form">
                <label>Часовой пояс<CrmTimeZoneSelect label="Изменить часовой пояс" value={zone} disabled={busy} onChange={setZone} /></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || zone.trim() === (editing.zoneId ?? '')}
                    onClick={() => saveZone(editing)}>Сохранить часовой пояс</button>
                <label>Аккаунт для доступа<select aria-label="Новое подключение DIKIDI" value={connectionId} disabled={busy} onChange={event => setConnectionId(event.target.value)}>
                    {connections.filter(row => row.provider === project.provider).map(row => <option key={row.id} value={row.id} disabled={['DISABLED', 'REAUTH_REQUIRED'].includes(row.status)}>{api.crmAccountName(row)} · {api.crmConnectionLabel(row.status)}</option>)}</select></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || unusable || connectionId === editing.connectionId}
                    onClick={() => void run(signal => api.changeCrmProjectConnection(editing.id, connectionId, editing.version, signal), () => { setEditing(null); setNotice('Доступ к проекту обновлён.') })}>Сменить аккаунт</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setEditing(null)}>Закрыть настройки</button>
            </div>}
        </article>)}
        {!loading && !readError && !occupied && <>
            <p>Подключите проект DIKIDI к этому рабочему пространству.</p>
            {connections.length === 0 ? <Link className="ui-button ui-button-primary" to={`/settings?section=crm&returnWorkspace=${encodeURIComponent(workspaceId)}`}>Добавить аккаунт CRM</Link>
                : <form className="crm-form" onSubmit={check}>
                    <h3>{preview ? '2. Подтверждение проекта' : '1. Выберите аккаунт и проект'}</h3>
                    <label>Аккаунт<select aria-label="Подключение DIKIDI" required value={connectionId} disabled={busy || !!preview} onChange={event => { setConnectionId(event.target.value); setPreview(null) }}>
                        <option value="">Выберите аккаунт</option>{connections.filter(row => row.provider === 'DIKIDI').map(row => <option key={row.id} value={row.id}>{api.crmAccountName(row)} · {api.crmConnectionLabel(row.status)}</option>)}</select></label>
                    {connection && unusable ? <p className="crm-notice">{connection.status === 'DISABLED' ? 'Аккаунт выключен.' : 'Требуется повторный вход.'}{' '}
                        <Link to={`/settings?section=crm&account=${encodeURIComponent(connection.id)}&returnWorkspace=${encodeURIComponent(workspaceId)}`}>Настроить аккаунт</Link></p>
                        : <label>ID проекта DIKIDI<input aria-label="ID проекта DIKIDI" value={externalId} inputMode="numeric" maxLength={20} required pattern="[1-9][0-9]*" disabled={busy || !!preview || !connection}
                            onChange={event => { setExternalId(event.target.value); setPreview(null) }} /></label>}
                    {conflict && <p role="alert" className="crm-notice">Этот проект уже связан с другим пространством. <Link to={`/workspaces/${encodeURIComponent(conflict.workspaceId)}#crm`}>Открыть его настройки</Link></p>}
                    {!preview && <button className="ui-button ui-button-secondary" disabled={busy || unusable || !externalId.trim() || !!conflict}>Проверить проект</button>}
                    {preview && <div className="crm-form"><div className="crm-notice"><strong>{preview.displayName}</strong><p>ID {preview.externalId} · Валюта: {preview.currency ?? 'не указана'}</p></div>
                        <label>Часовой пояс проекта<CrmTimeZoneSelect label="Часовой пояс проекта" value={zone} disabled={busy} onChange={setZone} /></label>
                        <div className="crm-actions"><button type="button" className="ui-button ui-button-primary" disabled={busy || unusable || !!conflict} onClick={() => void run(signal => api.bindCrmProject(workspaceId,
                            { connectionId, externalProjectId: preview.externalId, ...(zone.trim() ? { zoneId: zone.trim() } : {}) }, signal), () => { setPreview(null); setExternalId(''); setZone(''); setNotice('Проект подключён. Каталог загружается.') })}>Привязать проект</button>
                            <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setPreview(null)}>Назад</button></div>
                    </div>}
                </form>}
        </>}
        {readError && <button type="button" className="ui-button ui-button-secondary" onClick={resource.reload}>Повторить загрузку</button>}
    </section>
}
export default function WorkspaceCrmPanel(props: Props) {
    const { currentEmployee, sessionEpoch } = useAuth()
    return <WorkspaceCrmContent {...props} key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, props.workspaceId].join(':')} />
}
