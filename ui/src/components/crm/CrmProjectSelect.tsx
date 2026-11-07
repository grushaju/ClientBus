import { useEffect, useState } from 'react'
import * as api from '../../api/crmApi'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../../api/events/uiScope'

interface Props {
    connection: api.CrmConnection; workspaceId: string; bindings: api.CrmProject[]
    value: string; disabled: boolean; onChange: (value: string) => void
}
export default function CrmProjectSelect({ connection, workspaceId, bindings, value, disabled, onChange }: Props) {
    const [rows, setRows] = useState<api.CrmProjectCheck[]>([])
    const [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(true)
    // External discovery runs on opening, explicit refresh or access changes, never on recovery polling/health events.
    const resource = useUiResource('crm-project-options:' + workspaceId + ':' + connection.id, true,
        event => affectsWorkspace(event, workspaceId) && isAccessEvent(event),
        signal => api.getAvailableCrmProjects(connection.id, workspaceId, signal),
        data => { setRows(data); setError(null) }, failure => { setError(api.crmError(failure)); onChange('') },
        () => { setRows([]); onChange('') }, setLoading, { reconcile: false })
    const available = rows.filter(row => !bindings.some(binding => binding.provider === connection.provider && binding.externalId === row.externalId
        && !(binding.archived && binding.workspaceId === workspaceId)))
    const selected = available.find(row => row.externalId === value)
    useEffect(() => { if (value && !loading && !selected) onChange('') }, [value, loading, selected, onChange])
    return <div className="crm-form">
        {error && <p role="alert" className="crm-error">{error}</p>}
        {loading && <p role="status">Загружаем проекты DIKIDI…</p>}
        <label>Проект DIKIDI<select aria-label="Проект DIKIDI" required value={value} disabled={disabled || loading || !!error || !available.length}
            onChange={event => onChange(event.target.value)}>
            <option value="">Выберите проект</option>
            {available.map(row => <option key={row.externalId} value={row.externalId}>{row.displayName}{bindings.some(binding => binding.archived && binding.workspaceId === workspaceId
                && binding.provider === connection.provider && binding.externalId === row.externalId) ? ' · восстановить' : ''}</option>)}
        </select></label>
        {!loading && !error && !available.length && <p>Нет свободных проектов для подключения. Уже привязанные проекты исключены.</p>}
        <button type="button" className="ui-button ui-button-secondary" disabled={disabled || loading} onClick={resource.reload}>{error ? 'Повторить загрузку проектов' : 'Обновить список проектов'}</button>
    </div>
}
