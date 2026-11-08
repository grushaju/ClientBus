import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import * as api from '../../api/crmApi'
import { useCrmPages } from './useCrmPages'
import { crmPeriodValid, useCrmRead, useCrmSearch } from './CrmReadShared'

type Entry = api.CrmMaster | api.CrmClient | api.CrmAppointmentClient
type Kind = 'master' | 'client'

function SelectedName({ project, kind, value, fallback, period, legacyClientId }: { project: api.CrmProject; kind: Kind; value: string; fallback: string; period?: api.CrmPeriod; legacyClientId?: string }) {
    const data = useCrmRead<Entry | undefined>('crm-filter-name:' + project.id + ':' + kind + ':' + value + ':' + JSON.stringify(period) + ':' + legacyClientId, project.workspaceId,
        signal => kind === 'master' ? api.getCrmMaster(value, signal) : period && !legacyClientId
            ? crmPeriodValid(period) ? api.getCrmAppointmentClients(project.id, { ...period, externalClientId: value }, signal).then(page => page.items[0]) : Promise.resolve(undefined)
            : api.getCrmClient(legacyClientId || value, signal))
    return <>{data.data?.displayName ?? fallback}</>
}

export function CrmDirectoryPicker({ project, kind, value, change, all = true, period, legacyClientId, disabled = false }: { project: api.CrmProject; kind: Kind; value: string; change: (id: string) => void; all?: boolean; period?: api.CrmPeriod; legacyClientId?: string; disabled?: boolean }) {
    const [open, setOpen] = useState(false), [selected, setSelected] = useState<Entry | null>(null)
    const root = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null), id = useId()
    const label = kind === 'master' ? 'Мастер' : 'Клиент CRM'
    const selection = value || legacyClientId || ''
    const empty = all ? kind === 'master' ? 'Все мастера' : 'Все клиенты' : 'Выберите мастера'
    const close = () => { setOpen(false); trigger.current?.focus() }
    useEffect(() => {
        if (!open || typeof document === 'undefined') return
        const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false) }
        document.addEventListener('pointerdown', outside)
        return () => document.removeEventListener('pointerdown', outside)
    }, [open])
    const choose = (row: Entry | null) => { setSelected(row); change(row?.id ?? ''); close() }
    return <div className="crm-directory-picker" ref={root}
        onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false) }}
        onKeyDown={event => { if (event.key === 'Escape' && open) { event.preventDefault(); event.stopPropagation(); close() } }}>
        <span id={id + '-label'} className="crm-directory-label">{label}</span>
        <button type="button" className="crm-directory-trigger" ref={trigger} disabled={disabled} aria-labelledby={id + '-label ' + id + '-value'}
            aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? id + '-popup' : undefined}
            onClick={() => setOpen(!open)} onKeyDown={event => { if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true) } }}>
            <span id={id + '-value'}>{selection ? <SelectedName key={selection + ':' + JSON.stringify(period)} project={project} kind={kind} value={selection} period={period} legacyClientId={legacyClientId} fallback={selected?.id === value ? selected.displayName : 'Выбранный ' + (kind === 'master' ? 'мастер' : 'клиент')} /> : empty}</span><span aria-hidden="true">▾</span>
        </button>
        {open && <DirectoryPopup id={id + '-popup'} project={project} kind={kind} value={selection} all={all} choose={choose} period={period} />}
    </div>
}

function DirectoryPopup({ id, project, kind, value, all, choose, period }: { id: string; project: api.CrmProject; kind: Kind; value: string; all: boolean; choose: (row: Entry | null) => void; period?: api.CrmPeriod }) {
    const [input, setInput] = useState(''), query = useCrmSearch(input)
    const root = useRef<HTMLDivElement>(null), search = useRef<HTMLInputElement>(null), focused = useRef<HTMLElement | null>(null)
    useEffect(() => {
        search.current?.focus()
        const popup = root.current
        if (!popup || typeof MutationObserver === 'undefined') return
        // A background page replacement can remove the focused option without firing blur.
        const observer = new MutationObserver(() => {
            const previous = focused.current, document = popup.ownerDocument
            if (popup.isConnected && previous && !previous.isConnected && document.activeElement === document.body) search.current?.focus()
        })
        observer.observe(popup, { childList: true, subtree: true })
        return () => observer.disconnect()
    }, [])
    const keys = (event: KeyboardEvent) => {
        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
        if (event.target === search.current && event.key !== 'ArrowDown') return
        const options = Array.from(root.current?.querySelectorAll<HTMLButtonElement>('[role="option"]') ?? [])
        if (!options.length) return
        event.preventDefault()
        const index = options.indexOf(event.target as HTMLButtonElement)
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
            : event.key === 'ArrowDown' ? (index + 1) % options.length : (index - 1 + options.length) % options.length
        options[next].focus()
    }
    return <div id={id} role="dialog" aria-label={kind === 'master' ? 'Выбор мастера' : 'Выбор клиента CRM'} className="crm-directory-popup" ref={root} onKeyDown={keys}
        onFocusCapture={event => { focused.current = event.target as HTMLElement }}>
        <label>{kind === 'master' ? 'Поиск мастера' : 'Поиск клиента CRM'}<input ref={search} type="search" maxLength={160} placeholder={kind === 'master' ? 'Имя мастера' : 'Имя или телефон'}
            value={input} onChange={event => setInput(event.target.value)} /></label>
        <DirectoryOptions key={project.id + ':' + query + ':' + JSON.stringify(period)} project={project} kind={kind} value={value} all={all} query={query} choose={choose} period={period} />
    </div>
}

function DirectoryOptions({ project, kind, value, all, query, choose, period }: { project: api.CrmProject; kind: Kind; value: string; all: boolean; query: string; choose: (row: Entry | null) => void; period?: api.CrmPeriod }) {
    const data = useCrmPages<Entry>('crm-filter:' + project.id + ':' + kind + ':' + query + ':' + JSON.stringify(period), project.workspaceId,
        (signal, cursor) => kind === 'master' ? api.getCrmMasters(project.id, signal, cursor, { q: query, includeNotObserved: false }) : period
            ? api.getCrmAppointmentClients(project.id, { ...period, q: query, cursor }, signal) : api.getCrmClients(project.id, { q: query, cursor }, signal))
    return <>
        <div role="listbox" aria-label={kind === 'master' ? 'Мастера' : 'Клиенты CRM'} className="crm-directory-options">
            {all && <button type="button" role="option" tabIndex={-1} aria-selected={!value} onClick={() => choose(null)}>{kind === 'master' ? 'Все мастера' : 'Все клиенты'}</button>}
            {data.page?.items.map(row => <button type="button" role="option" tabIndex={-1} key={row.id} aria-selected={row.id === value} onClick={() => choose(row)}>{row.displayName}</button>)}
        </div>
        <div className="crm-directory-status">
            {data.error && <p role="alert">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
            {data.loading && !data.page && <p role="status">Загрузка списка…</p>}
            {data.page && !data.loading && !data.error && !data.page.items.length && <p>{query ? 'По запросу ничего не найдено.' : period && kind === 'client' ? 'В сохранённых записях этого периода клиенты с подтверждённым ID не найдены.' : data.page.dataState.freshness === 'NEVER_LOADED'
                ? kind === 'client' ? 'Список клиентов ещё не загружен. Обновите клиентов во вкладке «Клиенты» раздела CRM.' : 'Список мастеров ещё не загружен. Обновите каталог в разделе CRM.'
                : kind === 'client' ? 'В проекте нет сохранённых клиентов CRM.' : 'В проекте нет сохранённых мастеров.'}</p>}
            {data.page?.hasMore && <button type="button" className="ui-button ui-button-secondary" aria-disabled={data.more || data.loading}
                onClick={() => { if (!data.more && !data.loading) void data.loadMore() }}>{data.more ? 'Загрузка…' : 'Показать ещё'}</button>}
        </div>
    </>
}
