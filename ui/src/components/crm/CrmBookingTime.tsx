import { useId, useState, type ReactNode } from 'react'
import * as api from '../../api/crmApi'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../../api/events/uiScope'
import { useCrmPages } from './useCrmPages'
import { CrmPageStatus, crmLocal, crmPeriodValid, crmShift, crmToday, useCrmAction } from './CrmReadShared'

type Props = {
    project: api.CrmProject; master: string; offers: api.CrmOfferSelection[]; durationSeconds: number
    disabled?: boolean; children: (start: string) => ReactNode
    appointment?: { id: string; version: number }; initialDate?: string
}
export function CrmBookingTime(props: Props) {
    const [from, setFrom] = useState(() => props.initialDate ?? crmToday(props.project.zoneId))
    const to = /^\d{4}-\d{2}-\d{2}$/.test(from) && Number.isFinite(Date.parse(from)) ? crmShift(from, 30) : ''
    const valid = crmPeriodValid({ dateFrom: from, dateTo: to })
    return <><div className="crm-toolbar">
        <label>График с<input type="date" min="2000-01-01" max="2100-12-01" value={from} disabled={props.disabled} onChange={event => setFrom(event.target.value)} /></label>
        <button type="button" className="ui-button ui-button-secondary" disabled={!valid || props.disabled || from < '2000-02-01'} onClick={() => setFrom(crmShift(from, -31))}>← 31 день</button>
        <button type="button" className="ui-button ui-button-secondary" disabled={!valid || props.disabled || to > '2100-12-01'} onClick={() => setFrom(crmShift(from, 31))}>31 день →</button>
    </div>{valid ? <WorkingIntervals key={from} {...props} from={from} to={to} /> : <><p>Выберите начало периода графика.</p>{props.children('')}</>}</>
}
function WorkingIntervals({ from, to, ...props }: Props & { from: string; to: string }) {
    const { project, master, disabled, children } = props
    const id = useId()
    const data = useCrmPages('crm-booking-intervals:' + project.id + ':' + master + ':' + from, project.workspaceId,
        (signal, cursor) => api.getCrmWorkIntervals(project.id, { masterId: master, dateFrom: from, dateTo: to, cursor }, signal))
    const refresh = useCrmAction('crm-booking-schedule:' + project.id + ':' + master + ':' + from, project.workspaceId)
    const [selected, setSelected] = useState('')
    const intervals = data.page?.items.filter(row => row.masterId === master && ['WORK', 'WORKING'].includes(row.kind)
        && row.localStart.slice(0, 10) >= from && row.localStart.slice(0, 10) <= to) ?? []
    const interval = intervals.find(row => row.id === selected)
    return <><label htmlFor={id}>Рабочий интервал</label><select id={id} value={interval?.id ?? ''} disabled={disabled || !intervals.length} onChange={event => setSelected(event.target.value)}>
        <option value="">Выберите рабочий интервал</option>
        {intervals.map(row => <option value={row.id} key={row.id}>{crmLocal(row.localStart)} — {crmLocal(row.localEnd)}</option>)}
    </select>
        <button type="button" className="ui-button ui-button-secondary" disabled={disabled || refresh.busy} onClick={() => void refresh.run(signal => api.refreshCrmData(project.id, { dataset: 'WORK_INTERVALS', masterId: master, dateFrom: from, dateTo: to }, signal), data.reload)}>Обновить график</button>
        {refresh.error && <p role="alert" className="crm-error">{refresh.error}</p>}
        <CrmPageStatus data={data} empty="Рабочие интервалы этого периода не загружены или не найдены. Обновите график или выберите другой период." />
        {interval ? <AvailableStarts key={JSON.stringify([interval, props.offers, props.durationSeconds])} {...props} interval={interval} /> : children('')}
    </>
}
function AvailableStarts({ project, master, offers, durationSeconds, disabled, children, interval, appointment }: Props & { interval: api.CrmWorkInterval }) {
    const id = useId()
    const [value, setValue] = useState(''), [data, setData] = useState<api.CrmAvailability | null>(null)
    const [loading, setLoading] = useState(true), [error, setError] = useState<string | null>(null)
    const request = { contextVersion: project.contextVersion, zoneRevision: project.zoneRevision, masterId: master, offers, date: interval.localStart.slice(0, 10) }
    const resource = useUiResource('crm-booking-time:' + project.id + ':' + JSON.stringify([request, appointment]), true,
        event => isAccessEvent(event) && affectsWorkspace(event, project.workspaceId),
        signal => appointment ? api.getCrmMoveAvailability(appointment.id, { contextVersion: project.contextVersion, zoneRevision: project.zoneRevision, expectedVersion: appointment.version, date: request.date }, signal)
            : api.getCrmAvailability(project.id, request, signal), result => { setData(result); setError(null) },
        failure => setError(api.crmError(failure)), () => { setData(null); setValue('') }, setLoading, { visible: false, reconcile: false })
    // Compare wall times in the project's zone, without converting them to the browser's zone.
    const wall = (local: string) => Date.parse(local + 'Z')
    const matches = data && !error && data.projectId === project.id && data.masterId === master && data.zoneId === project.zoneId
        && data.contextVersion === project.contextVersion && data.zoneRevision === project.zoneRevision
        ? data.starts.filter(start => wall(start) >= wall(interval.localStart) && wall(start) + durationSeconds * 1000 <= wall(interval.localEnd)) : []
    const selected = !loading && matches.includes(value) ? value : ''
    return <><label htmlFor={id}>Свободное время</label><select id={id} value={selected} disabled={disabled || loading || !matches.length} onChange={event => setValue(event.target.value)}>
        <option value="">{loading ? 'Загрузка свободного времени…' : 'Выберите время'}</option>
        {matches.map(start => <option key={start} value={start}>{crmLocal(start)}</option>)}
    </select>
        {loading && <p role="status">Загрузка свободного времени…</p>}
        {error && <p role="alert" className="crm-error">{error} <button type="button" onClick={resource.reload}>Повторить загрузку времени</button></p>}
        {!loading && !error && !matches.length && <p>В этом рабочем интервале нет доступного времени для выбранных услуг.</p>}
        {children(selected)}
    </>
}
