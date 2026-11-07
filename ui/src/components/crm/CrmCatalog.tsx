import { useEffect, useRef, useState } from 'react'
import * as api from '../../api/crmApi'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { getApiErrorStatus } from '../../api/apiClient'
import { useCrmPages } from './useCrmPages'
import { CrmFreshness, OfferTerms } from './CrmShared'

type ProjectProps = { project: api.CrmProject; refresh?: number }
function PageStatus({ data, empty, moreLabel, archived = false }: { data: ReturnType<typeof useCrmPages<any>>; empty: string; moreLabel: string; archived?: boolean }) {
    return <>
        {data.error && <div role="alert" className="crm-error">{data.error} <button type="button" onClick={data.reload}>Повторить</button></div>}
        {data.loading && !data.page && <p role="status">Загрузка…</p>}
        {!data.loading && !data.error && !data.page?.items.length && <p className="crm-empty">{data.page?.dataState.freshness === 'NEVER_LOADED' ? archived ? 'В архиве нет сохранённых данных.' : 'Данные ещё не загружены. Нажмите «Обновить».' : empty}</p>}
        {data.page?.hasMore && <button type="button" className="ui-button ui-button-secondary crm-more" disabled={data.more || data.loading}
            onClick={() => void data.loadMore()}>{data.more ? 'Загрузка…' : moreLabel}</button>}
    </>
}
function MasterOptions({ project, query, choose }: ProjectProps & { query: string; choose: (master: api.CrmMaster) => void }) {
    const data = useCrmPages('crm-master-options:' + project.id + ':' + query, project.workspaceId,
        (signal, cursor) => api.getCrmMasters(project.id, signal, cursor, { q: query, includeNotObserved: false }))
    return <><ul className="crm-picker-results">{data.page?.items.map(master => <li key={master.id}>
        <button type="button" onClick={() => choose(master)}>{master.displayName} <small>ID {master.externalId}</small></button>
    </li>)}</ul><PageStatus data={data} empty="Мастера не найдены." moreLabel="Ещё мастера" /></>
}
function MasterFilter({ project, selected, change }: ProjectProps & { selected: api.CrmMaster | null; change: (master: api.CrmMaster | null) => void }) {
    const [open, setOpen] = useState(false), [query, setQuery] = useState('')
    const trigger = useRef<HTMLButtonElement>(null)
    return <div className="crm-master-filter">
        <button type="button" className="ui-button ui-button-secondary" ref={trigger} aria-expanded={open}
            onClick={() => setOpen(!open)}>Мастер: {selected?.displayName ?? 'все'} ▾</button>
        {open && <div className="crm-picker" onKeyDown={event => { if (event.key === 'Escape') { setOpen(false); trigger.current?.focus() } }}>
            <label>Найти мастера<input autoFocus type="search" maxLength={160} value={query} onChange={event => setQuery(event.target.value)} /></label>
            <button type="button" onClick={() => { change(null); setOpen(false); trigger.current?.focus() }}>Все мастера</button>
            <MasterOptions key={query} project={project} query={query} choose={master => { change(master); setOpen(false); trigger.current?.focus() }} />
        </div>}
    </div>
}
function ServiceRows({ project, query, masterId, onMaster, refresh }: ProjectProps & { query: string; masterId?: string; onMaster?: (id: string) => void }) {
    const data = useCrmPages('crm-services:' + project.id + ':' + query + ':' + masterId, project.workspaceId,
        (signal, cursor) => api.getCrmServices(project.id, { q: query, masterId, cursor }, signal))
    useEffect(() => { if (refresh) data.reload() }, [refresh])
    return <>
        <CrmFreshness state={data.page?.dataState} archived={project.archived} />
        <ul className="crm-service-list">{data.page?.items.map(service => <li className="crm-service" key={service.id}>
            <div className="crm-service-name"><strong>{service.displayName}</strong>{service.category && <small>{service.category}</small>}</div>
            <ul>{service.offers.map(offer => <li key={offer.id} className="crm-offer">
                {onMaster ? <button type="button" className="crm-text-button" onClick={() => onMaster(offer.masterId)}>{offer.masterDisplayName || 'Карточка мастера'}</button>
                    : <span>{offer.masterDisplayName}</span>}
                <OfferTerms offer={offer} />
            </li>)}</ul>
            {!service.offers.length && <p>Предложения мастеров пока не получены.</p>}
        </li>)}</ul>
        <PageStatus data={data} archived={project.archived} empty={query || masterId ? 'Услуги по этим условиям не найдены.' : 'В каталоге пока нет услуг.'} moreLabel="Показать ещё услуги" />
    </>
}
export function CrmServices({ project, onMaster, refresh }: ProjectProps & { onMaster: (id: string) => void }) {
    const [query, setQuery] = useState(''), [selected, setSelected] = useState<api.CrmMaster | null>(null)
    return <><div className="crm-toolbar">
        <label className="crm-search">Найти услугу<input type="search" placeholder="Название или категория" maxLength={160} value={query} onChange={event => setQuery(event.target.value)} /></label>
        <MasterFilter project={project} selected={selected} change={setSelected} />
    </div><ServiceRows key={query + ':' + selected?.id} project={project} query={query} masterId={selected?.id} onMaster={onMaster} refresh={refresh} /></>
}

function MasterRows({ project, query, mapping, unlinked, onMaster, refresh }: ProjectProps & {
    query: string; mapping: boolean; unlinked: boolean; onMaster: (id: string) => void
}) {
    const scope = useUiScope()
    const data = useCrmPages('crm-masters:' + project.id + ':' + query + ':' + mapping + ':' + unlinked, project.workspaceId,
        (signal, cursor) => api.getCrmMasters(project.id, signal, cursor, { q: query, unlinked, includeNotObserved: mapping }))
    useEffect(() => { if (refresh) data.reload() }, [refresh])
    const [candidates, setCandidates] = useState<api.CrmEmployeeCandidate[]>([]), [candidateError, setCandidateError] = useState<string | null>(null)
    const [candidateLoading, setCandidateLoading] = useState(false), [candidateQuery, setCandidateQuery] = useState('')
    const [saving, setSaving] = useState<string | null>(null), [saveError, setSaveError] = useState<string | null>(null)
    const [undo, setUndo] = useState<{ saved: api.CrmMaster; previous: string | null } | null>(null)
    const [message, setMessage] = useState('')
    const mutation = useRef<AbortController>()
    const undoFocus = useRef<string | null>(null)
    const candidateResource = useUiResource('crm-candidates:' + project.id, mapping,
        event => affectsWorkspace(event, project.workspaceId) && (isAccessEvent(event) || ['employees.invalidated', 'crm.invalidated'].includes(event.type)),
        signal => api.getCrmEmployeeCandidates(project.id, signal),
        value => { setCandidates(value); setCandidateError(null) }, error => setCandidateError(api.crmError(error)),
        () => { setCandidates([]); setUndo(null); mutation.current?.abort(); mutation.current = undefined; setSaving(null) }, setCandidateLoading)
    useEffect(() => {
        setSaving(null); setUndo(null); setSaveError(null); setMessage(''); undoFocus.current = null
        return () => { mutation.current?.abort(); mutation.current = undefined }
    }, [scope])
    useEffect(() => {
        if (!saving && undoFocus.current && typeof document !== 'undefined') {
            if (document.activeElement === document.body) document.getElementById('crm-link-' + undoFocus.current)?.focus()
            undoFocus.current = null
        }
    }, [saving])
    async function save(master: api.CrmMaster, employeeId: string | null, reverting = false) {
        if (project.archived || mutation.current) return
        const controller = new AbortController(), current = data.capture(), candidateCurrent = candidateResource.capture()
        mutation.current = controller; setSaving(master.id); setSaveError(null); setMessage('')
        try {
            const saved = await api.linkCrmMaster(master, employeeId, controller.signal)
            if (!current() || !candidateCurrent() || controller.signal.aborted) return
            data.replace(saved); setUndo(reverting ? null : { saved, previous: master.employeeId })
            if (reverting) undoFocus.current = master.id
            setMessage(`${master.displayName}: ${reverting ? 'изменение отменено' : employeeId ? 'связь сохранена' : 'связь удалена'}.`)
            // Keep the changed row until the user leaves the filter; undo stays available outside it.
        } catch (error) {
            if (!current() || !candidateCurrent() || controller.signal.aborted) return
            setSaveError(api.crmError(error)); setUndo(null)
            if ([403, 404].includes(getApiErrorStatus(error) ?? 0)) { setCandidates([]); candidateResource.reload() }
            data.reload()
        } finally {
            if (mutation.current === controller) { mutation.current = undefined; if (current()) setSaving(null) }
        }
    }
    const visibleCandidates = candidates.filter(row => (row.displayName + ' ' + row.username).toLocaleLowerCase().includes(candidateQuery.toLocaleLowerCase()))
    return <>
        <CrmFreshness state={data.page?.dataState} archived={project.archived} />
        {mapping && <details className="crm-candidate-search"><summary>Найти сотрудника по имени или логину</summary><label>Поиск сотрудника в списках<input type="search" value={candidateQuery} maxLength={160} onChange={event => setCandidateQuery(event.target.value)} placeholder="Имя или логин" /></label></details>}
        {mapping && candidateError && <p role="alert" className="crm-error">{candidateError} <button type="button" onClick={candidateResource.reload}>Повторить загрузку сотрудников</button></p>}
        {saveError && <p role="alert" className="crm-error">{saveError}</p>}
        {(message || undo) && <div className="crm-feedback" role="status">{message} {undo && <button type="button" className="ui-button ui-button-secondary" disabled={!!saving}
            onClick={() => void save(undo.saved, undo.previous, true)}>Отменить изменение</button>}</div>}
        <ul className="crm-masters">{data.page?.items.map(master => <li className={mapping ? 'crm-master mapping' : 'crm-master'} key={master.id}>
            <div><button type="button" className="crm-text-button" onClick={() => onMaster(master.id)}>{master.displayName}</button>
                <small>ID {master.externalId}</small>
                {master.observationState === 'NOT_OBSERVED' && <small>Отсутствует в последнем полном каталоге</small>}</div>
            {mapping && <label>Сотрудник ClientBus<select id={'crm-link-' + master.id} aria-label={'Сотрудник: ' + master.displayName} value={master.employeeId ?? ''}
                aria-disabled={project.archived || !!saving} disabled={project.archived || (candidateLoading && !candidates.length) || !!candidateError} onChange={event => void save(master, event.target.value || null)}>
                <option value="">Без связи с сотрудником</option>
                {master.employeeId && !visibleCandidates.some(row => row.id === master.employeeId) && <option value={master.employeeId}>
                    {candidates.find(row => row.id === master.employeeId)?.displayName ?? 'Связанный сотрудник'}
                </option>}
                {visibleCandidates.map(row => <option key={row.id} value={row.id}>{row.displayName || row.username} · {row.username}{!row.enabled ? ' · отключён' : ''}</option>)}
            </select>{saving === master.id && <small role="status">Сохраняем…</small>}</label>}
            {!mapping && <span className="crm-row-hint">Услуги и условия →</span>}
        </li>)}</ul>
        <PageStatus data={data} archived={project.archived} empty={unlinked ? 'Мастеров без связи по этим условиям нет.' : query ? 'Мастера не найдены.' : 'В каталоге пока нет мастеров.'} moreLabel="Показать ещё мастеров" />
    </>
}
export function CrmMasters({ project, mapping, onMaster, refresh }: ProjectProps & { mapping: boolean; onMaster: (id: string) => void }) {
    const [query, setQuery] = useState(''), [unlinked, setUnlinked] = useState(false)
    return <>
        {mapping && <div className="crm-mapping-heading"><h2>Связи с сотрудниками</h2><details className="crm-mapping-help"><summary>О связях</summary><p className="crm-notice">Связь означает, что мастер и сотрудник — один человек. Она не даёт доступ к пространству и не назначает диалоги. Мастер может работать без аккаунта ClientBus.</p></details></div>}
        <div className="crm-toolbar"><label className="crm-search">Найти мастера<input type="search" maxLength={160} value={query} onChange={event => setQuery(event.target.value)} /></label>
            {mapping && <label className="crm-checkbox"><input type="checkbox" checked={unlinked} onChange={event => setUnlinked(event.target.checked)} />Без связи с сотрудником</label>}</div>
        <MasterRows key={query + ':' + mapping + ':' + unlinked} project={project} query={query} unlinked={mapping && unlinked} mapping={mapping} onMaster={onMaster} refresh={refresh} />
    </>
}
export function CrmMasterDrawer({ project, id, close }: ProjectProps & { id: string; close: () => void }) {
    const dialog = useRef<HTMLDialogElement>(null)
    const [master, setMaster] = useState<api.CrmMaster | null>(null), [error, setError] = useState<string | null>(null)
    const resource = useUiResource('crm-master:' + id, true,
        event => affectsWorkspace(event, project.workspaceId) && (isAccessEvent(event) || event.type === 'crm.invalidated'),
        signal => api.getCrmMaster(id, signal), value => { setMaster(value); setError(null) }, error => setError(api.crmError(error)), () => setMaster(null))
    useEffect(() => {
        const previous = document.activeElement as HTMLElement | null
        dialog.current?.showModal()
        return () => { if (previous?.isConnected) previous.focus() }
    }, [])
    return <dialog className="crm-drawer" ref={dialog} aria-labelledby="crm-master-title" onCancel={close} onClick={event => { if (event.target === event.currentTarget) close() }}>
        <div className="crm-drawer-content"><div className="crm-heading"><h2 id="crm-master-title">{master?.displayName ?? 'Карточка мастера'}</h2>
            <button type="button" className="ui-button ui-button-secondary" autoFocus onClick={close}>Закрыть</button></div>
            {error && <p role="alert" className="crm-error">{error} <button type="button" onClick={resource.reload}>Повторить</button></p>}
            {master && <><p>{project.displayName} · ID {master.externalId}</p><h3>Услуги мастера</h3><ServiceRows project={project} query="" masterId={id} /></>}
        </div>
    </dialog>
}
