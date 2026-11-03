import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useWorkspace } from '../workspace/WorkspaceContext'
import { useUiResource } from '../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../api/events/uiScope'
import * as api from '../api/crmApi'
import { CrmServices, CrmMasters, CrmMasterDrawer } from '../components/crm/CrmCatalog'
import { recoveryQuery } from '../components/crm/CrmShared'
import '../components/crm/crm.css'

function CrmProjectCatalog({ project, canManage, rememberedTab, remember }: {
    project: api.CrmProject; canManage: boolean; rememberedTab: string; remember: (tab: string) => void
}) {
    const { isSuperAdmin } = useAuth()
    const scope = useUiScope()
    const [mapping, setMapping] = useState(false), [drawer, setDrawer] = useState<string | null>(null)
    const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), [notice, setNotice] = useState('')
    const [refresh, setRefresh] = useState(0)
    const mutation = useRef<AbortController>()
    const servicesSupported = project.capabilities.some(value => value.code === 'READ_SERVICES' && ['AVAILABLE', 'CONFIGURATION_REQUIRED', 'REAUTH_REQUIRED'].includes(value.availability))
    const tab = servicesSupported && rememberedTab !== 'masters' ? 'services' : 'masters'
    const tabs = servicesSupported ? ['services', 'masters'] : ['masters']
    useEffect(() => {
        setBusy(false); setError(null); setNotice('')
        return () => { mutation.current?.abort(); mutation.current = undefined }
    }, [scope])
    function changeTab(value: string) { remember(value); setMapping(false) }
    function tabKeys(event: KeyboardEvent<HTMLButtonElement>) {
        let next: string | undefined
        if (event.key === 'ArrowRight') next = tabs[(tabs.indexOf(tab) + 1) % tabs.length]
        if (event.key === 'ArrowLeft') next = tabs[(tabs.indexOf(tab) + tabs.length - 1) % tabs.length]
        if (event.key === 'Home') next = tabs[0]
        if (event.key === 'End') next = tabs[tabs.length - 1]
        if (next) { event.preventDefault(); changeTab(next); document.getElementById('crm-tab-' + next)?.focus() }
    }
    async function update() {
        if (mutation.current) return
        const controller = new AbortController(); mutation.current = controller; setBusy(true); setError(null)
        try {
            await api.refreshCrmCatalog(project.id, controller.signal)
            if (!controller.signal.aborted && (!scope || scope.isCurrent())) { setNotice('Обновление каталога запрошено.'); setRefresh(value => value + 1) }
        } catch (failure) { if (!controller.signal.aborted && (!scope || scope.isCurrent())) setError(api.crmError(failure)) }
        finally { if (mutation.current === controller) { mutation.current = undefined; if (!controller.signal.aborted) setBusy(false) } }
    }
    const recovery = recoveryQuery(project, tab)
    const accountProblem = ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)
    const needsRecovery = !project.enabled || accountProblem
    return <>
        <div className="crm-heading crm-catalog-heading">
            <div><strong>{project.provider}</strong> · {project.displayName} <span className="crm-state">{api.crmConnectionLabel(project.connectionState)}</span></div>
            <button type="button" className="ui-button ui-button-secondary" disabled={busy || needsRecovery} onClick={() => void update()}>{busy ? 'Запрашиваем…' : 'Обновить'}</button>
        </div>
        {needsRecovery && <div className="crm-notice">
            {!project.enabled ? 'Обновление проекта выключено.' : project.connectionState === 'DISABLED' ? 'Аккаунт CRM выключен.' : 'Для обновления каталога требуется вход в CRM.'}
            {' '}Сохранённые данные доступны.{' '}
            {isSuperAdmin ? <Link to={!project.enabled
                ? `/workspaces/${encodeURIComponent(project.workspaceId)}?${recovery}#crm`
                : `/settings?section=crm&account=${encodeURIComponent(project.connectionId)}&${recovery}`}>
                {!project.enabled ? 'Включить обновление проекта' : project.connectionState === 'DISABLED' ? 'Включить аккаунт' : 'Восстановить доступ'}</Link>
                : <span>Обратитесь к администратору организации.</span>}
        </div>}
        {error && <p role="alert" className="crm-error">{error}</p>}
        {notice && <p className="crm-action-status" role="status">{notice}</p>}
        <div className="crm-tab-heading">
            <div className="crm-tabs" role="tablist" aria-label="Каталог CRM">{tabs.map(value => <button type="button" key={value} role="tab" id={'crm-tab-' + value}
                aria-selected={tab === value} aria-controls="crm-tabpanel" tabIndex={tab === value ? 0 : -1}
                onKeyDown={tabKeys} onClick={() => changeTab(value)}>{value === 'services' ? 'Услуги' : 'Мастера'}</button>)}</div>
            {canManage && tab === 'masters' && <button type="button" className="ui-button ui-button-secondary" aria-pressed={mapping}
                onClick={() => setMapping(!mapping)}>{mapping ? 'Вернуться к мастерам' : 'Связи с сотрудниками'}</button>}
        </div>
        <div role="tabpanel" id="crm-tabpanel" aria-labelledby={'crm-tab-' + tab}>
            {tab === 'services' ? <CrmServices project={project} onMaster={setDrawer} refresh={refresh} />
                : <CrmMasters key={String(mapping)} project={project} mapping={canManage && mapping} onMaster={setDrawer} refresh={refresh} />}
        </div>
        {drawer && <CrmMasterDrawer key={drawer} project={project} id={drawer} close={() => setDrawer(null)} />}
    </>
}
function WorkspaceCatalog({ workspaceId }: { workspaceId: string }) {
    const { isSuperAdmin, employeeWorkspaces, currentEmployee } = useAuth()
    const [params, setParams] = useSearchParams()
    const [projects, setProjects] = useState<api.CrmProject[]>([]), [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(true)
    const canManage = isSuperAdmin || employeeWorkspaces.some(row => row.workspaceId === workspaceId && row.isSupervisor)
    const memoryKey = 'crm-tab:' + currentEmployee?.id
    const [tab, setTab] = useState(() => {
        if (['masters', 'services'].includes(params.get('tab') ?? '')) return params.get('tab')!
        try { return sessionStorage.getItem(memoryKey) ?? 'services' } catch { return 'services' }
    })
    const resource = useUiResource('crm-projects:' + workspaceId, true,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) || event.type === 'crm.invalidated'),
        signal => api.getCrmProjects(workspaceId, signal), value => { setProjects(value); setError(null) },
        error => setError(api.crmError(error)), () => setProjects([]), setLoading)
    const project = projects.find(row => row.id === params.get('project')) ?? projects[0]
    function remember(value: string) {
        setTab(value)
        try { sessionStorage.setItem(memoryKey, value) } catch { /* Private storage may be disabled. */ }
    }
    return <section className="crm-page" aria-label="CRM рабочего пространства">
        <div className="crm-heading"><h1>CRM</h1>
            {isSuperAdmin && <Link to={`/workspaces/${encodeURIComponent(workspaceId)}?returnWorkspace=${encodeURIComponent(workspaceId)}&returnTab=${tab}#crm`}>Настройки проекта</Link>}
        </div>
        {error && <p role="alert" className="crm-error">{error} <button type="button" onClick={resource.reload}>Повторить</button></p>}
        {loading && !project && <p role="status">Загрузка CRM…</p>}
        {!project && !loading && !error && <div className="crm-empty"><h2>Подключите проект CRM</h2><p>Здесь появятся услуги и мастера этого рабочего пространства.</p>
            {isSuperAdmin ? <Link className="ui-button ui-button-primary" to={`/workspaces/${encodeURIComponent(workspaceId)}?returnWorkspace=${encodeURIComponent(workspaceId)}#crm`}>Подключить проект</Link>
                : <p>Проект подключает администратор организации.</p>}</div>}
        {projects.length > 1 && <label className="crm-project-select">Проект<select value={project?.id ?? ''} onChange={event => setParams({ workspace: workspaceId, project: event.target.value, tab })}>
            {projects.map(row => <option key={row.id} value={row.id}>{row.provider} · {row.displayName}</option>)}</select></label>}
        {project && <CrmProjectCatalog key={project.id + ':' + canManage} project={project} canManage={canManage} rememberedTab={tab} remember={remember} />}
    </section>
}
export default function CrmPage() {
    const { currentEmployee, sessionEpoch } = useAuth()
    const { currentWorkspace, workspaces, setCurrentWorkspaceId } = useWorkspace()
    const [params] = useSearchParams(), location = useLocation()
    const target = params.get('workspace'), available = workspaces.map(row => row.id).join(':')
    useEffect(() => { if (target && workspaces.some(row => row.id === target)) setCurrentWorkspaceId(target) }, [location.key, target, available])
    if (!currentWorkspace) return <div className="crm-page"><h1>CRM</h1><p>Выберите рабочее пространство.</p></div>
    return <WorkspaceCatalog key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, currentWorkspace.id].join(':')} workspaceId={currentWorkspace.id} />
}
