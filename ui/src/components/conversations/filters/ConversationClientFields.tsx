import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { getClients } from '../../../api/clientApi'
import { getWorkspaceClientAccounts } from '../../../api/clientAccountApi'
import type { ClientListItemDto } from '../../../api/types/client'
import type { ClientAccountDto } from '../../../api/types/clientAccount'
import type { ConversationFilterDraft } from './conversationFilterModel'

export default function ConversationClientFields({ draft, setDraft, workspaceId }: {
    draft: ConversationFilterDraft; setDraft: Dispatch<SetStateAction<ConversationFilterDraft>>; workspaceId: string
}) {
    const [clients, setClients] = useState<ClientListItemDto[]>([])
    const [accounts, setAccounts] = useState<ClientAccountDto[]>([])
    const [clientQuery, setClientQuery] = useState('')
    const [accountQuery, setAccountQuery] = useState('')
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [version, setVersion] = useState(0)
    const [loadedScope, setLoadedScope] = useState<string | null>(null)
    const withoutClient = draft.clientLink === 'WITHOUT_CLIENT'
    const channelKey = JSON.stringify([...draft.channelAccountIds].sort())
    const scope = `${workspaceId}:${channelKey}:${withoutClient}`
    useEffect(() => {
        let active = true
        setLoading(true)
        setError(null)
        void (async () => {
            try {
                const [available, workspaceAccounts] = await Promise.all([
                    withoutClient ? Promise.resolve([]) : getClients(),
                    getWorkspaceClientAccounts(workspaceId, JSON.parse(channelKey) as string[]),
                ])
                if (!active) return
                setClients(available)
                setAccounts(workspaceAccounts)
                setLoadedScope(scope)
            } catch (err) {
                if (active) setError(err instanceof Error ? err.message : 'Не удалось загрузить клиентов и аккаунты.')
            } finally {
                if (active) setLoading(false)
            }
        })()
        return () => { active = false }
    }, [version, workspaceId, channelKey, withoutClient, scope])
    const clientIds = withoutClient ? [] : draft.clientIds ?? []
    const toggle = (field: 'clientIds' | 'clientAccountIds', id: string) => setDraft(current => {
        const ids = current[field] ?? []
        return { ...current, [field]: ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id],
            ...(field === 'clientIds' ? { clientAccountIds: [] } : {}) }
    })
    const clientName = (client: ClientListItemDto) => `${client.lastName} ${client.firstName}`.trim() || client.id
    const matches = (values: (string | null | undefined)[], query: string) =>
        values.some(value => value?.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    const eligibleAccounts = (loadedScope === scope ? accounts : []).filter(account =>
        (draft.clientLink !== 'WITH_CLIENT' || !!account.clientId)
        && (!withoutClient || !account.clientId))
    const eligibleClientIds = new Set(eligibleAccounts.map(account => account.clientId).filter(Boolean))
    const visibleClients = (loadedScope === scope ? clients : []).filter(client =>
        (draft.clientLink !== 'WITH_CLIENT' && !draft.channelAccountIds.length || eligibleClientIds.has(client.id))
        && matches([clientName(client), ...(client.phoneList ?? [])], clientQuery))
    const visibleAccounts = eligibleAccounts.filter(account =>
        (!clientIds.length || (account.clientId && clientIds.includes(account.clientId)))
        && matches([account.displayName, account.username, account.phone, account.externalId, account.channelType], accountQuery))
    return <div className="conversation-client-fields">
        {loading && <small role="status">Загрузка клиентов и аккаунтов…</small>}
        {error && <div className="ui-form-error" role="alert">{error}
            <button type="button" className="ui-button ui-button-secondary" onClick={() => setVersion(value => value + 1)}>Повторить загрузку клиентов</button>
        </div>}
        {!withoutClient && <fieldset>
            <legend>Клиенты</legend>
            <input className="ui-input" aria-label="Поиск клиентов" placeholder="Имя или телефон клиента"
                value={clientQuery} onChange={event => setClientQuery(event.target.value)} />
            <div className="conversation-filter-options">
                {visibleClients.map(client => <label key={client.id}>
                    <input type="checkbox" checked={clientIds.includes(client.id)} onChange={() => toggle('clientIds', client.id)} />
                    <span>{clientName(client)}</span>
                </label>)}
                {clientIds.filter(id => !clients.some(client => client.id === id)).map(id => <label key={id}>
                    <input type="checkbox" checked onChange={() => toggle('clientIds', id)} /><span>Недоступный клиент ({id})</span>
                </label>)}
                {!loading && !visibleClients.length && <small>Клиенты не найдены.</small>}
            </div>
            <small>Выбрано: {clientIds.length}. Пустой выбор — все клиенты.</small>
            {!!clientIds.length && <button type="button" className="ui-button ui-button-secondary" onClick={() => setDraft(current => ({ ...current, clientIds: [], clientAccountIds: [] }))}>Очистить клиентов</button>}
        </fieldset>}
        <fieldset>
            <legend>Аккаунты клиентов</legend>
            <input className="ui-input" aria-label="Поиск аккаунтов клиентов" placeholder="Имя, username, телефон или ID"
                value={accountQuery} onChange={event => setAccountQuery(event.target.value)} />
            <div className="conversation-filter-options">
                {visibleAccounts.map(account => <label key={account.id}>
                    <input type="checkbox" checked={draft.clientAccountIds.includes(account.id)} onChange={() => toggle('clientAccountIds', account.id)} />
                    <span>{account.displayName || account.username || account.phone || account.externalId || account.id} · {account.channelType}
                        {account.clientId ? ` · ${clients.find(client => client.id === account.clientId)
                            ? clientName(clients.find(client => client.id === account.clientId)!) : 'Клиент'}` : ' · Без клиента'}</span>
                </label>)}
                {!loading && !visibleAccounts.length && <small>Аккаунты не найдены.</small>}
            </div>
            <small>Выбрано: {draft.clientAccountIds.length}. {withoutClient ? 'Только аккаунты без клиента.' : 'Клиенты и аккаунты применяются совместно.'}</small>
        </fieldset>
    </div>
}
