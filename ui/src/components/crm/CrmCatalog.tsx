import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
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
        (signal, cursor) => api.getCrmMasters(project.id, signal, cursor, { q: query, includeNotObserved: false, mastersOnly: true }))
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

function MasterRows({ project, query, mapping, unlinked, onMaster, refresh, mastersOnly, staff = false }: ProjectProps & {
    query: string; mapping: boolean; unlinked: boolean; onMaster: (id: string) => void; mastersOnly: boolean; staff?: boolean
}) {
    const scope = useUiScope()
    const data = useCrmPages((staff ? 'crm-staff:' : 'crm-masters:') + project.id + ':' + query + ':' + mapping + ':' + unlinked, project.workspaceId,
        (signal, cursor) => api.getCrmMasters(project.id, signal, cursor,
            { q: query, unlinked, includeNotObserved: mapping, mastersOnly, accountsOnly: staff }))
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
            data.replace(saved); setEmployeeRows(rows => rows.map(row => row.id === saved.id ? saved : row))
            setUndo(reverting ? null : { saved, previous: master.employeeId })
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
    // 13.6 (макет пользователя): таблица «запись кабинета → оператор → сотрудник ClientBus».
    const [accesses, setAccesses] = useState<api.CrmStaffAccess[]>([])
    const [employeeRows, setEmployeeRows] = useState<api.CrmMaster[]>([])
    const [staffSaving, setStaffSaving] = useState<string | null>(null)
    const [accessTick, setAccessTick] = useState(0)
    useEffect(() => {
        if (!staff) return
        const controller = new AbortController()
        void api.getCrmStaffAccesses(project.id, controller.signal).then(rows => { if (!controller.signal.aborted) setAccesses(rows) }, () => undefined)
        void (async () => {
            const rows: api.CrmMaster[] = []; let cursor: string | null | undefined
            do { const answer = await api.getCrmMasters(project.id, controller.signal, cursor)
                rows.push(...answer.items); cursor = answer.hasMore ? answer.nextCursor : null } while (cursor)
            if (!controller.signal.aborted) setEmployeeRows(rows)
        })().catch(() => undefined)
        return () => controller.abort()
    }, [staff, project.id, refresh, accessTick])
    async function saveStaffLink(row: api.CrmMaster, linkedId: string | null) {
        if (project.archived || staffSaving) return
        setStaffSaving(row.id); setSaveError(null)
        try { const saved = await api.linkCrmStaffRow(row, linkedId); data.replace(saved) }
        catch (error) { setSaveError(api.crmError(error)); data.reload() }
        finally { setStaffSaving(null) }
    }
    async function saveOperator(access: api.CrmStaffAccess, employeeId: string | null) {
        if (project.archived || staffSaving) return
        setStaffSaving(access.id); setSaveError(null)
        try { const saved = await api.linkCrmStaffAccess(access, employeeId)
            setAccesses(current => current.map(row => row.id === saved.id ? saved : row))
            setUndo(pending => pending && pending.saved.id === employeeId ? null : pending)
            setMessage(`${access.displayName}: ${employeeId ? 'оператор выбран' : 'оператор снят'}.`)}
        catch (error) { setSaveError(api.crmError(error)); setAccessTick(tick => tick + 1) }
        finally { setStaffSaving(null) }
    }
    async function saveAccessMapping(access: api.CrmStaffAccess, employeeId: string | null) {
        if (project.archived || staffSaving || !access.operatorId) return
        setStaffSaving(access.id); setSaveError(null)
        try {
            const row = employeeRows.find(candidate => candidate.id === access.operatorId) ?? await api.getCrmMaster(access.operatorId)
            const saved = await api.linkCrmMaster(row, employeeId)
            setEmployeeRows(current => current.map(candidate => candidate.id === saved.id ? saved : candidate))
            data.replace(saved); setUndo(pending => pending && pending.saved.id === saved.id ? null : pending)
            setMessage(`${access.displayName}: ${employeeId ? 'связь сохранена' : 'связь удалена'}.`)
            setAccessTick(tick => tick + 1)
        }
        catch (error) { setSaveError(api.crmError(error)); setAccessTick(tick => tick + 1) }
        finally { setStaffSaving(null) }
    }
    const cabinetFor = (externalId: string) => accesses.filter(row => row.masterExternalId === externalId)
    // Доказанная формой DIKIDI привязка записи к мастеру показывается человеком: имя из
    // строки каталога того же проекта, id — как он лежит в записи.
    const masterNote = (access: api.CrmStaffAccess) => {
        if (!access.masterExternalId) return null
        const row = employeeRows.find(value => value.externalId === access.masterExternalId && value.isMaster)
            ?? employeeRows.find(value => value.externalId === access.masterExternalId)
        return row ? `Мастер ${row.displayName} · ID ${access.masterExternalId}` : `Мастер ID ${access.masterExternalId}`
    }
    const accessTable = staff && mapping && accesses.length > 0 && data.page != null
    const operatorIds = new Set(accesses.map(row => row.operatorId).filter(id => id !== null))
    const listRows = accessTable ? (data.page?.items ?? []).filter(row => !operatorIds.has(row.id)) : data.page?.items
    const projectMasterRows = employeeRows.filter(row => row.isMaster)
    // Оператор записи кабинета — только учётка (chat-аккаунт проекта), и один к одному:
    // учётка, занятая другой записью, в списке не предлагается (бэкенд это же проверяет).
    const operatorChoices = (access: api.CrmStaffAccess) => {
        const taken = new Set(accesses.filter(row => row.id !== access.id && row.operatorId).map(row => row.operatorId as string))
        return employeeRows.filter(row => !row.isMaster && !taken.has(row.id))
    }
    return <>
        <CrmFreshness state={data.page?.dataState} archived={project.archived} />
        {mapping && <details className="crm-candidate-search"><summary>Найти сотрудника по имени или логину</summary><label>Поиск сотрудника в списках<input type="search" value={candidateQuery} maxLength={160} onChange={event => setCandidateQuery(event.target.value)} placeholder="Имя или логин" /></label></details>}
        {mapping && candidateError && <p role="alert" className="crm-error">{candidateError} <button type="button" onClick={candidateResource.reload}>Повторить загрузку сотрудников</button></p>}
        {saveError && <p role="alert" className="crm-error">{saveError}</p>}
        {(message || undo) && <div className="crm-feedback" role="status">{message} {undo && <button type="button" className="ui-button ui-button-secondary" disabled={!!saving}
            onClick={() => void save(undo.saved, undo.previous, true)}>Отменить изменение</button>}</div>}
        {accessTable && <><div className="crm-access-scroll"><table className="crm-access-table"><thead><tr>
            <th>Профили</th><th>Операторы</th><th>Сотрудник ClientBus</th></tr></thead>
            <tbody>{accesses.map(access => <tr key={access.id}>
                <td><b>{access.displayName}</b><small className="crm-access-id">{`ID ${access.externalId}`}</small>
                    <div>{access.roleLabel && <span className="crm-access-role">{access.roleLabel}</span>}
                        {access.mainOwner && <span className="crm-access-owner">владелец кабинета</span>}
                        {access.observationState === 'NOT_OBSERVED' && <span className="crm-access-old">больше нет в кабинете</span>}</div>
                    <div className="crm-access-note">{[access.phone, masterNote(access)]
                        .filter(Boolean).join(' · ') || '—'}</div></td>
                <td><select aria-label={'Оператор: ' + access.displayName + ' · ' + access.externalId} value={access.operatorId ?? ''}
                    disabled={project.archived || staffSaving !== null}
                    onChange={event => void saveOperator(access, event.target.value || null)}>
                    <option value="">Оператор не выбран</option>
                    {access.operatorId && !operatorChoices(access).some(row => row.id === access.operatorId) && <option value={access.operatorId}>{access.operatorDisplayName ?? 'Выбранная учётка'}</option>}
                    {operatorChoices(access).map(row => <option key={row.id} value={row.id}>{row.displayName} · ID {row.externalId}</option>)}
                </select>{staffSaving === access.id && <small role="status">Сохраняем…</small>}</td>
                <td><select aria-label={'Сотрудник ClientBus: ' + access.displayName + ' · ' + access.externalId} value={access.operatorMappingEmployeeId ?? ''}
                    disabled={!access.operatorId || project.archived || staffSaving !== null || !!saving || (candidateLoading && !candidates.length) || !!candidateError}
                    onChange={event => void saveAccessMapping(access, event.target.value || null)}>
                    <option value="">{access.operatorId ? 'Без связи с сотрудником' : 'Сначала выберите оператора'}</option>
                    {access.operatorMappingEmployeeId && !visibleCandidates.some(row => row.id === access.operatorMappingEmployeeId) && <option value={access.operatorMappingEmployeeId}>
                        {candidates.find(row => row.id === access.operatorMappingEmployeeId)?.displayName ?? 'Связанный сотрудник'}</option>}
                    {visibleCandidates.map(row => <option key={row.id} value={row.id}>{row.displayName || row.username} · {row.username}{!row.enabled ? ' · отключён' : ''}</option>)}
                </select>{access.operatorMappingEmployeeId && access.operatorId && <small className="crm-access-note">маппинг хранится на учётке оператора</small>}</td>
            </tr>)}</tbody></table></div></>}
        <ul className="crm-masters">{listRows?.map(master => <li className={mapping ? 'crm-master mapping' : 'crm-master'} key={master.id}>
            <div><button type="button" className="crm-text-button" onClick={() => onMaster(master.id)}>{master.displayName}</button>
                <small>ID {master.externalId}</small>
                {staff && master.isMaster && <small className="crm-role-master">Мастер</small>}
                {staff && !master.isMaster && !master.linkedId && <small>Учётка кабинета</small>}
                {staff && master.linkedId && <small>{`Один человек с: ${master.linkedDisplayName ?? 'Связанный сотрудник'}`
                    + (master.linkedExternalId ? ` · ID ${master.linkedExternalId}` : '')}</small>}
                {staff && master.isMaster && cabinetFor(master.externalId).map(row => <small key={row.id} className="crm-cabinet">
                    {`Кабинет: ${row.displayName}${row.roleLabel ? ` · ${row.roleLabel}` : ''}${row.phone ? ` · ${row.phone}` : ''}${row.mainOwner ? ' · владелец кабинета' : ''}`}</small>)}
                {master.observationState === 'NOT_OBSERVED' && <small>Отсутствует в последнем полном каталоге</small>}</div>
            {mapping && staff && !master.isMaster && <label>Это тот же человек, что сотрудник каталога<select aria-label={'Связь учётки: ' + master.displayName}
                value={master.linkedId ?? ''} disabled={project.archived || staffSaving !== null || staffSaving === master.id}
                onChange={event => void saveStaffLink(master, event.target.value || null)}>
                <option value="">Не связывать</option>
                {master.linkedId && !projectMasterRows.some(row => row.id === master.linkedId) && <option value={master.linkedId}>{master.linkedDisplayName ?? 'Связанный сотрудник'}</option>}
                {projectMasterRows.map(row => <option key={row.id} value={row.id}>{row.displayName} · ID {row.externalId}</option>)}
            </select>{staffSaving === master.id && <small role="status">Сохраняем…</small>}
            <span className="crm-notice">DIKIDI не отдаёт связь учётки с сотрудником каталога — подтвердите её вручную; сообщения автора будут резолвиться через неё.</span></label>}
            {mapping && <label>Сотрудник ClientBus<select id={'crm-link-' + master.id} aria-label={'Сотрудник: ' + master.displayName} value={master.employeeId ?? ''}
                aria-disabled={project.archived || !!saving} disabled={project.archived || (candidateLoading && !candidates.length) || !!candidateError} onChange={event => void save(master, event.target.value || null)}>
                <option value="">Без связи с сотрудником</option>
                {master.employeeId && !visibleCandidates.some(row => row.id === master.employeeId) && <option value={master.employeeId}>
                    {candidates.find(row => row.id === master.employeeId)?.displayName ?? 'Связанный сотрудник'}
                </option>}
                {visibleCandidates.map(row => <option key={row.id} value={row.id}>{row.displayName || row.username} · {row.username}{!row.enabled ? ' · отключён' : ''}</option>)}
            </select>{saving === master.id && <small role="status">Сохраняем…</small>}</label>}
            {!mapping && (!staff || master.isMaster) && <button type="button" className="crm-text-button crm-row-hint" onClick={() => onMaster(master.id)}>Услуги и условия →</button>}
        </li>)}</ul>
        {accessTable && listRows?.length === 0 && (data.page?.items.length ?? 0) > 0 &&
            <p className="crm-notice">Все учётки уже выбраны операторами в таблице выше.</p>}
        <PageStatus data={data} archived={project.archived} empty={staff
            ? unlinked ? 'Учёток без связи по этим условиям нет.' : query ? 'Учётки не найдены.' : 'В проекте пока нет учётных записей кабинета.'
            : unlinked ? 'Мастеров без связи по этим условиям нет.' : query ? 'Мастера не найдены.' : 'В каталоге пока нет мастеров.'}
            moreLabel={staff ? 'Показать ещё учётки' : 'Показать ещё мастеров'} />
    </>
}
export function CrmMasters({ project, onMaster, refresh }: ProjectProps & { onMaster: (id: string) => void }) {
    const [query, setQuery] = useState('')
    return <>
        <div className="crm-toolbar"><label className="crm-search">Найти мастера<input type="search" maxLength={160} value={query} onChange={event => setQuery(event.target.value)} /></label></div>
        <MasterRows key={query} project={project} query={query} unlinked={false} mapping={false} mastersOnly onMaster={onMaster} refresh={refresh} />
    </>
}
/** Таба «Управление»: учётки кабинета проекта (crm.employee, ismaster=false) и записи доступа;
 *  мастера — на вкладке «Мастера», привязка Employee — здесь и не зависит от роли. */
export function CrmStaff({ project, mapping, onMaster, refresh }: ProjectProps & { mapping: boolean; onMaster: (id: string) => void }) {
    const [query, setQuery] = useState(''), [unlinked, setUnlinked] = useState(false)
    return <>
        {mapping && <div className="crm-mapping-heading"><h2>Связи с сотрудниками</h2><details className="crm-mapping-help"><summary>О связях</summary><p className="crm-notice">Связь означает, что сотрудник CRM и сотрудник ClientBus — один человек: мастер или тот, кто ведёт запись. Она не даёт доступ к пространству и не назначает диалоги. Сотрудник может работать без аккаунта ClientBus.</p></details></div>}
        <div className="crm-toolbar"><label className="crm-search">Найти сотрудника<input type="search" maxLength={160} value={query} onChange={event => setQuery(event.target.value)} /></label>
            {mapping && <label className="crm-checkbox"><input type="checkbox" checked={unlinked} onChange={event => setUnlinked(event.target.checked)} />Без связи с сотрудником</label>}</div>
        <MasterRows key={query + ':' + mapping + ':' + unlinked} project={project} query={query} unlinked={mapping && unlinked} mapping={mapping} mastersOnly={false} staff onMaster={onMaster} refresh={refresh} />
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
        <div className="crm-drawer-content"><div className="crm-heading"><h2 id="crm-master-title">{master?.displayName ?? 'Карточка сотрудника'}</h2>
            <button type="button" className="ui-button ui-button-secondary" autoFocus onClick={close}>Закрыть</button></div>
            {error && <p role="alert" className="crm-error">{error} <button type="button" onClick={resource.reload}>Повторить</button></p>}
            {master && <><p>{project.displayName} · ID {master.externalId}</p>{master.isMaster
                ? <><Link onClick={close} to={'/crm?' + new URLSearchParams({ workspace: project.workspaceId, project: project.id, tab: 'schedule', master: id })}>График мастера</Link><h3>Услуги мастера</h3><ServiceRows project={project} query="" masterId={id} /></>
                : <p>Сотрудник не оказывает услуг и не имеет графика мастера — он ведёт или сопровождает записи.</p>}</>}
        </div>
    </dialog>
}
