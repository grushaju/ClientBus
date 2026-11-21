import { useState } from 'react'
import * as api from '../../api/crmApi'
import { getClient } from '../../api/clientApi'
import type { ClientDto } from '../../api/types/client'
import ClientAvatar from '../clients/ClientAvatar'
import { useCrmAction } from './CrmReadShared'

export function CrmClientAvatarTransfer({ row, action, disabled, done }: {
    row: api.CrmClient; action: ReturnType<typeof useCrmAction>; disabled: boolean; done: () => void
}) {
    const [source, setSource] = useState<ClientDto | null>(null)
    return <>
        {!source && <div className="crm-actions"><button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={disabled || action.busy}
            onClick={() => void action.run(async signal => {
                const client = await getClient(row.clientId!, signal)
                if (!client.avatarUrl) throw new Error('У клиента ClientBus нет аватара. Сначала добавьте фото в его карточку.')
                return client
            }, setSource)}>Аватар из ClientBus</button></div>}
        {source && <div className="crm-unlink-confirm" role="group" aria-label="Перенос аватара в CRM">
            <ClientAvatar clientId={source.id} avatarUrl={source.avatarUrl} firstName={source.firstName} lastName={source.lastName} size={64} />
            <p>Перенести это фото из ClientBus в DIKIDI? Текущий аватар CRM будет заменён. Остальные данные сохранятся.</p>
            <div className="crm-actions"><button type="button" className="ui-button ui-button-sm ui-button-primary" disabled={disabled || action.busy}
                onClick={() => void action.run(async signal => {
                    const fresh = await api.getCrmClient(row.id, signal)
                    return api.transferCrmClientAvatar(fresh, source, signal)
                }, () => { setSource(null); done() })}>Перенести в DIKIDI</button>
                <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={action.busy} onClick={() => setSource(null)}>Отмена</button></div>
        </div>}
    </>
}
