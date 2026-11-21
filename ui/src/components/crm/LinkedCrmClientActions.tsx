import { useState } from 'react'
import * as api from '../../api/crmApi'
import { useCrmAction } from './CrmReadShared'
import { ApiError } from '../../api/apiClient'

/** Unlinking changes the local association; the external client remains in CRM. */
export function LinkedCrmClientActions({ link, canManage, open, changed }: {
    link: api.CrmClientLink; canManage: boolean; open: () => void; changed: (message?: string) => void
}) {
    const [confirming, setConfirming] = useState<api.CrmClient | null>(null)
    const action = useCrmAction('unlink-client:' + link.client.id, link.project.workspaceId)
    return <div className="crm-linked-actions">
        <div className="crm-actions">
            <button type="button" className="ui-button ui-button-sm ui-button-secondary" onClick={open}>Карточка</button>
            {canManage && !link.project.archived && !confirming && <button type="button" className="ui-button ui-button-sm ui-button-secondary"
                disabled={action.busy} onClick={() => { action.clearError(); setConfirming(link.client) }}>Отвязать</button>}
        </div>
        {confirming && canManage && !link.project.archived && <div className="crm-unlink-confirm" role="group" aria-label="Подтверждение отвязки CRM">
            <p>Отвязать «{confirming.displayName}» от клиента ClientBus? Карточка и данные в {link.project.provider} сохранятся.</p>
            <div className="crm-actions">
                <button type="button" className="ui-button ui-button-sm ui-button-danger" disabled={action.busy} onClick={() => void action.run(async signal => {
                    for (let attempt = 0; attempt < 2; attempt++) {
                        const fresh = await api.getCrmClient(link.client.id, signal)
                        if (fresh.clientId !== confirming.clientId || fresh.mappingUpdatedAt !== confirming.mappingUpdatedAt
                            || fresh.mappingUpdatedBy !== confirming.mappingUpdatedBy || fresh.mappingReason !== confirming.mappingReason || fresh.mappingPreviousId !== confirming.mappingPreviousId) {
                            setConfirming(null); changed()
                            throw new Error('Связь уже изменена. Проверьте актуальную карточку.')
                        }
                        try { await api.linkCrmClient(fresh, null, signal); return }
                        catch (error) {
                            if (attempt || !(error instanceof ApiError) || error.code !== 'CRM_VERSION_CONFLICT') throw error
                        }
                    }
                }, () => { setConfirming(null); changed() })}>Подтвердить отвязку</button>
                <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={() => setConfirming(null)}>Отмена</button>
            </div>
        </div>}
        {action.error && <p className="crm-error" role="alert">{action.error}</p>}
    </div>
}
