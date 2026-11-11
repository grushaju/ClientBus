import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import * as api from '../../api/crmApi'
import { getApiErrorCode, getApiErrorStatus } from '../../api/apiClient'
import { CrmDirectoryPicker } from './CrmDirectoryPicker'
import { CrmBookingTime } from './CrmBookingTime'
import { useCrmPages } from './useCrmPages'
import { CrmDialog, CrmPageStatus, crmLocal, crmMoney, useCrmAction, useCrmRead } from './CrmReadShared'
import { readIntent, resolveIntent, sendIntent, type CrmIntentScope } from './crmIntent'

const states = { REQUESTED: 'Принята, ожидает выполнения', IN_FLIGHT: 'Выполняется', CONFIRMED: 'Подтверждена', REJECTED: 'Отклонена', UNKNOWN: 'Результат неизвестен — требуется сверка' }
const types = { CREATE: 'Создание', MOVE: 'Перенос', CANCEL: 'Отмена' }
function supported(project: api.CrmProject, type: api.CrmOperation['type']) {
    return !project.archived && project.enabled && !!project.zoneId && project.connectionState === 'CONNECTED'
        && project.capabilities?.some(capability => capability.code === ({ CREATE: 'CREATE_APPOINTMENT', MOVE: 'MOVE_APPOINTMENT', CANCEL: 'CANCEL_APPOINTMENT' }[type]) && capability.availability === 'AVAILABLE')
}
function useIntentScope(project: api.CrmProject): CrmIntentScope {
    const { currentEmployee } = useAuth()
    return useMemo(() => ({ organizationId: currentEmployee?.organizationId ?? '', employeeId: currentEmployee?.id ?? '', projectId: project.id }), [currentEmployee?.organizationId, currentEmployee?.id, project.id])
}
function PendingCreations({ project }: { project: api.CrmProject }) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const manage = isSuperAdmin || employeeWorkspaces.some(workspace => workspace.workspaceId === project.workspaceId && workspace.isSupervisor)
    const [cursor, page] = useState<string>()
    const scope = useIntentScope(project), [, changed] = useState(0)
    let pending: ReturnType<typeof readIntent> = null, storageError: string | null = null
    try { pending = readIntent(scope) } catch (error) { storageError = error instanceof Error ? error.message : 'Хранилище незавершённой операции недоступно.' }
    const key = pending?.key
    useEffect(() => {
        const refresh = () => changed(value => value + 1)
        window.addEventListener('storage', refresh); window.addEventListener('clientbus:crm-intent', refresh)
        return () => { window.removeEventListener('storage', refresh); window.removeEventListener('clientbus:crm-intent', refresh) }
    }, [scope])
    const data = useCrmRead('crm-pending-creations:' + project.id + ':' + project.contextVersion + ':' + manage + ':' + cursor + ':' + key, project.workspaceId, async signal => {
        const [operations, recovery] = await Promise.all([api.getCrmPendingCreations(project.id, { own: !manage, cursor }, signal), key ? api.findCrmOperation(project.id, key, signal).catch(error => {
            if (getApiErrorStatus(error) === 404 && getApiErrorCode(error) === 'NOT_FOUND') return null
            throw error
        }) : Promise.resolve(null)])
        if (recovery) await resolveIntent(scope, recovery)
        return { operations, recovery }
    })
    const action = useCrmAction('crm-reconcile:' + project.id, project.workspaceId), entries = data.data?.operations.items ?? []
    const rows = data.data?.recovery && !entries.some(row => row.id === data.data!.recovery!.id) ? [data.data.recovery, ...entries] : entries
    if (!storageError && !key && !rows.length && !data.error && !cursor) return null
    const title = pending && pending.type !== 'CREATE' ? 'Незавершённое действие с записью' : 'Незавершённое создание записи'
    return <section aria-label={title} className="crm-notice"><h3>{title}</h3>
        <p>Проверка результата не отправляет команду повторно.</p>
        {storageError && <p role="alert" className="crm-error">{storageError}</p>}
        {key && !data.loading && data.data && !data.data.recovery && <p className="crm-notice">Ответ на сохранённое намерение пока не найден. Это не подтверждает отказ. Повторите поиск; новый запрос автоматически не отправляется.</p>}
        <button type="button" className="ui-button ui-button-secondary" disabled={data.loading} onClick={data.reload}>Проверить результат</button>
        {(data.error || action.error) && <p role="alert" className="crm-error">{action.error || data.error}</p>}
        {data.loading && !data.data && <p role="status">Загрузка операций…</p>}
        <ul className="crm-appointments">{rows.map(row => <li className="crm-operation" key={row.id}>
            <strong>{types[row.type]} · {states[row.status]}</strong><small>{new Date(row.createdAt).toLocaleString('ru-RU')}</small><details><summary>Технические детали</summary><span>ID: {row.id}</span></details>
            {row.externalAppointmentId && <span>Внешний ID: {row.externalAppointmentId}</span>}{row.errorCode && <span className="crm-error-text">{row.errorCode}</span>}
            {row.retryAt && <span>Обращения доступны после {new Date(row.retryAt).toLocaleString('ru-RU')}</span>}
            {row.status === 'UNKNOWN' && <><p>Действие могло выполниться в DIKIDI. Сверка проверяет результат и не повторяет команду.</p><button type="button" className="ui-button ui-button-secondary" disabled={action.busy} onClick={() => void action.run(signal => api.reconcileCrmOperation(row.id, signal), () => data.reload())}>Сверить результат</button></>}
        </li>)}</ul>
        {cursor && <button type="button" className="ui-button ui-button-secondary" onClick={() => page(undefined)}>К началу списка</button>}
        {data.data?.operations.hasMore && <button type="button" className="ui-button ui-button-secondary" onClick={() => page(data.data!.operations.nextCursor ?? undefined)}>Следующие незавершённые создания</button>}
    </section>
}
export function CrmOperationsPanel({ project }: { project: api.CrmProject }) {
    const [create, setCreate] = useState(false)
    return <section className="crm-operation-panel"><button type="button" className="ui-button ui-button-primary" disabled={!supported(project, 'CREATE')}
        title={supported(project, 'CREATE') ? undefined : 'Создание недоступно. Проверьте подключение, часовой пояс и состояние проекта CRM.'} onClick={() => setCreate(true)}>+ Запись</button>
        <PendingCreations key={project.id + ':' + project.contextVersion} project={project} />
        {create && <CreateDialog key={project.id + ':' + project.contextVersion + ':' + project.zoneRevision} project={project} close={() => setCreate(false)} />}</section>
}
export function CrmAppointmentHistory({ project, row }: { project: api.CrmProject; row: api.CrmAppointment }) {
    const [open, setOpen] = useState(false)
    return <details open={open} onToggle={event => setOpen(event.currentTarget.open)}><summary>История действий</summary>
        {open && <AppointmentHistoryRows key={row.id + ':' + project.contextVersion} project={project} row={row} />}</details>
}
function AppointmentHistoryRows({ project, row }: { project: api.CrmProject; row: api.CrmAppointment }) {
    const { isSuperAdmin, employeeWorkspaces } = useAuth()
    const manage = isSuperAdmin || employeeWorkspaces.some(workspace => workspace.workspaceId === project.workspaceId && workspace.isSupervisor)
    const [cursor, page] = useState<string>()
    const scope = useIntentScope(project)
    const data = useCrmRead('crm-appointment-history:' + row.id + ':' + project.contextVersion + ':' + manage + ':' + cursor, project.workspaceId,
        async signal => {
            const result = await api.getCrmAppointmentHistory(row.id, { own: !manage, cursor }, signal)
            let pending: ReturnType<typeof readIntent> = null
            // Read-only history also works without storage/Web Locks. Writes retain their existing safeguards.
            try { pending = readIntent(scope) } catch { /* Storage errors must not hide the history. */ }
            for (const entry of result.items) if (pending && pending.key === entry.operation.idempotencyKey
                    && entry.operation.initiatorEmployeeId === scope.employeeId
                    && ['CONFIRMED', 'REJECTED'].includes(entry.operation.status)) await resolveIntent(scope, entry.operation)
            return result
        })
    const action = useCrmAction('crm-history-reconcile:' + row.id, project.workspaceId)
    async function reconcile(id: string, signal: AbortSignal) {
        const result = await api.reconcileCrmOperation(id, signal)
        if (result.initiatorEmployeeId === scope.employeeId) await resolveIntent(scope, result)
        return result
    }
    return <><p>Действия, выполненные через ClientBus.{!manage && ' Показаны только ваши действия.'}</p>
        <button type="button" className="ui-button ui-button-secondary" disabled={data.loading || action.busy} onClick={data.reload}>Обновить историю</button>
        {(data.error || action.error) && <p role="alert" className="crm-error">{action.error || data.error}</p>}
        {data.loading && !data.data && <p role="status">Загрузка истории…</p>}
        {data.data && !data.data.items.length && <p>Действий в ClientBus пока нет.</p>}
        <ul className="crm-appointments">{data.data?.items.map(entry => <li key={entry.operation.id} className="crm-operation">
            <strong>{types[entry.operation.type]} · {states[entry.operation.status]}</strong>
            <span>{entry.authorName} · {new Date(entry.operation.createdAt).toLocaleString('ru-RU')}</span>
            {entry.oldStart && <span>Прежнее начало: {crmLocal(entry.oldStart)}</span>}
            {entry.newStart && <span>{entry.operation.type === 'MOVE' ? 'Новое начало' : 'Начало'}: {crmLocal(entry.newStart)} · {entry.zoneId}</span>}
            {entry.cancelComment && <p>Комментарий к отмене: {entry.cancelComment}</p>}
            {entry.operation.status === 'UNKNOWN' && <button type="button" className="ui-button ui-button-secondary" disabled={action.busy}
                onClick={() => void action.run(signal => reconcile(entry.operation.id, signal), data.reload)}>Сверить результат</button>}
            {entry.operation.retryAt && <span>Обращения доступны после {new Date(entry.operation.retryAt).toLocaleString('ru-RU')}</span>}
            <details><summary>Технические детали</summary><span>ID: {entry.operation.id}</span>
                {entry.operation.externalAppointmentId && <span>Внешний ID: {entry.operation.externalAppointmentId}</span>}
                {entry.operation.errorCode && <span>{entry.operation.errorCode}</span>}</details>
        </li>)}</ul>
        {cursor && <button type="button" className="ui-button ui-button-secondary" onClick={() => page(undefined)}>К началу истории</button>}
        {data.data?.hasMore && <button type="button" className="ui-button ui-button-secondary" onClick={() => page(data.data!.nextCursor ?? undefined)}>Следующая страница истории</button>}
    </>
}
function CreateDialog({ project, close }: { project: api.CrmProject; close: () => void }) {
    const [master, setMaster] = useState(''), [client, setClient] = useState(''), [comment, setComment] = useState('')
    return <CrmDialog title="Создать запись CRM" close={close}><p>{project.displayName} · {project.zoneId}</p>
        <CrmDirectoryPicker project={project} kind="master" value={master} change={setMaster} all={false} />
        <CrmDirectoryPicker project={project} kind="client" value={client} change={setClient} all={false} />
        <label>Комментарий<textarea value={comment} maxLength={2000} onChange={event => setComment(event.target.value)} /></label>
        {master && <CreateSelection key={master} project={project} master={master} client={client} comment={comment} saved={close} />}</CrmDialog>
}
type Quote = { id: string; expectedVersion: number; label: string }
function CreateSelection({ project, master, client, comment, saved }: { project: api.CrmProject; master: string; client: string; comment: string; saved: () => void }) {
    const scope = useIntentScope(project), action = useCrmAction('crm-create:' + project.id, project.workspaceId)
    const data = useCrmPages('crm-create-offers:' + project.id + ':' + master, project.workspaceId, (signal, cursor) => api.getCrmServices(project.id, { masterId: master, cursor }, signal))
    const [selected, setSelected] = useState<Quote[]>([])
    const offers = data.page?.items.flatMap(service => service.offers.filter(offer => offer.masterId === master && offer.offerState.active !== false).map(offer => ({ ...offer, name: service.displayName }))) ?? []
    const stale = selected.some(choice => !offers.some(offer => offer.id === choice.id && offer.version === choice.expectedVersion))
    const choices = selected.map(({ id, expectedVersion }) => ({ id, expectedVersion }))
    const valid = selected.length > 0 && selected.length <= 10 && !stale
    const duration = selected.reduce((sum, choice) => sum + (offers.find(offer => offer.id === choice.id)?.durationSeconds ?? 0), 0)
    const submit = (start: string) => <button type="button" className="ui-button ui-button-primary" disabled={!client || !start || !valid || action.busy || !supported(project, 'CREATE')}
        onClick={() => void action.run(signal => sendIntent(scope, 'CREATE', key => api.createCrmAppointment(project.id, key, { contextVersion: project.contextVersion, zoneRevision: project.zoneRevision, crmClientId: client, masterId: master, offers: choices, localStart: start, comment: comment.trim() || undefined }, signal), signal), saved)}>Создать запись</button>
    return <><h3>Услуги и предложения мастера</h3><ul className="crm-appointments">{offers.map(offer => <li key={offer.id}><label className="crm-checkbox"><input type="checkbox" checked={selected.some(row => row.id === offer.id)} disabled={action.busy || !selected.some(row => row.id === offer.id) && (offer.price == null || offer.durationSeconds == null)}
        onChange={event => { setSelected(previous => event.target.checked ? [...previous, { id: offer.id, expectedVersion: offer.version, label: offer.name + ' · ' + crmMoney(offer.price, offer.currency) }] : previous.filter(row => row.id !== offer.id)) }} />{offer.name} · {crmMoney(offer.price, offer.currency)} · {offer.durationSeconds == null ? 'длительность неизвестна' : offer.durationSeconds / 60 + ' мин'}</label></li>)}</ul>
        <CrmPageStatus data={data} />{stale && <p role="alert">Условия выбранных предложений изменились. Выберите услуги заново.</p>}
        {selected.length > 0 && <button type="button" className="ui-button ui-button-secondary" disabled={action.busy} onClick={() => setSelected([])}>Сбросить выбранные услуги</button>}
        {selected.length > 0 && <p>Выбрано: {selected.map(row => row.label).join('; ')}</p>}{action.error && <p role="alert" className="crm-error">{action.error} При потере ответа проверьте незавершённое действие или историю записи.</p>}
        {valid ? <CrmBookingTime project={project} master={master} offers={choices} durationSeconds={duration} disabled={action.busy}>{submit}</CrmBookingTime> : submit('')}</>
}
export function CrmAppointmentActions({ project, row, changed }: { project: api.CrmProject; row: api.CrmAppointment; changed: () => void }) {
    const [mode, setMode] = useState<'MOVE' | 'CANCEL' | null>(null), [accepted, setAccepted] = useState(false)
    return <><div className="crm-actions"><button type="button" className="ui-button ui-button-secondary" disabled={!supported(project, 'MOVE') || row.kind !== 'APPOINTMENT' || row.appointmentState !== 'SCHEDULED'} onClick={() => setMode('MOVE')}>Перенести запись</button>
        <button type="button" className="ui-button ui-button-danger" disabled={!supported(project, 'CANCEL') || row.kind !== 'APPOINTMENT' || row.appointmentState !== 'SCHEDULED'} onClick={() => setMode('CANCEL')}>Отменить запись</button></div>
        <CrmAppointmentHistory project={project} row={row} />
        {accepted && <p role="status">Операция принята. Её результат доступен в истории действий этой записи.</p>}
        {mode && <ChangeForm key={mode + ':' + row.id + ':' + project.contextVersion + ':' + project.zoneRevision} project={project} row={row} mode={mode} close={() => setMode(null)} saved={() => { setMode(null); setAccepted(true); changed() }} />}</>
}
function ChangeForm({ project, row, mode, close, saved }: { project: api.CrmProject; row: api.CrmAppointment; mode: 'MOVE' | 'CANCEL'; close: () => void; saved: () => void }) {
    const scope = useIntentScope(project), action = useCrmAction('crm-change:' + row.id, project.workspaceId)
    const [reason, setReason] = useState(''), [version] = useState(row.version)
    const master = row.masters.length === 1 ? row.masters[0].masterId : null
    const duration = row.services.reduce((sum, service) => sum + (service.durationSeconds ?? 0), 0)
    const canChooseTime = !!master && row.mastersComplete && row.servicesComplete && row.services.length > 0 && row.services.every(service => service.durationSeconds != null && service.durationSeconds > 0)
    const actions = (start: string) => <div className="crm-actions"><button type="button" className="ui-button ui-button-secondary" disabled={action.busy} onClick={close}>Закрыть</button>
        <button type="button" className={'ui-button ' + (mode === 'CANCEL' ? 'ui-button-danger' : 'ui-button-primary')} disabled={action.busy || mode === 'MOVE' && (!start || row.version !== version) || !supported(project, mode)}
            onClick={() => void action.run(signal => sendIntent(scope, mode, key => api.changeCrmAppointment(row.id, key, mode, { contextVersion: project.contextVersion, zoneRevision: project.zoneRevision, expectedVersion: version, ...(mode === 'MOVE' ? { localStart: start } : { reason: reason.trim() || undefined }) }, signal), signal), saved)}>{mode === 'MOVE' ? 'Подтвердить перенос' : 'Подтвердить отмену'}</button></div>
    return <section className="crm-form" aria-label={mode === 'MOVE' ? 'Перенос записи' : 'Отмена записи'}><h3>{mode === 'MOVE' ? 'Перенос записи' : 'Отмена записи'}</h3><p>{project.displayName} · {crmLocal(row.localStart)} · {row.zoneId}</p>
        {mode === 'CANCEL' && <label>Комментарий к отмене (сохранится в ClientBus)<textarea value={reason} maxLength={2000} onChange={event => setReason(event.target.value)} /></label>}
        {action.error && <p role="alert" className="crm-error">{action.error} При потере ответа проверьте незавершённое действие или историю записи.</p>}
        {mode === 'MOVE' && canChooseTime ? <CrmBookingTime project={project} master={master!} offers={[]} durationSeconds={duration}
            appointment={{ id: row.id, version }} initialDate={row.localStart.slice(0, 10)} disabled={action.busy || row.version !== version}>{actions}</CrmBookingTime>
            : <>{mode === 'MOVE' && <p>Для выбора свободного времени нужны полные услуги и мастер записи. Обновите запись и каталог мастеров.</p>}{actions('')}</>}</section>
}
