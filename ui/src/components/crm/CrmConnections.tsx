import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { useUiResource } from '../../api/events/useUiResource'
import { isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { getApiErrorCode } from '../../api/apiClient'
import * as api from '../../api/crmApi'
import { ReturnToCrm } from './CrmShared'
import './crm.css'

function CrmConnectionsContent() {
    const { currentEmployee, isSuperAdmin, sessionEpoch } = useAuth()
    const { workspaces } = useWorkspace()
    const [params] = useSearchParams(), scope = useUiScope()
    const [types, setTypes] = useState<api.CrmType[]>([]), [connections, setConnections] = useState<api.CrmConnection[]>([])
    const [projects, setProjects] = useState<api.CrmProject[]>([])
    const [provider, setProvider] = useState<api.CrmType>('DIKIDI')
    const [displayName, setDisplayName] = useState(''), [login, setLogin] = useState(''), [secret, setSecret] = useState('')
    const [editing, setEditing] = useState<api.CrmConnection | null>(null), [formOpen, setFormOpen] = useState(false)
    const [busy, setBusy] = useState(false), [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null), [actionError, setActionError] = useState<string | null>(null)
    const [search, setSearch] = useState(''), [attention, setAttention] = useState(false)
    const [confirm, setConfirm] = useState<string | null>(null), [created, setCreated] = useState<string | null>(null)
    const [deleting, setDeleting] = useState<api.CrmConnection | null>(null)
    const [workspaceId, setWorkspaceId] = useState(params.get('returnWorkspace') ?? '')
    const [notice, setNotice] = useState('')
    const mutation = useRef<AbortController>(), nameInput = useRef<HTMLInputElement>(null), focusedAccount = useRef<string | null>(null)
    const deletedConnections = useRef(new Set<string>())
    const knownConnections = useRef(new Set<string>()), writeRevision = useRef(0)
    const drafts = useRef({ editing, created, confirm, deleting }); drafts.current = { editing, created, confirm, deleting }
    function clearForm() { setEditing(null); setLogin(''); setSecret(''); setDisplayName(''); setFormOpen(false) }
    const resource = useUiResource('crm-connections:' + sessionEpoch + ':' + currentEmployee?.id, isSuperAdmin,
        event => event.type === 'crm.invalidated' || isAccessEvent(event),
        async signal => {
            const revision = writeRevision.current
            const [types, connections, projects] = await Promise.all([api.getCrmTypes(signal), api.getCrmConnections(signal), api.getOrganizationCrmProjects(signal)])
            return { types, connections, projects, revision }
        }, value => {
            // A pre-mutation GET cannot establish that a newly saved account was deleted.
            if (value.revision !== writeRevision.current) return
            const available = value.connections.filter(row => !deletedConnections.current.has(row.id))
            const ids = new Set(available.map(row => row.id)), currentDrafts = drafts.current
            for (const id of knownConnections.current) if (!ids.has(id)) deletedConnections.current.add(id)
            knownConnections.current = ids
            const editorRemoved = !!currentDrafts.editing && !ids.has(currentDrafts.editing.id)
            const createdRemoved = !!currentDrafts.created && !ids.has(currentDrafts.created)
            if (editorRemoved) clearForm()
            if (createdRemoved) setCreated(null)
            if (currentDrafts.confirm && !ids.has(currentDrafts.confirm)) setConfirm(null)
            if (currentDrafts.deleting && !ids.has(currentDrafts.deleting.id)) setDeleting(null)
            if (editorRemoved || createdRemoved) setNotice('Аккаунт CRM удалён.')
            setTypes(value.types)
            setConnections(previous => available.map(row => {
                const local = previous.find(item => item.id === row.id)
                return local && local.version > row.version ? local : row
            }))
            setProjects(value.projects); setError(null)
        },
        error => setError(api.crmError(error)),
        () => { deletedConnections.current.clear(); knownConnections.current.clear(); setTypes([]); setConnections([]); setProjects([]); clearForm(); setBusy(false); setCreated(null); setConfirm(null); setDeleting(null); setActionError(null); mutation.current?.abort(); mutation.current = undefined }, setLoading)
    useEffect(() => {
        deletedConnections.current.clear(); knownConnections.current.clear(); setBusy(false); setActionError(null); clearForm(); setConfirm(null); setDeleting(null)
        return () => { mutation.current?.abort(); mutation.current = undefined }
    }, [scope])
    function edit(connection: api.CrmConnection) {
        setDeleting(null)
        setEditing(connection); setDisplayName(connection.displayName ?? ''); setProvider(connection.provider); setLogin(''); setSecret(''); setActionError(null); setFormOpen(true)
    }
    useEffect(() => { if (formOpen) nameInput.current?.focus() }, [formOpen, editing?.id])
    useEffect(() => {
        const id = params.get('account'), account = connections.find(row => row.id === id)
        if (!id || !account || focusedAccount.current === id) return
        focusedAccount.current = id
        setAttention(false); setSearch('')
        if (account.status === 'REAUTH_REQUIRED') edit(account)
        else document.getElementById('crm-account-' + id)?.focus()
    }, [params, connections])
    async function run(action: (signal: AbortSignal) => Promise<api.CrmConnection | void>, onSuccess?: (value: api.CrmConnection | void) => void,
        successNotice = 'Изменения сохранены.') {
        if (mutation.current) return
        const current = resource.capture(), controller = new AbortController()
        mutation.current = controller; setBusy(true); setActionError(null); setNotice('')
        try {
            const value = await action(controller.signal)
            if (current() && !controller.signal.aborted) {
                if (value && deletedConnections.current.has(value.id)) { resource.reload(); return }
                writeRevision.current++
                if (value) {
                    knownConnections.current.add(value.id)
                    setConnections(previous => previous.some(row => row.id === value.id) ? previous.map(row => row.id === value.id ? value : row) : [...previous, value])
                }
                onSuccess?.(value); setConfirm(null); setDeleting(null); setNotice(successNotice); resource.reload()
            }
        } catch (error) { if (current() && !controller.signal.aborted) {
            if (getApiErrorCode(error) === 'CRM_VERSION_CONFLICT') clearForm()
            setDeleting(null)
            setActionError(api.crmError(error)); resource.reload()
        } }
        finally { if (mutation.current === controller) mutation.current = undefined; if (current() && !controller.signal.aborted) setBusy(false) }
    }
    function save(event: FormEvent) {
        event.preventDefault()
        const submittedSecret = secret, snapshot = editing; setSecret('')
        void run(signal => snapshot ? api.patchCrmConnection(snapshot.id, {
            expectedVersion: snapshot.version, displayName: displayName.trim(), ...(login.trim() ? { login: login.trim() } : {}),
            ...(submittedSecret ? { secret: submittedSecret } : {}),
        }, signal) : api.createCrmConnection({ provider, displayName: displayName.trim(), login: login.trim(), secret: submittedSecret }, signal),
        value => { clearForm(); if (!snapshot && value) setCreated(value.id) })
    }
    if (!isSuperAdmin) return null
    const needle = search.trim().toLocaleLowerCase()
    const visible = connections.filter(row => (!attention || row.status !== 'CONNECTED') &&
        [api.crmAccountName(row), row.loginHint, row.provider, ...projects.filter(project => project.connectionId === row.id).map(project => project.displayName)].join(' ').toLocaleLowerCase().includes(needle))
    const changed = !editing || displayName.trim() !== (editing.displayName ?? '') || !!login.trim() || !!secret
    const usedConnections = new Set(projects.map(project => project.connectionId))
    const workspaceOccupied = workspaceId && projects.some(row => row.workspaceId === workspaceId && row.provider === 'DIKIDI')
    return <section className="settings-section crm-panel" aria-label="Подключения CRM">
        <div className="crm-heading"><div><h2>Аккаунты CRM</h2><p>Доступы организации. Проекты подключаются к рабочим пространствам.</p></div>
            <button type="button" className="ui-button ui-button-primary" disabled={busy} onClick={() => { clearForm(); setFormOpen(true); setCreated(null); setDeleting(null) }}>Добавить аккаунт</button></div>
        <ReturnToCrm />
        {(actionError || error) && <p role="alert" className="crm-error">{actionError || error}</p>}
        {notice && <p role="status" className="crm-action-status">{notice}</p>}
        {loading && <p role="status">Загрузка подключений…</p>}
        {!loading && !connections.length && !formOpen && <div className="crm-empty"><h3>Добавьте первый аккаунт CRM</h3><p>После этого подключите проект к рабочему пространству.</p></div>}
        {formOpen && <form onSubmit={save} className="crm-form">
            <h3>{editing ? 'Настройки аккаунта' : 'Новый аккаунт'}</h3>
            <label>Название аккаунта<input ref={nameInput} aria-label="Название аккаунта" value={displayName} maxLength={80} placeholder="Например, Основной DIKIDI" disabled={busy} onChange={event => setDisplayName(event.target.value)} /></label>
            <label>CRM<select aria-label="Тип CRM" value={provider} disabled={busy || !!editing || types.length === 0} onChange={event => setProvider(event.target.value as api.CrmType)}>
                {types.map(type => <option key={type} value={type}>{type}</option>)}</select></label>
            <label>Телефон DIKIDI<input type="tel" autoComplete="off" aria-label="Телефон DIKIDI" value={login} maxLength={16} placeholder={editing ? 'Не менять телефон' : '+7…'}
                required={!editing} disabled={busy} onChange={event => setLogin(event.target.value)} /></label>
            <label>Пароль DIKIDI<input type="password" autoComplete="new-password" aria-label="Пароль DIKIDI" value={secret} maxLength={512} required={!editing || !!login.trim()}
                placeholder={editing ? 'Не менять пароль' : ''} disabled={busy} onChange={event => setSecret(event.target.value)} /></label>
            <div className="crm-actions"><button className="ui-button ui-button-primary" disabled={busy || types.length === 0 || !changed || (!editing && (!login.trim() || !secret))}>
                {busy ? 'Выполняется…' : editing ? 'Сохранить' : 'Добавить подключение'}</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={clearForm}>Отмена</button></div>
        </form>}
        {created && <div className="crm-notice crm-form"><h3>Аккаунт добавлен. Подключите проект</h3>
            <label>Рабочее пространство<select aria-label="Рабочее пространство" value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}><option value="">Выберите пространство</option>
                {workspaces.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</select></label>
            {workspaceOccupied ? <p>В этом пространстве уже есть проект DIKIDI, включая отключённые проекты. Выберите другое пространство.</p>
                : workspaceId && <Link className="ui-button ui-button-primary" to={`/workspaces/${encodeURIComponent(workspaceId)}?connection=${encodeURIComponent(created)}&returnWorkspace=${encodeURIComponent(workspaceId)}#crm`}>Подключить проект</Link>}
            {!workspaces.length && <Link to="/workspaces">Создать рабочее пространство</Link>}
        </div>}
        {!!connections.length && <div className="crm-toolbar"><label className="crm-search">Найти аккаунт<input type="search" value={search} maxLength={160} placeholder="Название, CRM или проект" onChange={event => setSearch(event.target.value)} /></label>
            <label className="crm-checkbox"><input type="checkbox" checked={attention} onChange={event => setAttention(event.target.checked)} />Требуют внимания</label></div>}
        {!loading && connections.length > 0 && !visible.length && <p>Аккаунты по этим условиям не найдены.</p>}
        <ul className="crm-connections">{visible.map(connection => <li className="crm-connection" key={connection.id} id={'crm-account-' + connection.id} tabIndex={-1}>
            <div><strong>{api.crmAccountName(connection)}</strong><span className="crm-state">{api.crmConnectionLabel(connection.status)}</span></div>
            <small>{connection.provider} · {connection.loginHint}{connection.lastCheckedAt && ' · Проверено: ' + new Date(connection.lastCheckedAt).toLocaleString('ru-RU')}</small>
            <ul className="crm-project-links">{projects.filter(row => row.connectionId === connection.id).map(row => <li key={row.id}>
                <Link to={`/workspaces/${encodeURIComponent(row.workspaceId)}#crm`}>{row.displayName} · {workspaces.find(workspace => workspace.id === row.workspaceId)?.name ?? 'Рабочее пространство'}{!row.enabled ? ' · обновление выключено' : ''}</Link></li>)}</ul>
            {!projects.some(row => row.connectionId === connection.id) && <span>Проекты пока не подключены.</span>}
            <div className="crm-actions">
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || connection.status === 'DISABLED'} onClick={() => void run(signal => api.verifyCrmConnection(connection.id, signal))}>Проверить</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => edit(connection)}>{connection.status === 'REAUTH_REQUIRED' ? 'Восстановить доступ' : 'Изменить данные'}</button>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy || loading} onClick={() => {
                    setDeleting(null)
                    if (connection.status === 'DISABLED') void run(signal => api.patchCrmConnection(connection.id, { expectedVersion: connection.version, enabled: true }, signal))
                    else setConfirm(connection.id)
                }}>{connection.status === 'DISABLED' ? 'Включить аккаунт' : 'Отключить аккаунт'}</button>
                <button type="button" className="ui-button ui-button-danger" disabled={busy || loading || !!error || usedConnections.has(connection.id)}
                    title={usedConnections.has(connection.id) ? 'Сначала смените аккаунт в настройках привязанных проектов.' : 'Удалить аккаунт CRM'}
                    onClick={() => { setConfirm(null); setDeleting(connection) }}>Удалить</button>
            </div>
            {confirm === connection.id && <div className="crm-notice"><p>Остановить обновление всех проектов этого аккаунта? Привязки, данные и история сохранятся.</p>
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => void run(signal => api.patchCrmConnection(connection.id, { expectedVersion: connection.version, enabled: false }, signal))}>Подтвердить отключение</button>{' '}
                <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setConfirm(null)}>Отмена</button></div>}
            {deleting?.id === connection.id && !usedConnections.has(connection.id) && <div className="crm-notice">
                <p>Удалить аккаунт «{api.crmAccountName(deleting)}»? Его сохранённые учётные данные будут удалены.</p>
                <div className="crm-actions"><button type="button" className="ui-button ui-button-danger" disabled={busy || loading || !!error}
                    onClick={() => void run(signal => api.deleteCrmConnection(deleting.id, deleting.version, signal), () => {
                        deletedConnections.current.add(deleting.id)
                        setConnections(previous => previous.filter(row => row.id !== deleting.id))
                        if (editing?.id === deleting.id) clearForm()
                        if (created === deleting.id) setCreated(null)
                    }, 'Аккаунт CRM удалён.')}>Подтвердить удаление</button>
                    <button type="button" className="ui-button ui-button-secondary" disabled={busy} onClick={() => setDeleting(null)}>Отмена</button></div>
            </div>}
        </li>)}</ul>
        {error && <button type="button" className="ui-button ui-button-secondary" onClick={resource.reload}>Повторить загрузку</button>}
    </section>
}
export default function CrmConnections() {
    const { currentEmployee, isSuperAdmin, sessionEpoch } = useAuth()
    return <CrmConnectionsContent key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, isSuperAdmin].join(':')} />
}
