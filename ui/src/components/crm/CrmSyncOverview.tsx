import { useState } from 'react'
import * as api from '../../api/crmApi'
import { useCrmRead } from './CrmReadShared'

const names: Record<string, string> = { CATALOG: 'Мастера и услуги', CLIENTS: 'Клиенты', CLIENT_STATISTICS: 'Статистика клиентов', APPOINTMENTS: 'Записи', CLIENT_HISTORY: 'История клиента', SCHEDULES: 'Графики мастеров', HISTORY: 'Архив записей', FINANCE: 'Финансы' }
const date = (value: string | null) => value ? new Date(value).toLocaleString('ru-RU') : 'Ещё не обновлено'

function Overview({ project }: { project: api.CrmProject }) {
    const resource = useCrmRead('crm-sync-overview:' + project.id, project.workspaceId, signal => api.getCrmSyncOverview(project.id, signal))
    return <div>
        <button type="button" className="ui-button ui-button-secondary" disabled={resource.loading} onClick={resource.reload}>Обновить состояние синхронизации</button>
        {resource.error && <p role="alert">{resource.error}</p>}
        {resource.loading && !resource.data && <p role="status">Загрузка состояния…</p>}
        {resource.data && <>
            <p>Проект: {project.displayName}.</p>
            {(!resource.data.enabled || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)) && <p>Автоматическая синхронизация приостановлена.</p>}
            {resource.data.stored && <p>Загружено в проект: клиентов — {resource.data.clients.total}, мастеров — {resource.data.stored.masters}, услуг — {resource.data.stored.services}, записей — {resource.data.stored.appointments}, интервалов графика — {resource.data.stored.intervals}.</p>}
            <p>Свежая статистика: {resource.data.clients.fresh} из {resource.data.clients.total} клиентов. Без статистики: {resource.data.clients.missing}.</p>
            {resource.data.clients.oldestat && <p>Самая старая статистика: {date(resource.data.clients.oldestat)}.</p>}
            <div className="crm-table-scroll"><table className="crm-clients-table">
                <thead><tr><th>Данные / период</th><th>Всего задач</th><th>Обновлены, следующий запуск позже</th><th>Ожидают</th><th>Выполняются</th><th>Ошибки</th><th>Наступил срок запуска</th><th>Последняя попытка</th><th>Ближайший запуск ожидающих</th><th>Самая старая успешная загрузка среди всех задач</th></tr></thead>
                <tbody>{resource.data.datasets.map(row => <tr key={row.dataset}>
                    <td>{names[row.dataset] ?? row.dataset}{row.datefrom && <small>{row.datefrom} — {row.dateto}</small>}</td><td>{row.scopes}</td>
                    <td>{row.completed}</td><td>{row.pending}</td><td>{row.running}</td>
                    <td>{row.failed}{row.failed > 0 && row.errorcode && <small>{row.errorcode}</small>}</td>
                    <td>{row.due ?? 0}</td><td>{row.lastattemptat ? date(row.lastattemptat) : 'Не запускались'}</td>
                    <td>{row.nextat ? date(row.nextat) : 'Нет ожидающих задач'}</td>
                    <td>{date(row.lastsuccessat)}</td>
                </tr>)}</tbody>
            </table></div>
            <p>Задачи — это периоды и этапы загрузки, а не количество записей в CRM. Задачи с наступившим сроком запуска входят в ожидающие или ошибки. Последняя колонка показывает самую старую из последних успешных загрузок; это не время завершения общего прохода. Количество загруженных данных не означает, что загрузка уже завершена.</p>
            {!resource.data.datasets.length && <p>Запланированных задач пока нет.</p>}
        </>}
    </div>
}
export default function CrmSyncOverview({ project }: { project: api.CrmProject }) {
    const [open, setOpen] = useState(false)
    return <details onToggle={event => setOpen(event.currentTarget.open)}><summary>Синхронизация CRM</summary>{open && <Overview project={project} />}</details>
}
