import { useState } from 'react'
import { Link } from 'react-router-dom'
import * as api from '../../api/crmApi'
import { CrmClientAvatar } from './CrmClientAvatar'
import { CrmClientAvatarTransfer } from './CrmClientAvatarTransfer'
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
    const [notice, setNotice] = useState('')
    const [firstName, setFirstName] = useState(''), [lastName, setLastName] = useState(''), [phones, setPhones] = useState('')
    const row = data.data, mutable = row && !project.archived && (!row.clientId || canManage)
    const saved = () => { setMode(null); setSelected(undefined); data.reload(); changed() }
    return <CrmDialog title="Клиент CRM" close={close}>
        {(data.error || action.error) && <p role="alert" className="crm-error">{action.error || data.error} <button type="button" onClick={data.reload}>Перечитать карточку</button></p>}
        {row ? <><div className="crm-client-card-head"><CrmClientAvatar id={row.id} avatarUrl={row.avatarUrl} displayName={row.displayName} size={56} /><div><h3>{row.displayName}</h3><p className="crm-client-project">{project.displayName}</p></div></div>
            <section className="crm-client-section" aria-label="Контакты"><h3>Контакты</h3>
                <dl className="crm-client-facts">{Object.entries(row.contacts).filter(([, value]) => value).map(([key, value]) => <div key={key}><dt>{contactLabel(key)}</dt><dd>{value}</dd></div>)}</dl>
                {!Object.values(row.contacts).some(Boolean) && <p>Контакты не указаны</p>}
            </section>
            <section className="crm-client-section" aria-label="Статистика клиента"><h3>Статистика</h3>
                <dl className="crm-client-stats"><div><dt>Визиты</dt><dd>{row.visitCount ?? 'Нет данных'}</dd></div><div><dt>Средний чек</dt><dd>{clientMoney(row, row.averageCheck)}</dd></div><div><dt>Потрачено</dt><dd>{clientMoney(row, row.totalSpent)}</dd></div></dl>
                {row.statisticsUpdatedAt && <p className="crm-client-note">Обновлено: {new Date(row.statisticsUpdatedAt).toLocaleString('ru-RU')}</p>}
                <Link to={'/crm?' + new URLSearchParams({ tab: 'appointments', workspace: project.workspaceId, project: project.id, crmClientId: row.id })}>Открыть визиты клиента →</Link>
            </section>
            <section className="crm-client-section" aria-label="Связь с ClientBus"><h3>Клиент в ClientBus</h3>
            {row.clientId ? <p>Связь установлена · <Link to={'/clients/' + encodeURIComponent(row.clientId)}>Открыть карточку клиента</Link></p> : <p>Карточка ClientBus не связана</p>}
            {row.mappingUpdatedAt && <p className="crm-client-note">{mappingLabel(row.mappingReason)} · {new Date(row.mappingUpdatedAt).toLocaleString('ru-RU')}</p>}
            {notice && <p role="status">{notice}</p>}
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
            {canManage && !project.archived && project.enabled && project.provider === 'DIKIDI' && row.clientId && !mode &&
                <CrmClientAvatarTransfer key={row.clientId} row={row} action={action} disabled={data.loading}
                    done={() => { setNotice('Аватар перенесён в DIKIDI'); data.reload() }} />}
            </section>
            <details className="crm-client-source"><summary>Данные CRM</summary><dl className="crm-client-facts"><div><dt>Номер клиента в CRM</dt><dd>{row.externalId}</dd></div></dl></details>
            {project.archived && <p>Архив: доступен только просмотр.</p>}
        </> : !data.error && <p>Загрузка клиента…</p>}
    </CrmDialog>
}

function contactLabel(key: string) {
    return ({ phone: 'Телефон', mobile: 'Мобильный телефон', email: 'Электронная почта', telegram: 'Telegram', whatsapp: 'WhatsApp', vk: 'ВКонтакте' } as Record<string, string>)[key.toLowerCase()] ?? 'Другой контакт'
}
function mappingLabel(reason: string | null) {
    return ({ LINK: 'Связь установлена', RELINK: 'Связь изменена', UNLINK: 'Связь снята', CREATE: 'Карточка создана и связана', CLIENT_DELETED: 'Связь снята при удалении клиента' } as Record<string, string>)[reason ?? ''] ?? 'Связь обновлена'
}
type ClientSort = 'NAME' | 'VISITS' | 'SPENT' | 'AVERAGE_CHECK'
function clientMoney(row: api.CrmClient, value: string | null | undefined) {
    if (value == null) return 'Нет данных'
    const [whole, fraction] = value.split('.')
    const amount = new Intl.NumberFormat('ru-RU').format(BigInt(whole)) + (fraction ? ',' + fraction : '')
    return row.currency ? amount + ' ' + row.currency : amount
}
function ClientRows({ project, query, unlinked, canManage, sort, direction, changeSort }: { project: api.CrmProject; query: string; unlinked: boolean; canManage: boolean; sort: ClientSort; direction: 'ASC' | 'DESC'; changeSort: (sort: ClientSort) => void }) {
    const data = useCrmPages('crm-clients:' + project.id + ':' + query + ':' + unlinked + ':' + sort + ':' + direction, project.workspaceId, (signal, cursor) => api.getCrmClients(project.id, { q: query, unlinked, cursor, sort, direction }, signal))
    const action = useCrmAction('refresh-clients:' + project.id, project.workspaceId)
    const [selected, setSelected] = useState<string | null>(null)
    const sortHeader = (column: ClientSort, label: string) => <th scope="col" aria-sort={sort === column ? direction === 'ASC' ? 'ascending' : 'descending' : 'none'}>
        <button type="button" className="crm-column-sort" onClick={() => changeSort(column)}>{label}<span aria-hidden="true">{sort === column ? direction === 'ASC' ? ' ↑' : ' ↓' : ' ↕'}</span></button></th>
    return <><button type="button" className="ui-button ui-button-secondary" disabled={action.busy || project.archived || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
        onClick={() => void action.run(signal => api.refreshCrmData(project.id, { dataset: 'CLIENTS' }, signal), data.reload)}>Обновить клиентов</button>
        <button type="button" className="ui-button ui-button-secondary" disabled={action.busy || project.archived || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
            onClick={() => void action.run(signal => api.refreshCrmData(project.id, { dataset: 'CLIENT_STATISTICS' }, signal), data.reload)}>Обновить статистику</button>
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}<CrmStatus state={data.page?.dataState} archived={project.archived} />
        {data.page && data.page.items.length > 0 && <div className="crm-clients-table-scroll"><table className="crm-masters crm-clients-table">
            <thead><tr>{sortHeader('NAME', 'Клиент')}{sortHeader('VISITS', 'Кол-во визитов')}{sortHeader('AVERAGE_CHECK', 'Средний чек')}{sortHeader('SPENT', 'Потраченная сумма')}<th scope="col">Связь и обновление</th></tr></thead>
            <tbody>{data.page.items.map(row => <tr key={row.id}><td><div className="crm-client-table-identity">
                <CrmClientAvatar id={row.id} avatarUrl={row.avatarUrl} displayName={row.displayName} size={36} />
                <div><button type="button" className="crm-text-button" onClick={() => setSelected(row.id)}>{row.displayName}</button>
                <small>{Object.values(row.contacts).join(' · ') || 'Контакты не указаны'}</small></div></div></td>
                <td className="crm-client-number">{row.visitCount ?? 'Нет данных'}</td><td className="crm-client-number">{clientMoney(row, row.averageCheck)}</td><td className="crm-client-number">{clientMoney(row, row.totalSpent)}</td>
                <td><span>{row.clientId ? 'Связан с ClientBus' : 'Без связи'}</span>{row.statisticsUpdatedAt
                    ? <small>Статистика: {new Date(row.statisticsUpdatedAt).toLocaleString('ru-RU')}</small>
                    : row.lastObservedAt && <small>{new Date(row.lastObservedAt).toLocaleString('ru-RU')}</small>}</td></tr>)}</tbody>
        </table></div>}
        {data.page && data.page.items.length > 0 && data.page.items.every(row => row.averageCheck == null)
            && <p role="status">Средний чек ещё не загружен. Нажмите «Обновить статистику» — сначала обновятся клиенты без статистики и с самыми старыми данными.</p>}
        <CrmPageStatus data={data} empty="Клиенты по этим условиям не найдены." />
        {selected && <CrmClientCard key={selected} id={selected} project={project} canManage={canManage} close={() => setSelected(null)} changed={data.reload} />}</>
}
export function CrmClients({ project, canManage }: { project: api.CrmProject; canManage: boolean }) {
    const [sort, setSort] = useState<ClientSort>('NAME'), [direction, setDirection] = useState<'ASC' | 'DESC'>('ASC')
    const changeSort = (column: ClientSort) => {
        setSort(column)
        setDirection(sort === column ? direction === 'ASC' ? 'DESC' : 'ASC' : column === 'NAME' ? 'ASC' : 'DESC')
    }
    const [input, setInput] = useState(''), [unlinked, setUnlinked] = useState(false), query = useCrmSearch(input)
    return <><div className="crm-toolbar"><label className="crm-search">Найти клиента CRM<input type="search" maxLength={160} value={input} onChange={event => setInput(event.target.value)} /></label>
        <label className="crm-checkbox"><input type="checkbox" checked={unlinked} onChange={event => setUnlinked(event.target.checked)} />Без связи с ClientBus</label>
        </div>
        <ClientRows key={project.id + ':' + query + ':' + unlinked + ':' + sort + ':' + direction} project={project} query={query} unlinked={unlinked} canManage={canManage} sort={sort} direction={direction} changeSort={changeSort} /></>
}
