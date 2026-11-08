import { useState } from 'react'
import * as api from '../../api/crmApi'
import { useCrmPages } from './useCrmPages'
import { CrmDirectoryPicker } from './CrmDirectoryPicker'
import { CrmPageStatus, CrmPeriodFields, CrmStatus, crmLocal, crmPeriodValid, crmShift, crmToday, useCrmAction } from './CrmReadShared'

function ScheduleRows({ project, masterId, period }: { project: api.CrmProject; masterId: string; period: api.CrmPeriod }) {
    const data = useCrmPages('crm-schedule:' + project.id + ':' + masterId + ':' + JSON.stringify(period), project.workspaceId,
        (signal, cursor) => api.getCrmWorkIntervals(project.id, { ...period, masterId, cursor }, signal))
    const action = useCrmAction('crm-schedule:' + project.id + ':' + masterId, project.workspaceId)
    return <><p>Часовой пояс: {project.zoneId ?? 'не указан'}. Рабочие интервалы не подтверждают свободное время для записи.</p>
        <button type="button" className="ui-button ui-button-secondary" disabled={action.busy || !project.zoneId || project.archived || !project.enabled || ['DISABLED', 'REAUTH_REQUIRED'].includes(project.connectionState)}
            onClick={() => void action.run(signal => api.refreshCrmData(project.id, { dataset: 'WORK_INTERVALS', masterId, ...period }, signal), data.reload)}>Обновить график</button>
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}<CrmStatus state={data.page?.dataState} archived={project.archived} />
        <ul className="crm-appointments">{data.page?.items.map(row => <li className="crm-interval" key={row.id}><strong>{crmLocal(row.localStart)} — {crmLocal(row.localEnd)}</strong><span>{row.kind === 'WORK' || row.kind === 'WORKING' ? 'Рабочий интервал' : row.kind}</span></li>)}</ul>
        <CrmPageStatus data={data} empty="Сохранённые рабочие интервалы не найдены. Это не подтверждает отсутствие смен." /></>
}
export function CrmSchedule({ project, initialMaster = '' }: { project: api.CrmProject; initialMaster?: string }) {
    const [masterId, setMasterId] = useState(initialMaster)
    const [period, setPeriod] = useState(() => { const today = crmToday(project.zoneId); return { dateFrom: today, dateTo: crmShift(today, 6) } })
    return <><CrmDirectoryPicker project={project} kind="master" value={masterId} change={setMasterId} all={false} /><CrmPeriodFields value={period} change={setPeriod} />
        {!project.zoneId && <p className="crm-notice">Укажите часовой пояс проекта в его настройках. До этого время показано как исходное местное.</p>}
        {masterId && crmPeriodValid(period) ? <ScheduleRows key={project.id + ':' + masterId + ':' + JSON.stringify(period)} project={project} masterId={masterId} period={period} /> : !masterId && <p>Выберите мастера для просмотра графика.</p>}
    </>
}
