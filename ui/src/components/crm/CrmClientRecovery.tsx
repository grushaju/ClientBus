import { useEffect } from 'react'
import * as api from '../../api/crmApi'
import { useCrmAction, useCrmRead } from './CrmReadShared'

/** Local completion remains available when the project cannot accept external writes. */
export function CrmClientRecovery({ project, clientId, done }: {
    project: api.CrmProject; clientId: string; done: () => void
}) {
    const latest = useCrmRead('crm-client-recovery:' + project.id + ':' + clientId, project.workspaceId,
        signal => api.getLatestClientProvision(project.id, clientId, signal))
    const action = useCrmAction('crm-client-recover:' + project.id + ':' + clientId, project.workspaceId)
    const operation = latest.data
    const running = operation?.status === 'REQUESTED' || operation?.status === 'IN_FLIGHT'
    useEffect(() => {
        if (!running) return
        const timer = window.setInterval(latest.reload, 3000)
        return () => window.clearInterval(timer)
    }, [running, latest.reload])
    if (!latest.error && !operation) return null
    if (operation && !['REQUESTED', 'IN_FLIGHT', 'CREATED', 'UNKNOWN'].includes(operation.status)) return null
    return <section className="crm-notice" aria-label="Восстановление связи CRM">
        <strong>{project.provider} · {project.displayName}{project.archived ? ' · архив' : ''}</strong>
        {latest.error && <p role="alert">{latest.error} <button type="button" onClick={latest.reload}>Повторить проверку</button></p>}
        {running && <p role="status">Проверяем завершение принятой операции CRM.</p>}
        {operation?.status === 'UNKNOWN' && <p>Результат создания неизвестен. Для поиска клиента в CRM восстановите доступ к проекту. Повторное создание заблокировано.</p>}
        {operation?.status === 'CREATED' && <>
            <p>Клиент уже создан в CRM. Можно восстановить связь с ClientBus без обращения к CRM, даже если проект отключён или находится в архиве.</p>
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy || !!latest.error}
                onClick={() => void action.run(signal => api.recoverClientProvision(operation.id, signal), result => {
                    latest.reload()
                    if (result.status === 'CONFIRMED') done()
                })}>Восстановить связь</button>
        </>}
        {action.error && <p role="alert" className="crm-error">{action.error}</p>}
    </section>
}

export function needsCrmRecoveryView(project: api.CrmProject) {
    return project.archived || !project.enabled || project.connectionState !== 'CONNECTED'
}
