import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { getApiErrorCode } from '../../api/apiClient'
import * as api from '../../api/crmApi'
import { crmLocation, ReturnToCrm, recoveryQuery } from './CrmShared'
import CrmTimeZoneSelect from './CrmTimeZoneSelect'
import CrmProjectSelect from './CrmProjectSelect'
import './crm.css'

interface Props { workspaceId: string }
function WorkspaceCrmContent({ workspaceId }: Props) {
    const { isSuperAdmin } = useAuth(), scope = useUiScope()
    const [params] = useSearchParams()
    const [projects, setProjects] = useState<api.CrmProject[]>([]), [organizationProjects, setOrganizationProjects] = useState<api.CrmProject[]>([])
    const [connections, setConnections] = useState<api.CrmConnection[]>([])
    const [connectionId, setConnectionId] = useState(params.get('connection') ?? ''), [externalId, setExternalId] = useState(''), [zone, setZone] = useState('')
    const [preview, setPreview] = useState<api.CrmProjectCheck | null>(null), [editing, setEditing] = useState<api.CrmProject | null>(null)
    const [previewArchive, setPreviewArchive] = useState<api.CrmProject | null>(null)
    const [confirm, setConfirm] = useState<string | null>(null)
    const [detaching, setDetaching] = useState<api.CrmProject | null>(null)
    const [busy, setBusy] = useState(false), [loading, setLoading] = useState(true)
    const [loaded, setLoaded] = useState(false)
    const [readError, setReadError] = useState<string | null>(null), [actionError, setActionError] = useState<string | null>(null), [notice, setNotice] = useState('')
    const mutation = useRef<AbortController>(), panel = useRef<HTMLElement>(null), scrolled = useRef(false)
    // GETs and mutation replies share one version floor. Only locally saved IDs survive an old empty list.
    const observedProjects = useRef(new Map<string, api.CrmProject>()), savedProjectIds = useRef(new Set<string>())
    function rememberProject(row: api.CrmProject) {
        const observed = observedProjects.current.get(row.id)
        if (observed && (row.contextVersion < observed.contextVersion || row.version < observed.version)) return observed
        observedProjects.current.set(row.id, row)
        return row
    }
    function reconcile(rows: api.CrmProject[], workspaceOnly = false) {
        const result = rows.map(row => observedProjects.current.get(row.id) ?? row)
        for (const id of savedProjectIds.current) {
            const row = observedProjects.current.get(id)
            if (row && !result.some(value => value.id === id)) result.push(row)
        }
        if (workspaceOnly) return result.filter(row => row.workspaceId === workspaceId)
        return result
    }
    function savedProject(row: api.CrmProject, message: string) {
        const current = rememberProject(row)
        savedProjectIds.current.add(current.id)
        setProjects(previous => reconcile(previous, true))
        setOrganizationProjects(previous => reconcile(previous))
        setNotice(current === row ? message : 'Состояние проекта уже изменилось. Показана актуальная версия.')
    }
    const resource = useUiResource('workspace-crm-settings:' + workspaceId, isSuperAdmin,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) || event.type === 'crm.invalidated'),
        async signal => {
            const [projects, connections, organizationProjects] = await Promise.all([api.getCrmProjects(workspaceId, signal), api.getCrmConnections(signal), api.getOrganizationCrmProjects(signal)])
            return { projects, connections, organizationProjects }
        }, value => {
            value.organizationProjects.forEach(rememberProject); value.projects.forEach(rememberProject)
            const rows = reconcile(value.projects, true)
            setProjects(rows); setConnections(value.connections); setOrganizationProjects(reconcile(value.organizationProjects)); setReadError(null); setLoaded(true)
            setEditing(previous => previous && rows.some(row => row.id === previous.id && row.version === previous.version && !row.archived) ? previous : null)
            setDetaching(previous => previous && rows.some(row => row.id === previous.id && row.version === previous.version && !row.archived) ? previous : null)
        },
        error => setReadError(api.crmError(error)),
        () => { setLoaded(false); setProjects([]); setConnections([]); setOrganizationProjects([]); observedProjects.current.clear(); savedProjectIds.current.clear(); setEditing(null); setDetaching(null); setPreview(null); setPreviewArchive(null); setBusy(false); setConfirm(null); mutation.current?.abort(); mutation.current = undefined }, setLoading)
    useEffect(() => {
        setBusy(false); setActionError(null); setEditing(null); setPreview(null); setPreviewArchive(null); setConfirm(null); setDetaching(null); observedProjects.current.clear(); savedProjectIds.current.clear()
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
            if (['CRM_VERSION_CONFLICT', 'CRM_PROJECT_ARCHIVED'].includes(getApiErrorCode(error) ?? '')) { setEditing(null); setConfirm(null); setDetaching(null); setPreview(null) }
            setActionError(api.crmError(error)); resource.reload()
        } }
        finally { if (mutation.current === controller) mutation.current = undefined; if (current() && !controller.signal.aborted) setBusy(false) }
    }
    const occupied = projects.some(row => row.provider === 'DIKIDI' && !row.archived)
    const connection = connections.find(row => row.id === connectionId)
    const unusable = !connection || ['DISABLED', 'REAUTH_REQUIRED'].includes(connection.status)
    const conflict = organizationProjects.find(row => row.provider === connection?.provider && row.externalId === externalId.trim() && row.workspaceId !== workspaceId)
    const archive = organizationProjects.find(row => row.archived && row.workspaceId === workspaceId && row.provider === connection?.provider && row.externalId === externalId.trim())
    const selectionBlocked = busy || !loaded || !!readError
    const bindingBlocked = selectionBlocked || loading
    function check(event: FormEvent) {
        event.preventDefault()
        if (bindingBlocked || unusable || conflict || occupied) return
        setPreview(null); setPreviewArchive(null)
        void run(signal => api.checkCrmProject(connectionId, externalId.trim(), signal), value => { setPreview(value); setPreviewArchive(archive ?? null) })
    }
    function saveZone(project: api.CrmProject) {
        void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, zoneId: zone.trim() }, signal),
            row => { savedProject(row, 'Часовой пояс сохранён.'); setEditing(null) })
    }
    if (!isSuperAdmin) return null
    return <section id="crm" ref={panel} tabIndex={-1} className="crm-panel" aria-label="Настройки CRM рабочего пространства">
        <div className="crm-heading"><h2>Проекты CRM</h2><ReturnToCrm /></div>
        {(actionError || readError) && <p role="alert" className="crm-error">{actionError || readError}</p>}
        {notice && <p role="status">{notice}</p>}
        {loading && !loaded && <p role="status">Загрузка CRM…</p>}
        {projects.map(project => <article className="crm-project-card" key={project.id}>
            <div className="crm-heading"><h3>{project.displayName}</h3><Link to={crmLocation(workspaceId, project.id)}>Открыть каталог</Link></div>
            <p>{project.provider} · ID {project.externalId} · {connections.find(row => row.id === project.connectionId) ? api.crmAccountName(connections.find(row => row.id === project.connectionId)!) : 'Аккаунт CRM'}
                <span className="crm-state">{api.crmConnectionLabel(project.connectionState)}</span></p>
            <p>Обновление: {project.enabled ? 'включено' : 'выключено'} · Часовой пояс: {project.zoneId ?? 'не указан'}</p>
            {project.archived && <p className="crm-notice">В архиве. История и сопоставления сохранены; доступен только просмотр. Архив не занимает активную привязку. {occupied ? 'Для восстановления сначала отвяжите текущий проект.' : 'Для восстановления подключите прежний ID проекта ниже.'}</p>}
            {!project.archived && !project.zoneId && editing?.id !== project.id && <div className="crm-notice crm-time-zone-field">
                <label>Часовой пояс проекта<CrmTimeZoneSelect label="Часовой пояс проекта" value={zone} disabled={busy || loading} onChange={setZone} /></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading || !zone} onClick={() => saveZone(project)}>Сохранить часовой пояс</button>
            </div>}
            {!project.archived && ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState) && <Link to={`/settings?section=crm&account=${encodeURIComponent(project.connectionId)}&${recoveryQuery(project, params.get('returnTab') ?? 'appointments')}`}>
                {project.connectionState === 'DISABLED' ? 'Включить аккаунт' : 'Восстановить доступ к аккаунту'}</Link>}
            {!project.archived && <div className="crm-actions">
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => { setEditing(project); setZone(project.zoneId ?? ''); setConnectionId(project.connectionId) }}>Настроить проект</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => project.enabled ? setConfirm(project.id)
                    : void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, enabled: true }, signal), row => savedProject(row, 'Обновление проекта включено.'))}>{project.enabled ? 'Выключить обновление' : 'Включить обновление'}</button>
                <button type="button" className="ui-button ui-button-danger" disabled={busy || loading || !!readError} onClick={() => { setConfirm(null); setEditing(null); setDetaching(project) }}>Отвязать проект</button>
            </div>}
            {detaching?.id === project.id && !project.archived && <div className="crm-notice">
                <p>Отвязать проект «{detaching.displayName}»? Обновления остановятся. Каталог, клиенты CRM, графики, визиты, финансы и сопоставления останутся в архиве этого Workspace. Можно будет подключить другой проект. Аккаунт CRM, данные в DIKIDI, клиенты и сотрудники ClientBus сохранятся.</p>
                <div className="crm-actions"><button type="button" className="ui-button ui-button-danger" disabled={busy || loading || !!readError}
                    onClick={() => void run(signal => api.detachCrmProject(detaching.id, detaching.version, signal), row => {
                        savedProject(row, 'Проект отвязан. История и сопоставления сохранены в архиве.'); setDetaching(null); setEditing(null); setConfirm(null); setPreview(null); setExternalId(''); setZone('')
                    })}>Подтвердить отвязку</button>
                    <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setDetaching(null)}>Отмена</button></div>
            </div>}
            {confirm === project.id && !project.archived && <div className="crm-notice"><p>Выключить обновление проекта? Привязка, история и связи сохранятся. Другой проект DIKIDI подключить сюда не получится.</p>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => void run(signal => api.patchCrmProject(project.id, { expectedVersion: project.version, enabled: false }, signal), row => { savedProject(row, 'Обновление проекта выключено.'); setConfirm(null) })}>Подтвердить выключение</button>{' '}
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setConfirm(null)}>Отмена</button></div>}
            {editing?.id === project.id && !project.archived && <div className="crm-form">
                <label>Часовой пояс<CrmTimeZoneSelect label="Изменить часовой пояс" value={zone} disabled={busy} onChange={setZone} /></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || zone.trim() === (editing.zoneId ?? '')}
                    onClick={() => saveZone(editing)}>Сохранить часовой пояс</button>
                <label>Аккаунт для доступа<select aria-label="Новое подключение DIKIDI" value={connectionId} disabled={busy} onChange={event => setConnectionId(event.target.value)}>
                    {connections.filter(row => row.provider === project.provider).map(row => <option key={row.id} value={row.id} disabled={['DISABLED', 'REAUTH_REQUIRED'].includes(row.status)}>{api.crmAccountName(row)} · {api.crmConnectionLabel(row.status)}</option>)}</select></label>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || unusable || connectionId === editing.connectionId}
                    onClick={() => void run(signal => api.changeCrmProjectConnection(editing.id, connectionId, editing.version, signal), row => { savedProject(row, 'Доступ к проекту обновлён.'); setEditing(null) })}>Сменить аккаунт</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setEditing(null)}>Закрыть настройки</button>
            </div>}
        </article>)}
        {/* Keep project options mounted across health invalidations and transient refresh failures. */}
        {loaded && !occupied && <>
            <p>Подключите проект DIKIDI к этому рабочему пространству.</p>
            {connections.length === 0 ? <Link className="ui-button ui-button-primary" to={`/settings?section=crm&returnWorkspace=${encodeURIComponent(workspaceId)}`}>Добавить аккаунт CRM</Link>
                : <form className="crm-form" onSubmit={check}>
                    <h3>{preview ? '2. Подтверждение проекта' : '1. Выберите аккаунт и проект'}</h3>
                    <label>Аккаунт<select aria-label="Подключение DIKIDI" required value={connectionId} disabled={selectionBlocked || !!preview} onChange={event => { setConnectionId(event.target.value); setExternalId(''); setPreview(null); setPreviewArchive(null) }}>
                        <option value="">Выберите аккаунт</option>{connections.filter(row => row.provider === 'DIKIDI').map(row => <option key={row.id} value={row.id}>{api.crmAccountName(row)} · {api.crmConnectionLabel(row.status)}</option>)}</select></label>
                    {connection && unusable ? <p className="crm-notice">{connection.status === 'DISABLED' ? 'Аккаунт выключен.' : 'Требуется повторный вход.'}{' '}
                        <Link to={`/settings?section=crm&account=${encodeURIComponent(connection.id)}&returnWorkspace=${encodeURIComponent(workspaceId)}`}>Настроить аккаунт</Link></p>
                        : connection && <CrmProjectSelect key={connection.id} connection={connection} workspaceId={workspaceId} bindings={organizationProjects}
                            value={externalId} disabled={selectionBlocked || !!preview} onChange={value => { setExternalId(value); setPreview(null); setPreviewArchive(null) }} />}
                    {conflict && <p role="alert" className="crm-notice">Этот проект уже связан с другим пространством. <Link to={`/workspaces/${encodeURIComponent(conflict.workspaceId)}#crm`}>Открыть его настройки</Link></p>}
                    {!preview && <button className="ui-button ui-button-secondary" disabled={bindingBlocked || unusable || !externalId.trim() || !!conflict}>Проверить проект</button>}
                    {preview && <div className="crm-form"><div className="crm-notice"><strong>{preview.displayName}</strong><p>ID {preview.externalId} · Валюта: {preview.currency ?? 'не указана'}</p></div>
                        {previewArchive && <p className="crm-notice">Проект будет восстановлен из архива с прежней историей и сопоставлениями.</p>}
                        <label>Часовой пояс проекта<CrmTimeZoneSelect label="Часовой пояс проекта" value={zone} disabled={bindingBlocked} onChange={setZone} /></label>
                        <div className="crm-actions"><button type="button" className="ui-button ui-button-primary" disabled={bindingBlocked || unusable || !!conflict} onClick={() => void run(signal => api.bindCrmProject(workspaceId,
                            { connectionId, externalProjectId: preview.externalId, ...(zone.trim() ? { zoneId: zone.trim() } : {}), ...(previewArchive ? { expectedArchiveVersion: previewArchive.version } : {}) }, signal), row => { savedProject(row, 'Проект подключён. Каталог загружается.'); setPreview(null); setPreviewArchive(null); setExternalId(''); setZone('') })}>{previewArchive ? 'Восстановить проект' : 'Привязать проект'}</button>
                            <button type="button" className="ui-button ui-button-secondary" disabled={bindingBlocked} onClick={() => setPreview(null)}>Назад</button></div>
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
