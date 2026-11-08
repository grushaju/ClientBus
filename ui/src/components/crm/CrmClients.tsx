import { useState } from 'react'
import { Link } from 'react-router-dom'
import * as api from '../../api/crmApi'
import { useCrmPages } from './useCrmPages'
import { CrmDialog, CrmPageStatus, CrmStatus, useCrmAction, useCrmRead, useCrmSearch } from './CrmReadShared'

function ClientCandidates({ row, choose, disabled }: { row: api.CrmClient; choose: (item: api.CrmClientCandidate) => void; disabled: boolean }) {
    const [input, setInput] = useState(''), query = useCrmSearch(input)
    return <><label>Найти клиента ClientBus<input type="search" maxLength={160} value={input} onChange={event => setInput(event.target.value)} /></label>
        <CandidateRows key={query} row={row} query={query} choose={choose} disabled={disabled} /></>
}
function CandidateRows({ row, query, choose, disabled }: { row: api.CrmClient; query: string; choose: (item: api.CrmClientCandidate) => void; disabled: boolean }) {
    const data = useCrmRead('crm-client-candidates:' + row.id + ':' + query, undefined, signal => api.getCrmClientCandidates(row.id, query, signal))
    return <><p>Совпадение телефона — подсказка. Связь устанавливается только после подтверждения.</p>
        {data.error && <p role="alert">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.data && <p>Ищем клиентов…</p>}
        <ul className="crm-picker-results">{data.data?.map(item => <li key={item.id}><button type="button" className="crm-text-button" disabled={disabled} onClick={() => choose(item)}>
            {item.firstName} {item.lastName} · {item.phoneList.join(', ') || 'Без телефона'}{item.phoneMatch && ' · телефон совпадает'}</button></li>)}</ul>
        {data.data && !data.data.length && <p>Кандидаты не найдены. Уточните поиск или создайте клиента.</p>}</>
}
export function CrmClientCard({ project, id, canManage, close, changed }: { project: api.CrmProject; id: string; canManage: boolean; close: () => void; changed: () => void }) {
    const data = useCrmRead('crm-client:' + id, project.workspaceId, signal => api.getCrmClient(id, signal))
    const action = useCrmAction('crm-client:' + id, project.workspaceId)
    const [mode, setMode] = useState<'link' | 'create' | null>(null), [selected, setSelected] = useState<api.CrmClientCandidate | null | undefined>()
    const [baseVersion, setBaseVersion] = useState(0)
    const [firstName, setFirstName] = useState(''), [lastName, setLastName] = useState(''), [phones, setPhones] = useState('')
    const row = data.data, mutable = row && !project.archived && (!row.clientId || canManage)
    const saved = () => { setMode(null); setSelected(undefined); data.reload(); changed() }
    return <CrmDialog title="Клиент CRM" close={close}>
        {(data.error || action.error) && <p role="alert" className="crm-error">{action.error || data.error} <button type="button" onClick={data.reload}>Перечитать карточку</button></p>}
        {row ? <><h3>{row.displayName}</h3><p>{project.displayName} · ID {row.externalId}</p>
            {Object.entries(row.contacts).map(([key, value]) => <p key={key}>{key}: {value}</p>)}
            {row.clientId ? <p>Связан с <Link to={'/clients/' + encodeURIComponent(row.clientId)}>клиентом ClientBus</Link></p> : <p>Без связи с ClientBus</p>}
            {row.mappingUpdatedAt && <p>Связь изменена: {new Date(row.mappingUpdatedAt).toLocaleString('ru-RU')} · {row.mappingReason} · автор {row.mappingUpdatedBy}</p>}
            <Link to={'/crm?' + new URLSearchParams({ tab: 'appointments', workspace: project.workspaceId, project: project.id, crmClientId: row.id })}>Визиты клиента</Link>
            {mutable && <div className="crm-actions"><button type="button" className="ui-button ui-button-secondary" disabled={action.busy || data.loading} onClick={() => { setBaseVersion(row.version); setMode('link'); setSelected(undefined) }}>{row.clientId ? 'Изменить связь' : 'Выбрать клиента ClientBus'}</button>
                {!row.clientId && <button type="button" className="ui-button ui-button-secondary" disabled={action.busy || data.loading} onClick={() => { setBaseVersion(row.version); setMode('create'); setSelected(undefined) }}>Создать клиента ClientBus</button>}
                {row.clientId && <button type="button" className="ui-button ui-button-danger" disabled={action.busy || data.loading} onClick={() => { setBaseVersion(row.version); setMode('link'); setSelected(null) }}>Снять связь</button>}</div>}
            {mutable && mode === 'link' && <div className="crm-form"><ClientCandidates row={row} choose={setSelected} disabled={action.busy} />
                {selected !== undefined && <><p>{selected === null ? 'Подтвердите снятие связи.' : `Связать с ${selected.firstName} ${selected.lastName} · ${selected.phoneList.join(', ') || 'без телефона'}?`}</p>
                    <button type="button" className="ui-button ui-button-primary" disabled={action.busy || data.loading} onClick={() => void action.run(signal => api.linkCrmClient({ ...row, version: baseVersion }, selected?.id ?? null, signal), saved, () => { setMode(null); setSelected(undefined); data.reload() })}>{selected === null ? 'Подтвердить снятие' : 'Подтвердить связь'}</button></>}
            </div>}
            {mutable && mode === 'create' && <form className="crm-form" onSubmit={event => { event.preventDefault(); if (!action.busy && !data.loading) void action.run(signal => api.createLinkedCrmClient({ ...row, version: baseVersion }, { firstName: firstName.trim(), lastName: lastName.trim(), phoneList: phones.split(',').map(value => value.trim()).filter(Boolean) }, signal), saved, () => { setMode(null); data.reload() }) }}>
                <p>Введите и подтвердите данные новой карточки. Профиль DIKIDI автоматически не копируется.</p>
                <label>Имя<input required maxLength={50} value={firstName} onChange={event => setFirstName(event.target.value)} /></label>
                <label>Фамилия<input maxLength={50} value={lastName} onChange={event => setLastName(event.target.value)} /></label>
                <label>Телефоны через запятую<input value={phones} maxLength={419} onChange={event => setPhones(event.target.value)} /></label>
                <button className="ui-button ui-button-primary" disabled={action.busy || data.loading || !firstName.trim()}>Создать и связать</button>
            </form>}
            {mode && <button type="button" className="ui-button ui-button-secondary" disabled={action.busy} onClick={() => { setMode(null); setSelected(undefined) }}>Отмена</button>}
            {project.archived && <p>Архив: доступен только просмотр.</p>}
        </> : !data.error && <p>Загрузка клиента…</p>}
    </CrmDialog>
}
function ClientRows({ project, query, unlinked, canManage }: { project: api.CrmProject; query: string; unlinked: boolean; canManage: boolean }) {
    const data = useCrmPages('crm-clients:' + project.id + ':' + query + ':' + unlinked, project.workspaceId, (signal, cursor) => api.getCrmClients(project.id, { q: query, unlinked, cursor }, signal))
    const action = useCrmAction('refresh-clients:' + project.id, project.workspaceId)
    const [selected, setSelected] = useState<string | null>(null)
    return <><button type="button" className="ui-button ui-button-secondary" disabled={action.busy || project.archived || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
        onClick={() => void action.run(signal => api.refreshCrmData(project.id, { dataset: 'CLIENTS' }, signal), data.reload)}>Обновить клиентов</button>
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}<CrmStatus state={data.page?.dataState} archived={project.archived} />
        <ul className="crm-masters">{data.page?.items.map(row => <li className="crm-master" key={row.id}><div><button type="button" className="crm-text-button" onClick={() => setSelected(row.id)}>{row.displayName}</button>
            <small>{Object.values(row.contacts).join(' · ') || 'Контакты не указаны'}</small></div><span>{row.clientId ? 'Связан с ClientBus' : 'Без связи'}{row.lastObservedAt && ' · ' + new Date(row.lastObservedAt).toLocaleString('ru-RU')}</span></li>)}</ul>
        <CrmPageStatus data={data} empty="Клиенты по этим условиям не найдены." />
        {selected && <CrmClientCard key={selected} id={selected} project={project} canManage={canManage} close={() => setSelected(null)} changed={data.reload} />}</>
}
export function CrmClients({ project, canManage }: { project: api.CrmProject; canManage: boolean }) {
    const [input, setInput] = useState(''), [unlinked, setUnlinked] = useState(false), query = useCrmSearch(input)
    return <><div className="crm-toolbar"><label className="crm-search">Найти клиента CRM<input type="search" maxLength={160} value={input} onChange={event => setInput(event.target.value)} /></label>
        <label className="crm-checkbox"><input type="checkbox" checked={unlinked} onChange={event => setUnlinked(event.target.checked)} />Без связи с ClientBus</label></div>
        <ClientRows key={project.id + ':' + query + ':' + unlinked} project={project} query={query} unlinked={unlinked} canManage={canManage} /></>
}
