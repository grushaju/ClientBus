import { useEffect, useRef, useState } from 'react'
import {
    assignClientAccount,
    createClientFromConversation,
    getClient,
    getClientAccountsByClient,
    getClientAvatarContent,
    searchClients,
    unassignClientAccount,
} from '../../api/clientApi'
import type { ClientDto, ClientListItemDto } from '../../api/types/client'
import type { ChannelSummary, ClientAccountSummary, ConversationDto } from '../../api/types/conversation'
import type { ClientAccountDto } from '../../api/types/clientAccount'
import type { UiEventEnvelope } from '../../api/types/uiEvent'
import { useUiResource } from '../../api/events/useUiResource'
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { affectsConversation, isAccessEvent } from '../../api/events/uiScope'
import PlatformIcon from '../common/platform/PlatformIcon'
import PlatformName from '../common/platform/PlatformName'
import ClientAccountPicker from '../clients/ClientAccountPicker'
import { ChannelAccountAvatar } from '../channels/ChannelAccountAvatar'
import ClientAccountAvatar from '../clients/ClientAccountAvatar'

interface Props {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    channel: ChannelSummary | null
    accountError?: string | null
    channelError?: string | null
    refreshingDetails?: boolean
    visible?: boolean
    onRetryDetails: () => void
    onChanged: (account: ClientAccountSummary) => void
}

const errorMessage = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback
const username = (value: string) => `@${value.replace(/^@+/, '')}`
const accountName = (account: ClientAccountSummary) => account.displayName || account.username || account.phone || account.externalId

function AccountCard({ account }: { account: ClientAccountSummary }) {
    return (
        <div className="conversation-client-account-card">
            <ClientAccountAvatar accountId={account.id} avatarUrl={account.avatarUrl} displayName={accountName(account)} size={36} />
            <PlatformIcon type={account.channelType} size={18} />
            <div className="conversation-client-account-info">
                <strong>{accountName(account)}</strong>
                {account.username && <span>{username(account.username)}</span>}
                {account.phone && <span>{account.phone}</span>}
            </div>
        </div>
    )
}

function ConversationClientDetails({
    conversation, clientAccount, channel, accountError, channelError,
    refreshingDetails = false, visible = true, onRetryDetails, onChanged,
}: Props) {
    const alive = useRef(true)
    const actionPending = useRef(false)
    const accountsRequest = useRef(0)
    const [client, setClient] = useState<ClientDto | null>(null)
    const [clientAccounts, setClientAccounts] = useState<ClientAccountSummary[]>([])
    const [clientAvatarSrc, setClientAvatarSrc] = useState<string | null>(null)
    const [loadingClient, setLoadingClient] = useState(!!clientAccount?.clientId)
    const [loadingAccounts, setLoadingAccounts] = useState(!!clientAccount?.clientId)
    const [clientError, setClientError] = useState<string | null>(null)
    const [accountsError, setAccountsError] = useState<string | null>(null)
    const [actionLoading, setActionLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [form, setForm] = useState<'create' | 'link' | null>(null)
    const [firstName, setFirstName] = useState('')
    const [lastName, setLastName] = useState('')
    const [phone, setPhone] = useState('')
    const [showOtherAccounts, setShowOtherAccounts] = useState(false)
    const [showAccountPicker, setShowAccountPicker] = useState(false)
    const [clientQuery, setClientQuery] = useState('')
    const [clients, setClients] = useState<ClientListItemDto[]>([])
    const [loadingClients, setLoadingClients] = useState(false)
    const [clientsError, setClientsError] = useState<string | null>(null)
    const [clientsReload, setClientsReload] = useState(0)
    const [selectedClientId, setSelectedClientId] = useState('')
    const clientId = clientAccount?.clientId
    const avatarLifetime = useUiLifetime('client-avatar:' + (clientId ?? ''))

    useEffect(() => {
        alive.current = true
        return () => { alive.current = false }
    }, [])

    const matchesClient = (event: UiEventEnvelope) => isAccessEvent(event)
        ? affectsConversation(event, conversation.id, conversation.workspaceId)
        : event.type === 'clients.invalidated' && (event.resource?.kind === 'client'
            ? event.resource.id === clientId : affectsConversation(event, conversation.id, conversation.workspaceId))
    // Profile and accounts reconcile independently, without closing the form.
    const profile = useUiResource('client-profile:' + (clientId ?? ''), !!clientId, matchesClient,
        signal => getClient(clientId!, signal),
        value => { setClient(value); setClientError(null) },
        err => setClientError(errorMessage(err, 'Не удалось загрузить клиента')),
        () => { setClient(null); setClientAvatarSrc(null) },
        value => { setLoadingClient(value); if (value) setClientError(null) }, visible)
    const accounts = useUiResource('client-accounts:' + (clientId ?? ''), !!clientId, matchesClient,
        async signal => {
            const request = accountsRequest.current
            return { request, items: await getClientAccountsByClient(clientId!, signal) }
        }, result => {
            if (result.request === accountsRequest.current) setClientAccounts(result.items)
            setAccountsError(null)
        }, err => setAccountsError(errorMessage(err, 'Не удалось загрузить аккаунты клиента')),
        () => { accountsRequest.current++; setClientAccounts([]) },
        value => { setLoadingAccounts(value); if (value) setAccountsError(null) }, visible)

    useEffect(() => {
        setClientAvatarSrc(null)
        if (!client?.avatarUrl) return
        let active = true
        const controller = new AbortController()
        let objectUrl: string | undefined
        void avatarLifetime.read(signal => getClientAvatarContent(client.id, signal), controller.signal).then(blob => {
            if (!active) return
            objectUrl = URL.createObjectURL(blob)
            setClientAvatarSrc(objectUrl)
        }).catch(() => { /* Fall back to the initial when the avatar is unavailable. */ })
        return () => {
            active = false
            controller.abort()
            if (objectUrl) URL.revokeObjectURL(objectUrl)
        }
    }, [client?.id, client?.avatarUrl, avatarLifetime])

    useEffect(() => {
        if (form !== 'link') return
        const controller = new AbortController()
        setLoadingClients(true)
        setClients([])
        setClientsError(null)
        const timer = window.setTimeout(async () => {
            try {
                const result = await searchClients(clientQuery.trim(), controller.signal)
                if (!controller.signal.aborted) setClients(result)
            } catch (err) {
                if (!controller.signal.aborted) setClientsError(errorMessage(err, 'Не удалось загрузить клиентов'))
            } finally {
                if (!controller.signal.aborted) setLoadingClients(false)
            }
        }, 250)
        return () => { window.clearTimeout(timer); controller.abort() }
    }, [form, clientQuery, clientsReload])

    async function mutate(operation: () => Promise<ClientAccountSummary>, fallback: string) {
        if (actionPending.current || !alive.current) return
        actionPending.current = true
        setActionLoading(true)
        setError(null)
        let updated: ClientAccountSummary
        try {
            updated = await operation()
        } catch (err) {
            if (alive.current) setError(errorMessage(err, fallback))
            return
        } finally {
            if (alive.current) {
                actionPending.current = false
                setActionLoading(false)
            }
        }
        // Notify the list even when navigation has already closed this panel.
        // The parent guards updates to the currently displayed conversation.
        onChanged(updated)
        if (!alive.current) return
        setForm(null)
        setShowAccountPicker(false)
    }

    function handleCreateClient() {
        if (!clientAccount || clientId || actionPending.current || refreshingDetails) return
        const name = firstName.trim()
        const surname = lastName.trim()
        if (!name && !surname) {
            setError('Укажите имя или фамилию клиента')
            return
        }
        void mutate(async () => {
            const created = await createClientFromConversation(conversation.id, {
                firstName: name, lastName: surname, phoneList: phone.trim() ? [phone.trim()] : [],
            })
            return { ...clientAccount, clientId: created.id }
        }, 'Не удалось создать клиента')
    }

    function handleLinkClient() {
        if (!clientAccount || clientId || !selectedClientId || loadingClients || clientsError || refreshingDetails) return
        void mutate(() => assignClientAccount(selectedClientId, clientAccount.id), 'Не удалось привязать аккаунт')
    }

    function handleAssignAnotherAccount(account: ClientAccountDto) {
        if (!clientId || !clientAccount || account.clientId !== null) return
        const isCurrent = accounts.capture()
        void mutate(async () => {
            const updated = await assignClientAccount(clientId, account.id)
            if (alive.current && isCurrent()) {
                accountsRequest.current += 1
                setClientAccounts(previous => [...previous.filter(item => item.id !== updated.id), updated])
                // Re-read the list if its earlier request failed or is still in flight.
                accounts.reload()
            }
            return clientAccount
        }, 'Не удалось привязать аккаунт')
    }

    const clientName = client ? [client.firstName, client.lastName].filter(Boolean).join(' ') || 'Без имени' : ''
    const otherAccounts = clientAccounts.filter(account => account.id !== clientAccount?.id)
    const disabled = actionLoading || refreshingDetails
    const retryDetails = <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={disabled} onClick={onRetryDetails}>Обновить данные</button>

    return (
        <aside className="conversation-client-panel">
            <div className="conversation-client-panel-header"><h3>Клиент</h3></div>
            <div className="conversation-client-panel-section" aria-busy={loadingClient}>
                {accountError && <div className="conversation-client-panel-error" role="alert">{accountError}{retryDetails}</div>}
                {!clientAccount ? (
                    !accountError && <div className="conversation-client-panel-state">Аккаунт недоступен{retryDetails}</div>
                ) : clientId ? (
                    <>
                        {loadingClient && !client && <div className="conversation-client-panel-state">Загрузка клиента…</div>}
                        {clientError && <div className="conversation-client-panel-error" role="alert">
                            {clientError}
                            <button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={loadingClient} onClick={profile.reload}>Повторить загрузку клиента</button>
                        </div>}
                        {client && <>
                            <div className="conversation-client-card">
                                <div className="conversation-client-avatar">
                                    {clientAvatarSrc ? <img src={clientAvatarSrc} alt="" /> : clientName.charAt(0).toUpperCase()}
                                </div>
                                <div className="conversation-client-card-info">
                                    <strong>{clientName}</strong>
                                    {!client.enabled && <span className="conversation-client-disabled">Неактивен</span>}
                                </div>
                            </div>
                            {client.phoneList.length > 0 && <div className="conversation-client-field">
                                <span>Телефон</span><div>{client.phoneList.map((value, index) => <div key={index}>{value}</div>)}</div>
                            </div>}
                        </>}
                    </>
                ) : (
                    <>
                        <div className="conversation-client-orphan"><strong>Клиент не создан</strong><span>Аккаунт пока не привязан к клиенту.</span></div>
                        {!form && <div className="conversation-client-actions">
                            <button type="button" className="ui-button ui-button-sm ui-button-primary ui-button-block" disabled={disabled} onClick={() => { setError(null); setForm('create') }}>Создать клиента</button>
                            <button type="button" className="ui-button ui-button-sm ui-button-secondary ui-button-block" disabled={disabled} onClick={() => { setError(null); setForm('link') }}>Привязать к клиенту</button>
                        </div>}
                        {form === 'create' && <form className="conversation-client-form" onSubmit={event => { event.preventDefault(); handleCreateClient() }}>
                            <label className="ui-form-field"><span className="ui-form-label">Имя</span><input className="ui-input ui-input-sm" value={firstName} onChange={event => setFirstName(event.target.value)} disabled={disabled} /></label>
                            <label className="ui-form-field"><span className="ui-form-label">Фамилия</span><input className="ui-input ui-input-sm" value={lastName} onChange={event => setLastName(event.target.value)} disabled={disabled} /></label>
                            <label className="ui-form-field"><span className="ui-form-label">Телефон</span><input type="tel" className="ui-input ui-input-sm" value={phone} onChange={event => setPhone(event.target.value)} disabled={disabled} /></label>
                            <div className="conversation-client-form-actions">
                                <button type="submit" className="ui-button ui-button-sm ui-button-primary ui-button-block" disabled={disabled}>{actionLoading ? 'Создание…' : 'Создать и привязать'}</button>
                                <button type="button" className="ui-button ui-button-sm ui-button-secondary ui-button-block" disabled={disabled} onClick={() => { setForm(null); setError(null) }}>Отмена</button>
                            </div>
                        </form>}
                        {form === 'link' && <form className="conversation-client-form" onSubmit={event => { event.preventDefault(); handleLinkClient() }}>
                            <label className="ui-form-field"><span className="ui-form-label">Поиск клиента</span>
                                <input type="search" className="ui-input ui-input-sm" value={clientQuery} onChange={event => { setClientQuery(event.target.value); setSelectedClientId('') }} disabled={disabled} placeholder="Имя, фамилия или телефон" />
                            </label>
                            <label className="ui-form-field"><span className="ui-form-label">Клиент</span>
                                <select className="ui-select ui-select-sm" value={selectedClientId} onChange={event => setSelectedClientId(event.target.value)} disabled={disabled || loadingClients || !!clientsError}>
                                    <option value="">{loadingClients ? 'Загрузка…' : clients.length ? 'Выберите клиента' : 'Клиенты не найдены'}</option>
                                    {clients.map(item => <option key={item.id} value={item.id}>{[item.firstName, item.lastName].filter(Boolean).join(' ') || 'Без имени'}</option>)}
                                </select>
                            </label>
                            {clientsError && <div className="conversation-client-panel-error" role="alert">{clientsError}<button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={disabled} onClick={() => setClientsReload(value => value + 1)}>Повторить поиск</button></div>}
                            <div className="conversation-client-form-actions">
                                <button type="submit" className="ui-button ui-button-sm ui-button-primary ui-button-block" disabled={disabled || loadingClients || !!clientsError || !selectedClientId}>{actionLoading ? 'Привязка…' : 'Привязать'}</button>
                                <button type="button" className="ui-button ui-button-sm ui-button-secondary ui-button-block" disabled={disabled} onClick={() => { setForm(null); setSelectedClientId(''); setError(null) }}>Отмена</button>
                            </div>
                        </form>}
                    </>
                )}
                {error && <div className="conversation-client-panel-error" role="alert">{error}{retryDetails}</div>}
            </div>

            {clientAccount && <div className="conversation-client-panel-section">
                <div className="conversation-client-section-header"><h4>Аккаунт</h4></div>
                <AccountCard account={clientAccount} />
                {clientId && <>
                    <div className="conversation-client-actions">
                        <button type="button" className="ui-button ui-button-sm ui-button-danger ui-button-block" disabled={disabled} onClick={() => void mutate(() => unassignClientAccount(clientId, clientAccount.id), 'Не удалось отвязать аккаунт')}>Отвязать аккаунт</button>
                        <button type="button" className="ui-button ui-button-sm ui-button-secondary ui-button-block" disabled={disabled || !client} onClick={() => { setError(null); setShowAccountPicker(true) }}>Привязать еще аккаунт</button>
                        {otherAccounts.length > 0 && <button type="button" className="ui-button ui-button-sm ui-button-secondary ui-button-block" aria-expanded={showOtherAccounts} onClick={() => setShowOtherAccounts(value => !value)}>{showOtherAccounts ? 'Скрыть другие аккаунты' : `Другие аккаунты (${otherAccounts.length})`}</button>}
                    </div>
                    {loadingAccounts && <div className="conversation-client-panel-state">Загрузка аккаунтов…</div>}
                    {accountsError && <div className="conversation-client-panel-error" role="alert">{accountsError}<button type="button" className="ui-button ui-button-sm ui-button-secondary" disabled={loadingAccounts || actionLoading} onClick={accounts.reload}>Повторить загрузку аккаунтов</button></div>}
                    {showOtherAccounts && <div className="conversation-client-other-accounts">{otherAccounts.map(account => <AccountCard key={account.id} account={account} />)}</div>}
                </>}
            </div>}

            <div className="conversation-client-panel-section">
                <div className="conversation-client-section-header"><h4>Канал</h4></div>
                {channelError && <div className="conversation-client-panel-error" role="alert">{channelError}{retryDetails}</div>}
                {channel ? <div className="conversation-client-channel">
                    <ChannelAccountAvatar channelId={channel.id} avatarUrl={channel.account?.avatarUrl ?? null} displayName={channel.account?.displayName ?? null} />
                    <PlatformIcon type={channel.type} size={18} />
                    <div>
                        <strong>{channel.name}</strong>
                        <span><PlatformName type={channel.type} /></span>
                        {channel.account?.displayName && <strong>{channel.account.displayName}</strong>}
                        {channel.account?.username && <span>{username(channel.account.username)}</span>}
                        {channel.account?.phone && <span>{channel.account.phone}</span>}
                    </div>
                </div> : !channelError && <div className="conversation-client-panel-state">Канал недоступен{retryDetails}</div>}
            </div>

            <ClientAccountPicker open={showAccountPicker} onClose={() => { if (!actionPending.current) setShowAccountPicker(false) }} onSelect={handleAssignAnotherAccount} busy={actionLoading} />
        </aside>
    )
}

export default function ConversationClientPanel(props: Props) {
    // A changed relationship starts a fresh form session, including A → B → A.
    return <ConversationClientDetails key={`${props.conversation.id}:${props.conversation.clientAccountId}:${props.clientAccount?.clientId ?? ''}`} {...props} />
}
