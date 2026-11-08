import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useUiResource } from '../../api/events/useUiResource'
import { useUiInvalidation } from '../../api/events/InvalidationContext'
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { affectsWorkspace, isAccessEvent } from '../../api/events/uiScope'
import * as api from '../../api/crmApi'

export function useCrmRead<T>(key: string, workspaceId: string | undefined, read: (signal: AbortSignal) => Promise<T>) {
    const [data, setData] = useState<T | null>(null), [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(true)
    const resource = useUiResource(key, true,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) || ['crm.invalidated', 'clients.invalidated'].includes(event.type)),
        read, value => { setData(value); setError(null) }, failure => setError(api.crmError(failure)), () => setData(null), setLoading)
    return { ...resource, data, error, loading }
}
export function useCrmAction(key: string, workspaceId?: string) {
    const lifetime = useUiLifetime(key), pending = useRef<AbortController>()
    const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
    const reset = () => { pending.current?.abort(); pending.current = undefined; lifetime.reset(); setBusy(false); setError(null) }
    useUiInvalidation('crm-command:' + key, true, event => isAccessEvent(event) && affectsWorkspace(event, workspaceId),
        async () => {}, reset, true, undefined, false)
    useEffect(() => { setBusy(false); setError(null); return () => { pending.current?.abort(); pending.current = undefined } }, [lifetime])
    async function run<T>(action: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void, fail?: () => void) {
        if (pending.current) return
        const controller = new AbortController(), current = lifetime.capture()
        pending.current = controller; setBusy(true); setError(null)
        try {
            const value = await lifetime.read(action, controller.signal)
            if (current() && !controller.signal.aborted) apply(value)
        } catch (failure) {
            if (current() && !controller.signal.aborted) { setError(api.crmError(failure)); fail?.() }
        } finally { if (pending.current === controller) { pending.current = undefined; if (current()) setBusy(false) } }
    }
    return { busy, error, run }
}
export function useCrmSearch(value: string) {
    const [query, setQuery] = useState(value)
    useEffect(() => { const timer = setTimeout(() => setQuery(value.trim()), 250); return () => clearTimeout(timer) }, [value])
    return query
}
export function CrmStatus({ state, archived = false }: { state?: api.CrmSyncState; archived?: boolean }) {
    if (!state) return <p className="crm-freshness">Полнота данных не подтверждена.</p>
    return <p className="crm-freshness" role="status">
        {state.lastSuccessAt ? 'Последнее успешное чтение: ' + new Date(state.lastSuccessAt).toLocaleString('ru-RU') : 'Данные ещё не загружены.'}
        {' · '}{state.completeness === 'COMPLETE' ? 'Полный результат этого запроса' : state.completeness === 'PARTIAL' ? 'Загрузка неполная' : 'Полнота не подтверждена'}
        {archived && ' · архив'}
        {!archived && state.freshness === 'STALE' && ' · сохранённые данные требуют обновления'}
        {!archived && ['QUEUED', 'RUNNING'].includes(state.status) && ' · обновление выполняется'}
        {!archived && state.status === 'FAILED' && ' · последняя попытка завершилась ошибкой'}
        {!archived && state.errorCode && <span className="crm-error-text"> · {state.errorCode}</span>}
    </p>
}
export function CrmPageStatus({ data, empty = 'Данные по этим условиям не найдены.' }: { data: { loading: boolean; error: string | null; page: { items: unknown[]; hasMore: boolean } | null; more: boolean; reload: () => void; loadMore: () => Promise<void> }; empty?: string }) {
    return <>
        {data.error && <p role="alert" className="crm-error">{data.error} <button type="button" onClick={data.reload}>Повторить</button></p>}
        {data.loading && !data.page && <p role="status">Загрузка…</p>}
        {data.page && !data.loading && !data.error && !data.page.items.length && <p>{empty} Проверьте состояние загрузки.</p>}
        {data.page?.hasMore && <button type="button" className="ui-button ui-button-secondary" disabled={data.more || data.loading} onClick={() => void data.loadMore()}>{data.more ? 'Загрузка…' : 'Показать ещё'}</button>}
    </>
}
export function CrmDialog({ title, close, children }: { title: string; close: () => void; children: ReactNode }) {
    const dialog = useRef<HTMLDialogElement>(null)
    useEffect(() => { if (typeof document === 'undefined') return; const previous = document.activeElement as HTMLElement | null; dialog.current?.showModal?.(); return () => { if (previous?.isConnected) previous.focus() } }, [])
    return <dialog className="crm-drawer" ref={dialog} aria-label={title} onCancel={close} onClick={event => { if (event.target === event.currentTarget) close() }}>
        <div className="crm-drawer-content"><div className="crm-heading"><h2>{title}</h2><button type="button" className="ui-button ui-button-secondary" autoFocus onClick={close}>Закрыть</button></div>{children}</div>
    </dialog>
}
export function crmMoney(value: unknown, currency?: unknown) {
    return value === null || value === undefined ? 'Неизвестно' : String(value).replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1') + (currency ? ' ' + String(currency) : ' (валюта неизвестна)')
}
export function crmLocal(value: string | null) { return value ? value.replace('T', ' ').slice(0, 16) : 'Время неизвестно' }
export function crmToday(zone?: string | null, now = new Date()) {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone || 'UTC', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
    return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)!.value).join('-')
}
export function crmShift(date: string, days: number) { return new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10) }
export function crmHistoryPeriod(zones: (string | null | undefined)[], direction: string): api.CrmPeriod {
    const now = new Date(), days = (zones.length ? zones : [undefined]).map(zone => crmToday(zone, now)).sort()
    // Include the current local day of every project within the inclusive 31-day API limit.
    if (direction === 'future') return { dateFrom: days[0], dateTo: crmShift(days[0], 30) }
    const last = days[days.length - 1]
    return { dateFrom: crmShift(last, -30), dateTo: last }
}
export function crmPeriodValid(period: api.CrmPeriod) {
    const from = Date.parse(period.dateFrom + 'T00:00:00Z'), to = Date.parse(period.dateTo + 'T00:00:00Z')
    return /^\d{4}-\d{2}-\d{2}$/.test(period.dateFrom) && /^\d{4}-\d{2}-\d{2}$/.test(period.dateTo) && Number.isFinite(from) && Number.isFinite(to)
        && new Date(from).toISOString().slice(0, 10) === period.dateFrom && new Date(to).toISOString().slice(0, 10) === period.dateTo
        && period.dateFrom >= '2000-01-01' && period.dateTo <= '2100-12-31' && to >= from && to - from < 31 * 86400000
}
export function CrmPeriodFields({ value, change }: { value: api.CrmPeriod; change: (period: api.CrmPeriod) => void }) {
    const valid = crmPeriodValid(value)
    const move = (direction: number) => { if (valid) { const days = Math.round((Date.parse(value.dateTo) - Date.parse(value.dateFrom)) / 86400000) + 1; change({ dateFrom: crmShift(value.dateFrom, days * direction), dateTo: crmShift(value.dateTo, days * direction) }) } }
    return <><div className="crm-toolbar">
        <button type="button" className="ui-button ui-button-secondary" disabled={!valid} onClick={() => move(-1)}>← Период</button>
        <label>С даты<input type="date" value={value.dateFrom} min="2000-01-01" max="2100-12-31" onChange={event => change({ ...value, dateFrom: event.target.value })} /></label>
        <label>По дату<input type="date" value={value.dateTo} min="2000-01-01" max="2100-12-31" onChange={event => change({ ...value, dateTo: event.target.value })} /></label>
        <button type="button" className="ui-button ui-button-secondary" disabled={!valid} onClick={() => move(1)}>Период →</button>
        <button type="button" className="ui-button ui-button-secondary" disabled={!value.dateFrom || !valid} onClick={() => change({ ...value, dateTo: value.dateFrom })}>День</button>
        <button type="button" className="ui-button ui-button-secondary" disabled={!valid} onClick={() => change({ ...value, dateTo: crmShift(value.dateFrom, 6) })}>Неделя</button>
    </div>{!valid && <p role="alert" className="crm-error">Выберите корректный период от 1 до 31 дня.</p>}</>
}
