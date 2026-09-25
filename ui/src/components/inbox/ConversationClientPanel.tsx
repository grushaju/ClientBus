import {
    useEffect,
    useState,
} from 'react'

import {
    assignClientAccount,
    createClient,
    getClient,
    getClientAccountsByClient,
    getClients,
    unassignClientAccount,
} from '../../api/clientApi'

import type {
    ClientDto,
    ClientListItemDto,
} from '../../api/types/client'

import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'

import type {
    ClientAccountDto,
} from '../../api/types/clientAccount'

import { useAuth } from '../../auth/AuthContext'

import PlatformIcon from '../common/platform/PlatformIcon'
import PlatformName from '../common/platform/PlatformName'
import ClientAccountPicker from '../clients/ClientAccountPicker'

interface Props {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    channel: ChannelSummary | null
    onChanged: () => Promise<void>
}

function ConversationClientPanel({
                                     conversation,
                                     clientAccount,
                                     channel,
                                     onChanged,
                                 }: Props) {
    const {
        isSuperAdmin,
    } = useAuth()

    const [client, setClient] =
        useState<ClientDto | null>(null)

    const [clientAccounts, setClientAccounts] =
        useState<ClientAccountSummary[]>([])

    const [loadingClient, setLoadingClient] =
        useState(false)

    const [clients, setClients] =
        useState<ClientListItemDto[]>([])

    const [loadingClients, setLoadingClients] =
        useState(false)

    const [showCreateForm, setShowCreateForm] =
        useState(false)

    const [showLinkForm, setShowLinkForm] =
        useState(false)

    const [showOtherAccounts, setShowOtherAccounts] =
        useState(false)

    const [showAccountPicker, setShowAccountPicker] =
        useState(false)

    const [firstName, setFirstName] =
        useState('')

    const [lastName, setLastName] =
        useState('')

    const [phone, setPhone] =
        useState('')

    const [selectedClientId, setSelectedClientId] =
        useState('')

    const [actionLoading, setActionLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        let cancelled = false

        async function loadClient() {
            setShowOtherAccounts(false)

            if (!clientAccount?.clientId) {
                setClient(null)
                setClientAccounts([])
                return
            }

            setLoadingClient(true)
            setError(null)

            try {
                const result =
                    await getClient(
                        clientAccount.clientId,
                    )

                const accounts =
                    await getClientAccountsByClient(
                        clientAccount.clientId,
                    )

                if (!cancelled) {
                    setClient(result)
                    setClientAccounts(accounts)
                }
            } catch (err) {
                if (!cancelled) {
                    setClient(null)
                    setClientAccounts([])

                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить клиента',
                    )
                }
            } finally {
                if (!cancelled) {
                    setLoadingClient(false)
                }
            }
        }

        void loadClient()

        return () => {
            cancelled = true
        }
    }, [
        clientAccount?.clientId,
    ])

    const loadClients =
        async () => {
            setLoadingClients(true)
            setError(null)

            try {
                const result =
                    await getClients()

                setClients(result)
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить клиентов',
                )
            } finally {
                setLoadingClients(false)
            }
        }

    const handleCreateClient =
        async () => {
            const normalizedFirstName =
                firstName.trim()

            const normalizedLastName =
                lastName.trim()

            if (
                !normalizedFirstName &&
                !normalizedLastName
            ) {
                setError(
                    'Укажите имя или фамилию клиента',
                )
                return
            }

            if (!clientAccount) {
                return
            }

            setActionLoading(true)
            setError(null)

            try {
                const created =
                    await createClient({
                        firstName:
                        normalizedFirstName,
                        lastName:
                        normalizedLastName,
                        phoneList:
                            phone.trim()
                                ? [phone.trim()]
                                : [],
                    })

                await assignClientAccount(
                    created.id,
                    clientAccount.id,
                )

                const updatedAccount:
                    ClientAccountSummary = {
                    ...clientAccount,
                    clientId: created.id,
                }

                setClient(created)

                setClientAccounts([
                    updatedAccount,
                ])

                setShowCreateForm(false)
                setFirstName('')
                setLastName('')
                setPhone('')

                await onChanged()
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось создать клиента',
                )
            } finally {
                setActionLoading(false)
            }
        }

    const handleLinkClient =
        async () => {
            if (
                !selectedClientId ||
                !clientAccount
            ) {
                return
            }

            setActionLoading(true)
            setError(null)

            try {
                await assignClientAccount(
                    selectedClientId,
                    clientAccount.id,
                )

                const linkedClient =
                    await getClient(
                        selectedClientId,
                    )

                const accounts =
                    await getClientAccountsByClient(
                        selectedClientId,
                    )

                setClient(linkedClient)
                setClientAccounts(accounts)

                setShowLinkForm(false)
                setSelectedClientId('')

                await onChanged()
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось привязать аккаунт',
                )
            } finally {
                setActionLoading(false)
            }
        }

    const handleAssignAnotherAccount =
        async (
            account: ClientAccountDto,
        ) => {
            if (!client) {
                return
            }

            setActionLoading(true)
            setError(null)

            try {
                await assignClientAccount(
                    client.id,
                    account.id,
                )

                const accounts =
                    await getClientAccountsByClient(
                        client.id,
                    )

                setClientAccounts(accounts)
                setShowAccountPicker(false)

                await onChanged()
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось привязать аккаунт',
                )
            } finally {
                setActionLoading(false)
            }
        }

    const handleUnlink =
        async () => {
            if (!clientAccount?.clientId) {
                return
            }

            setActionLoading(true)
            setError(null)

            try {
                await unassignClientAccount(
                    clientAccount.clientId,
                    clientAccount.id,
                )

                const orphanAccount:
                    ClientAccountSummary = {
                    ...clientAccount,
                    clientId: null,
                }

                setClient(null)
                setClientAccounts([
                    orphanAccount,
                ])

                await onChanged()
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось отвязать аккаунт',
                )
            } finally {
                setActionLoading(false)
            }
        }

    const clientName = client
        ? [
            client.firstName,
            client.lastName,
        ]
            .filter(Boolean)
            .join(' ')
        : null

    const accountName =
        clientAccount?.displayName ||
        clientAccount?.username ||
        clientAccount?.phone ||
        clientAccount?.externalId ||
        'Неизвестный аккаунт'

    const currentAccount =
        clientAccount
            ? clientAccounts.find(
            account =>
                account.id ===
                clientAccount.id,
        ) ?? clientAccount
            : null

    const otherAccounts =
        clientAccounts.filter(
            account =>
                account.id !==
                clientAccount?.id,
        )

    return (
        <aside className="conversation-client-panel">
            <div className="conversation-client-panel-header">
                <h3>Клиент</h3>
            </div>

            <div className="conversation-client-panel-section">
                {loadingClient ? (
                    <div className="conversation-client-panel-state">
                        Загрузка клиента…
                    </div>
                ) : client ? (
                    <>
                        <div className="conversation-client-card">
                            <div className="conversation-client-avatar">
                                {(clientName ||
                                    accountName)
                                    .charAt(0)
                                    .toUpperCase()}
                            </div>

                            <div className="conversation-client-card-info">
                                <strong>
                                    {clientName ||
                                        'Без имени'}
                                </strong>

                                {!client.enabled && (
                                    <span className="conversation-client-disabled">
                                        Неактивен
                                    </span>
                                )}
                            </div>
                        </div>

                        {client.phoneList.length > 0 && (
                            <div className="conversation-client-field">
                                <span>
                                    Телефон
                                </span>

                                <div>
                                    {client.phoneList.map(
                                        value => (
                                            <div
                                                key={value}
                                            >
                                                {value}
                                            </div>
                                        ),
                                    )}
                                </div>
                            </div>
                        )}
                    </>
                ) : (
                    <>
                        <div className="conversation-client-orphan">
                            <strong>
                                Клиент не создан
                            </strong>

                            <span>
                                Аккаунт существует, но
                                пока не привязан к клиенту.
                            </span>
                        </div>

                        {!showCreateForm &&
                            !showLinkForm && (
                                <div className="conversation-client-actions">
                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-primary ui-button-block"
                                        onClick={() => {
                                            setError(null)
                                            setShowCreateForm(true)
                                        }}
                                    >
                                        Создать клиента
                                    </button>

                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-secondary ui-button-block"
                                        onClick={() => {
                                            setError(null)
                                            setShowLinkForm(true)
                                            void loadClients()
                                        }}
                                    >
                                        Привязать к клиенту
                                    </button>
                                </div>
                            )}

                        {showCreateForm && (
                            <div className="conversation-client-form">
                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        Имя
                                    </span>

                                    <input
                                        className="ui-input ui-input-sm"
                                        value={firstName}
                                        onChange={event =>
                                            setFirstName(
                                                event.target.value,
                                            )
                                        }
                                        disabled={actionLoading}
                                    />
                                </label>

                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        Фамилия
                                    </span>

                                    <input
                                        className="ui-input ui-input-sm"
                                        value={lastName}
                                        onChange={event =>
                                            setLastName(
                                                event.target.value,
                                            )
                                        }
                                        disabled={actionLoading}
                                    />
                                </label>

                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        Телефон
                                    </span>

                                    <input
                                        className="ui-input ui-input-sm"
                                        value={phone}
                                        onChange={event =>
                                            setPhone(
                                                event.target.value,
                                            )
                                        }
                                        disabled={actionLoading}
                                    />
                                </label>

                                <div className="conversation-client-form-actions">
                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-primary ui-button-block"
                                        onClick={() =>
                                            void handleCreateClient()
                                        }
                                        disabled={actionLoading}
                                    >
                                        {actionLoading
                                            ? 'Создание…'
                                            : 'Создать и привязать'}
                                    </button>

                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-secondary ui-button-block"
                                        onClick={() => {
                                            setShowCreateForm(false)
                                            setError(null)
                                        }}
                                        disabled={actionLoading}
                                    >
                                        Отмена
                                    </button>
                                </div>
                            </div>
                        )}

                        {showLinkForm && (
                            <div className="conversation-client-form">
                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        Клиент
                                    </span>

                                    <select
                                        className="ui-select ui-select-sm"
                                        value={selectedClientId}
                                        onChange={event =>
                                            setSelectedClientId(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            actionLoading ||
                                            loadingClients
                                        }
                                    >
                                        <option value="">
                                            {loadingClients
                                                ? 'Загрузка…'
                                                : 'Выберите клиента'}
                                        </option>

                                        {clients.map(
                                            item => (
                                                <option
                                                    key={item.id}
                                                    value={item.id}
                                                >
                                                    {[
                                                            item.firstName,
                                                            item.lastName,
                                                        ]
                                                            .filter(Boolean)
                                                            .join(' ') ||
                                                        'Без имени'}
                                                </option>
                                            ),
                                        )}
                                    </select>
                                </label>

                                <div className="conversation-client-form-actions">
                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-primary ui-button-block"
                                        onClick={() =>
                                            void handleLinkClient()
                                        }
                                        disabled={
                                            actionLoading ||
                                            !selectedClientId
                                        }
                                    >
                                        {actionLoading
                                            ? 'Привязка…'
                                            : 'Привязать'}
                                    </button>

                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-secondary ui-button-block"
                                        onClick={() => {
                                            setShowLinkForm(false)
                                            setSelectedClientId('')
                                            setError(null)
                                        }}
                                        disabled={actionLoading}
                                    >
                                        Отмена
                                    </button>
                                </div>
                            </div>
                        )}
                    </>
                )}

                {error && (
                    <div className="conversation-client-panel-error">
                        {error}
                    </div>
                )}
            </div>

            {client && currentAccount && (
                <div className="conversation-client-panel-section">
                    <div className="conversation-client-section-header">
                        <h4>
                            Аккаунт
                        </h4>
                    </div>

                    <div className="conversation-client-account-card">
                        <div className="conversation-client-account-icon">
                            <PlatformIcon
                                type={
                                    currentAccount.channelType
                                }
                                size={18}
                            />
                        </div>

                        <div className="conversation-client-account-info">
                            <strong>
                                {currentAccount.displayName ||
                                    currentAccount.username ||
                                    currentAccount.phone ||
                                    currentAccount.externalId}
                            </strong>

                            <span>
                                <PlatformName
                                    type={
                                        currentAccount.channelType
                                    }
                                />
                            </span>

                            {currentAccount.username && (
                                <span>
                                    @{currentAccount.username}
                                </span>
                            )}

                            {currentAccount.phone && (
                                <span>
                                    {currentAccount.phone}
                                </span>
                            )}
                        </div>
                    </div>

                    <div className="conversation-client-actions">
                        <button
                            type="button"
                            className="ui-button ui-button-sm ui-button-danger ui-button-block"
                            onClick={() =>
                                void handleUnlink()
                            }
                            disabled={actionLoading}
                        >
                            {actionLoading
                                ? 'Выполняется…'
                                : 'Отвязать аккаунт'}
                        </button>

                        {(
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary ui-button-block"
                                onClick={() => {
                                    setError(null)
                                    setShowAccountPicker(true)
                                }}
                                disabled={actionLoading}
                            >
                                Привязать еще аккаунт
                            </button>
                        )}

                        {otherAccounts.length > 0 && (
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary ui-button-block"
                                onClick={() =>
                                    setShowOtherAccounts(
                                        value => !value,
                                    )
                                }
                            >
                                {showOtherAccounts
                                    ? 'Скрыть другие аккаунты'
                                    : `Другие аккаунты (${otherAccounts.length})`}
                            </button>
                        )}

                    </div>

                    {showOtherAccounts &&
                        otherAccounts.length > 0 && (
                            <div className="conversation-client-other-accounts">
                                {otherAccounts.map(
                                    account => (
                                        <div
                                            key={account.id}
                                            className="conversation-client-account-card"
                                        >
                                        <div className="conversation-client-account-icon">
                                                <PlatformIcon
                                                    type={
                                                        account.channelType
                                                    }
                                                    size={18}
                                                />
                                            </div>

                                            <div className="conversation-client-account-info">
                                                <strong>
                                                    {account.displayName ||
                                                        account.username ||
                                                        account.phone ||
                                                        account.externalId}
                                                </strong>

                                                <span>
                                                    <PlatformName
                                                        type={
                                                            account.channelType
                                                        }
                                                    />
                                                </span>

                                                {account.username && (
                                                    <span>
                                                        @{account.username}
                                                    </span>
                                                )}

                                                {account.phone && (
                                                    <span>
                                                        {account.phone}
                                                    </span>
                                                )}
                                            </div>
                                        </div>
                                    ),
                                )}
                            </div>
                        )}
                </div>
            )}

            {channel && (
                <div className="conversation-client-panel-section">
                    <div className="conversation-client-section-header">
                        <h4>
                            Канал
                        </h4>
                    </div>

                    <div className="conversation-client-channel">
                        <PlatformIcon
                            type={channel.type}
                            size={18}
                        />

                        <div>
                            <strong>
                                {channel.name}
                            </strong>

                            <span>
                                <PlatformName
                                    type={channel.type}
                                />
                            </span>
                        </div>
                    </div>
                </div>
            )}

            <ClientAccountPicker
                open={showAccountPicker}
                onClose={() => {
                    if (!actionLoading) {
                        setShowAccountPicker(false)
                    }
                }}
                onSelect={account =>
                    void handleAssignAnotherAccount(
                        account,
                    )
                }
                busy={actionLoading}
            />
        </aside>
    )
}

export default ConversationClientPanel