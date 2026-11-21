import { useEffect, useRef, useState } from 'react'
import * as api from '../../api/crmApi'
import { ApiError } from '../../api/apiClient'
import { getClient } from '../../api/clientApi'
import type { ClientDto } from '../../api/types/client'
import { useCrmAction, useCrmRead, useCrmSearch } from './CrmReadShared'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../../api/events/uiScope'

const normalized = (phone: string | null) => phone?.replace(/[\s()+.\-/\u00a0]/g, '') ?? ''
const pending = (operation: api.CrmClientProvision | null) => !!operation && ['REQUESTED', 'IN_FLIGHT', 'UNKNOWN', 'CREATED'].includes(operation.status)
// Observation timestamps/statistics can advance the row version without changing the choice.
const linkChoice = (row: api.CrmClient) => JSON.stringify([row.id, row.projectId, row.externalId, row.identityKind,
    row.displayName, row.normalizedPhone, row.contacts?.phone, row.clientId,
    row.mappingUpdatedAt, row.mappingUpdatedBy, row.mappingPreviousId, row.mappingReason])
const provisionErrors: Record<string, string> = {
    CRM_CLIENT_PHONE_INVALID: 'DIKIDI отклонила телефон. Укажите международный формат: для российского номера замените начальную 8 на +7.',
    CRM_CLIENT_DUPLICATE: 'В CRM найден клиент с таким телефоном. Проверьте совпадения перед созданием.',
    CRM_CLIENT_SEARCH_INCOMPLETE: 'Поиск вернул слишком много совпадений. Уточните телефон перед созданием.',
    CRM_CLIENT_ALREADY_LINKED: 'Клиент уже связан с карточкой этого проекта CRM.',
    CRM_CONTEXT_CHANGED: 'Настройки CRM изменились. Закройте форму и откройте её заново.',
    CRM_CONFIGURATION_REQUIRED: 'Проверьте подключение CRM и доступность клиента ClientBus.',
    CRM_REAUTH_REQUIRED: 'Не удалось войти в CRM. Проверьте подключение.',
    ACCESS_DENIED: 'Прав на создание клиента больше нет.',
    CRM_OPERATION_EXPIRED: 'Время ожидания истекло до отправки в CRM. Можно создать новый запрос.',
}
const provisionError = (code: string | null) => provisionErrors[code ?? ''] ?? 'Создание не выполнено. Проверьте подключение CRM и повторите попытку.'

/** Load contact details only after an authorized employee opens the action. */
export function ClientCrmProvisionForm({ clientId, project, linked, done, close }: {
    clientId: string; project: api.CrmProject; linked: boolean; done: () => void; close: () => void
}) {
    const source = useCrmRead('crm-source-client:' + clientId, project.workspaceId, signal => getClient(clientId, signal))
    if (source.data) return <FindOrCreateCrmClient client={source.data} project={project} linked={linked} done={done} close={close} />
    return <div>
        {source.error ? <p role="alert">{source.error} <button type="button" onClick={source.reload}>Повторить загрузку клиента</button></p> : <p role="status">Загружаем контакты ClientBus…</p>}
        <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={close}>Закрыть</button>
    </div>
}

// Search imports provider rows. Its own crm.invalidated must not start another external search.
function useClientSearch(project: api.CrmProject, clientId: string, query: string) {
    const [data, setData] = useState<{ items: api.CrmClient[]; hasMore: boolean } | null>(null)
    const [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(false)
    const resource = useUiResource('crm-client-search:' + project.id + ':' + clientId + ':' + query, !!query,
        event => isAccessEvent(event) && affectsWorkspace(event, project.workspaceId),
        signal => api.searchCrmClients(project.id, clientId, query, signal),
        value => { setData(value); setError(null) }, error => setError(api.crmError(error)), () => setData(null), setLoading, { reconcile: false })
    useEffect(() => { setData(null); setError(null) }, [query])
    return { ...resource, data, error, loading }
}

export default function FindOrCreateCrmClient({ client, project, linked, done, close }: {
    client: Pick<ClientDto, 'id' | 'firstName' | 'lastName' | 'phoneList'>; project: api.CrmProject; linked: boolean
    done: () => void; close: () => void
}) {
    const [firstName, setFirstName] = useState(client.firstName), [lastName, setLastName] = useState(client.lastName)
    const [phone, setPhone] = useState(client.phoneList.length === 1 ? client.phoneList[0] : '')
    const [input, setInput] = useState(client.phoneList.length === 1 ? normalized(client.phoneList[0]) : ''), query = useCrmSearch(input)
    const [differentPerson, setDifferentPerson] = useState(false), [creating, setCreating] = useState(false)
    const [candidate, setCandidate] = useState<api.CrmClient | null>(null)
    const [receiptUncertain, setReceiptUncertain] = useState(false)
    // Same key and frozen request survive a lost HTTP response within this form session.
    const submission = useRef<{ key: string; value: { clientId: string; firstName: string; lastName: string; phone: string; differentPerson: boolean } }>()
    const targetAtOpen = useRef(project)
    const action = useCrmAction('find-or-create:' + project.id + ':' + client.id, project.workspaceId)
    const latest = useCrmRead('crm-client-provision:' + project.id + ':' + client.id, project.workspaceId,
        signal => api.getLatestClientProvision(project.id, client.id, signal))
    const matches = useClientSearch(project, client.id, query)
    const operation = latest.data, active = pending(operation)
    const running = operation?.status === 'REQUESTED' || operation?.status === 'IN_FLIGHT'
    const finished = useRef<string | null>(null)
    const watched = useRef<string | null>(null)
    const observed = useRef('')
    useEffect(() => {
        const revision = operation ? [operation.id, operation.status, operation.updatedAt].join(':') : ''
        if (observed.current === revision) return
        observed.current = revision
        if (operation && pending(operation)) {
            const newlyWatched = watched.current !== operation.id
            watched.current = operation.id; setReceiptUncertain(false)
            if (operation.phone) { setPhone(operation.phone); if (newlyWatched) setInput(normalized(operation.phone)) }
        }
        if (operation?.status === 'REJECTED') { submission.current = undefined; setReceiptUncertain(false) }
        if (operation?.status === 'CONFIRMED' && watched.current === operation.id && finished.current !== operation.id) { finished.current = operation.id; done() }
    }, [operation, done])
    useEffect(() => {
        if (!running) return
        const timer = window.setInterval(latest.reload, 3000)
        return () => window.clearInterval(timer)
    }, [running, latest.reload])
    const phoneMatches = matches.data?.items.filter(row => normalized(row.normalizedPhone) === normalized(phone)) ?? []
    const canCreate = project.capabilities.some(capability => capability.code === 'CREATE_CLIENT' && capability.availability === 'AVAILABLE')
    const statusUnavailable = !latest.loaded || !!latest.error
    const blocked = action.busy || active || linked || receiptUncertain || statusUnavailable
    function create() {
        if (action.busy) return
        const sent = submission.current ?? { key: crypto.randomUUID(), value: { clientId: client.id, firstName: firstName.trim(), lastName: lastName.trim(), phone, differentPerson } }
        submission.current = sent
        setReceiptUncertain(true)
        void action.run(async signal => {
            try { return await api.provisionCrmClient(targetAtOpen.current, sent.key, sent.value, signal) }
            catch (error) {
                if (!signal.aborted && error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408) {
                    submission.current = undefined; setReceiptUncertain(false)
                }
                throw error
            }
        }, result => {
            watched.current = result.id
            setReceiptUncertain(false)
            if (result.status === 'CONFIRMED') { finished.current = result.id; done() }
            else latest.reload()
        }, () => latest.reload())
    }
    function confirmLink(selected: api.CrmClient) {
        void action.run(async signal => {
            const link = (row: api.CrmClient) => operation?.status === 'UNKNOWN'
                ? api.resolveClientProvision(operation.id, row, signal)
                : api.linkCrmClient(row, client.id, signal)
            try { await link(selected) }
            catch (error) {
                if (!(error instanceof ApiError) || error.code !== 'CRM_VERSION_CONFLICT') throw error
                const fresh = await api.getCrmClient(selected.id, signal)
                if (linkChoice(fresh) !== linkChoice(selected)) {
                    setCandidate(fresh.clientId && fresh.clientId !== client.id ? null : fresh)
                    throw new Error(fresh.clientId && fresh.clientId !== client.id
                        ? 'Карточка уже связана с другим клиентом ClientBus. Выберите другую.'
                        : 'Данные выбранного клиента изменились. Проверьте обновлённую карточку и подтвердите связь ещё раз.')
                }
                // One bounded retry with the unchanged, explicitly confirmed choice.
                await link(fresh)
            }
        }, () => { setCandidate(null); done() }, () => { matches.reload(); latest.reload() })
    }
    return <section className="crm-form crm-find-create" aria-label="Найти или создать в CRM">
        <strong>{project.provider} · {project.displayName}</strong>
        {!latest.loaded && latest.loading && <p role="status">Проверяем предыдущие операции…</p>}
        {latest.error && <p role="alert">{latest.error} <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={latest.reload}>Повторить проверку</button></p>}
        {running && <p role="status">Создаём клиента в CRM. Можно закрыть форму — операция продолжится.</p>}
        {operation?.status === 'UNKNOWN' && <div role="status"><p>Ответ CRM не получен. Клиент мог быть создан. Найдите его ниже и подтвердите связь. Повторное создание заблокировано.</p>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={matches.loading || !query} onClick={matches.reload}>Проверить результат</button></div>}
        {operation?.status === 'CREATED' && <div role="status"><p>Клиент создан в CRM. Нужно завершить сохранение связи.</p>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={() => void action.run(signal => api.recoverClientProvision(operation.id, signal), latest.reload)}>Восстановить связь</button></div>}
        {operation?.status === 'REJECTED' && <p role="alert">{provisionError(operation.errorCode)}</p>}
        {receiptUncertain && !active && <p role="status">Проверяем, принят ли запрос. <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={create}>Проверить отправку</button></p>}
        <label>Поиск в CRM<input type="search" maxLength={160} value={input} disabled={action.busy} placeholder="Телефон или имя" onChange={e => { setInput(e.target.value); setCandidate(null) }} /></label>
        {matches.loading && !matches.data && query && <p role="status">Ищем в CRM…</p>}
        {matches.error && <p role="alert">{matches.error} <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={matches.reload}>Повторить поиск</button></p>}
        {matches.data?.hasMore && <p>Найдено много клиентов. Уточните поиск.</p>}
        {matches.data && query && !matches.data.items.length && <p>По этому запросу совпадений не найдено.</p>}
        {matches.data?.items.map(row => <div className="crm-client-pick-row" key={row.id}>
            <span>{row.displayName} · {row.normalizedPhone ?? 'без телефона'} · ID {row.externalId}</span>
            {normalized(row.normalizedPhone) === normalized(phone) && <small>телефон совпадает</small>}
            {row.clientId && row.clientId !== client.id ? <small>Связан с другим клиентом ClientBus</small> :
                <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={statusUnavailable || action.busy || running || operation?.status === 'CREATED' || row.clientId === client.id} onClick={() => { action.clearError(); setCandidate(row) }}>{row.clientId === client.id ? 'Уже связан' : 'Выбрать'}</button>}
        </div>)}
        {candidate && <div className="crm-notice">
            <p>Связать «{candidate.displayName}» ({candidate.normalizedPhone ?? 'без телефона'}) с «{[client.firstName, client.lastName].filter(Boolean).join(' ')}»?</p>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={statusUnavailable || action.busy || running || operation?.status === 'CREATED'} onClick={() => confirmLink(candidate)}>Связать существующего</button>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={() => setCandidate(null)}>Отмена выбора</button>
        </div>}
        {!active && !linked && canCreate && <>
            {!creating ? <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={statusUnavailable || receiptUncertain} onClick={() => setCreating(true)}>Создать нового клиента CRM</button> :
                <form onSubmit={e => { e.preventDefault(); create() }}>
                    {client.phoneList.length > 1 && <label>Телефон из ClientBus
                        <select aria-label="Выбрать телефон ClientBus" disabled={blocked} value={client.phoneList.includes(phone) ? phone : ''} onChange={e => { setPhone(e.target.value); setInput(normalized(e.target.value)); setDifferentPerson(false); setCandidate(null) }}>
                            <option value="">Выберите номер</option>{client.phoneList.map(value => <option key={value}>{value}</option>)}
                        </select>
                    </label>}
                    <label>Телефон для CRM<input type="tel" maxLength={30} required value={phone} disabled={blocked} onChange={e => { setPhone(e.target.value); setInput(normalized(e.target.value)); setDifferentPerson(false); setCandidate(null) }} /></label>
                    {!blocked && /^89[0-9]{9}$/.test(normalized(phone)) && <p><small>Если это российский номер, DIKIDI ожидает +7 вместо 8. </small>
                        <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={() => { const value = '+7' + normalized(phone).slice(1); setPhone(value); setInput(normalized(value)); setDifferentPerson(false); setCandidate(null) }}>Использовать +7 вместо 8</button></p>}
                    <label>Имя<input required maxLength={50} value={firstName} disabled={blocked} onChange={e => setFirstName(e.target.value)} /></label>
                    <label>Фамилия<input maxLength={50} value={lastName} disabled={blocked} onChange={e => setLastName(e.target.value)} /></label>
                    <p><small>Контакты заполнены из ClientBus и будут скопированы один раз. Исправления сохранятся только в CRM. ClientBus не отправляет сообщений при создании.</small></p>
                    {!!phoneMatches.length && <label className="crm-checkbox"><input type="checkbox" checked={differentPerson} disabled={blocked} onChange={e => setDifferentPerson(e.target.checked)} />Это другой человек, несмотря на совпадение телефона</label>}
                    <button type="submit" className="ui-button ui-button-sm ui-button-primary" disabled={blocked || !firstName.trim() || !/^[0-9]{7,15}$/.test(normalized(phone)) || matches.loading || !!matches.error || !!matches.data?.hasMore || (!!phoneMatches.length && !differentPerson)}>Создать и связать</button>
                </form>}
        </>}
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}
        <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={close}>Закрыть</button>
    </section>
}
