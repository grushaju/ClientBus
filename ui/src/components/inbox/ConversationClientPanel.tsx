import {
    useEffect,
    useState,
} from 'react'

import {
    assignClientAccount,
    createClient,
    getClient,
    getWorkspaceClients,
    unassignClientAccount,
} from '../../api/clientApi'

import {
    getClientAccountsByClient,
} from '../../api/clientAccountApi'

import type { ClientDto } from '../../api/types/client'

import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'

import PlatformIcon from '../common/platform/PlatformIcon'
import PlatformName from '../common/platform/PlatformName'

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
    const [client, setClient] =
        useState<ClientDto | null>(null)

    const [clientAccounts, setClientAccounts] =
        useState<ClientAccountSummary[]>([])

    const [loadingClient, setLoadingClient] =
        useState(false)

    const [clients, setClients] =
        useState<ClientDto[]>([])

    const [loadingClients, setLoadingClients] =
        useState(false)

    const [showCreateForm, setShowCreateForm] =
        useState(false)

    const [showLinkForm, setShowLinkForm] =
        useState(false)

    const [showOtherAccounts, setShowOtherAccounts] =
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
    }, [clientAccount?.clientId])

    const loadClients = async () => {
        setLoadingClients(true)
        setError(null)

        try {
            const result =
                await getWorkspaceClients(
                    conversation.workspaceId,
                )

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

    const handleCreateClient = async () => {
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
                    workspaceId:
                    conversation.workspaceId,
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

            const updatedAccount: ClientAccountSummary = {
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

    const handleLinkClient = async () => {
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

    const handleUnlink = async () => {
        if (!clientAccount) {
            return
        }

        setActionLoading(true)
        setError(null)

        try {
            await unassignClientAccount(
                clientAccount.id,
            )

            const orphanAccount: ClientAccountSummary = {
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
                                        className="conversation-client-primary-button"
                                        onClick={() => {
                                            setError(null)
                                            setShowCreateForm(true)
                                        }}
                                    >
                                        Создать клиента
                                    </button>

                                    <button
                                        type="button"
                                        className="conversation-client-secondary-button"
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
                                <label>
                                    <span>
                                        Имя
                                    </span>

                                    <input
                                        value={firstName}
                                        onChange={event =>
                                            setFirstName(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            actionLoading
                                        }
                                    />
                                </label>

                                <label>
                                    <span>
                                        Фамилия
                                    </span>

                                    <input
                                        value={lastName}
                                        onChange={event =>
                                            setLastName(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            actionLoading
                                        }
                                    />
                                </label>

                                <label>
                                    <span>
                                        Телефон
                                    </span>

                                    <input
                                        value={phone}
                                        onChange={event =>
                                            setPhone(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            actionLoading
                                        }
                                    />
                                </label>

                                <div className="conversation-client-form-actions">
                                    <button
                                        type="button"
                                        className="conversation-client-primary-button"
                                        onClick={() =>
                                            void handleCreateClient()
                                        }
                                        disabled={
                                            actionLoading
                                        }
                                    >
                                        {actionLoading
                                            ? 'Создание…'
                                            : 'Создать и привязать'}
                                    </button>

                                    <button
                                        type="button"
                                        className="conversation-client-secondary-button"
                                        onClick={() =>
                                            setShowCreateForm(
                                                false,
                                            )
                                        }
                                        disabled={
                                            actionLoading
                                        }
                                    >
                                        Отмена
                                    </button>
                                </div>
                            </div>
                        )}

                        {showLinkForm && (
                            <div className="conversation-client-form">
                                {loadingClients ? (
                                    <div className="conversation-client-panel-state">
                                        Загрузка клиентов…
                                    </div>
                                ) : (
                                    <>
                                        <label>
                                            <span>
                                                Клиент
                                            </span>

                                            <select
                                                value={
                                                    selectedClientId
                                                }
                                                onChange={event =>
                                                    setSelectedClientId(
                                                        event.target.value,
                                                    )
                                                }
                                                disabled={
                                                    actionLoading
                                                }
                                            >
                                                <option value="">
                                                    Выберите клиента
                                                </option>

                                                {clients.map(
                                                    item => {
                                                        const name =
                                                            [
                                                                item.firstName,
                                                                item.lastName,
                                                            ]
                                                                .filter(
                                                                    Boolean,
                                                                )
                                                                .join(
                                                                    ' ',
                                                                )

                                                        return (
                                                            <option
                                                                key={
                                                                    item.id
                                                                }
                                                                value={
                                                                    item.id
                                                                }
                                                            >
                                                                {name ||
                                                                    'Без имени'}
                                                            </option>
                                                        )
                                                    },
                                                )}
                                            </select>
                                        </label>

                                        <div className="conversation-client-form-actions">
                                            <button
                                                type="button"
                                                className="conversation-client-primary-button"
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
                                                className="conversation-client-secondary-button"
                                                onClick={() =>
                                                    setShowLinkForm(
                                                        false,
                                                    )
                                                }
                                                disabled={
                                                    actionLoading
                                                }
                                            >
                                                Отмена
                                            </button>
                                        </div>
                                    </>
                                )}
                            </div>
                        )}
                    </>
                )}
            </div>

            {currentAccount && (
                <div className="conversation-client-panel-section">
                    <h3>Аккаунты</h3>

                    <div className="conversation-accounts-list">
                        <div className="conversation-account-card conversation-account-card-current">
                            <div className="conversation-account-platform">
                                <PlatformIcon
                                    type={
                                        currentAccount.channelType
                                    }
                                    size={20}
                                />

                                <PlatformName
                                    type={
                                        currentAccount.channelType
                                    }
                                />

                                <span className="conversation-account-current">
                                    Текущий
                                </span>
                            </div>

                            <div className="conversation-client-field">
                                <span>Имя</span>
                                <strong>
                                    {currentAccount.displayName ||
                                        currentAccount.username ||
                                        currentAccount.phone ||
                                        currentAccount.externalId}
                                </strong>
                            </div>

                            <div className="conversation-client-field">
                                <span>ID платформы</span>
                                <strong>
                                    {
                                        currentAccount.externalId
                                    }
                                </strong>
                            </div>

                            {currentAccount.username && (
                                <div className="conversation-client-field">
                                    <span>Username</span>
                                    <strong>
                                        @
                                        {
                                            currentAccount.username
                                        }
                                    </strong>
                                </div>
                            )}

                            {currentAccount.phone && (
                                <div className="conversation-client-field">
                                    <span>Телефон</span>
                                    <strong>
                                        {
                                            currentAccount.phone
                                        }
                                    </strong>
                                </div>
                            )}

                            {currentAccount.clientId !== null && (
                                <button
                                    type="button"
                                    className="conversation-client-secondary-button"
                                    onClick={() =>
                                        void handleUnlink()
                                    }
                                    disabled={
                                        actionLoading
                                    }
                                >
                                    {actionLoading
                                        ? 'Выполняется…'
                                        : 'Отвязать аккаунт'}
                                </button>
                            )}
                        </div>

                        {otherAccounts.length > 0 && (
                            <>
                                <button
                                    type="button"
                                    className="conversation-accounts-toggle"
                                    onClick={() =>
                                        setShowOtherAccounts(
                                            value => !value,
                                        )
                                    }
                                >
                                    <span>
                                        Другие (
                                        {
                                            otherAccounts.length
                                        }
                                        )
                                    </span>

                                    <span>
                                        {showOtherAccounts
                                            ? '▴'
                                            : '▾'}
                                    </span>
                                </button>

                                {showOtherAccounts &&
                                    otherAccounts.map(
                                        account => {
                                            const name =
                                                account.displayName ||
                                                account.username ||
                                                account.phone ||
                                                account.externalId

                                            return (
                                                <div
                                                    key={
                                                        account.id
                                                    }
                                                    className="conversation-account-card"
                                                >
                                                    <div className="conversation-account-platform">
                                                        <PlatformIcon
                                                            type={
                                                                account.channelType
                                                            }
                                                            size={
                                                                20
                                                            }
                                                        />

                                                        <PlatformName
                                                            type={
                                                                account.channelType
                                                            }
                                                        />
                                                    </div>

                                                    <div className="conversation-client-field">
                                                        <span>
                                                            Имя
                                                        </span>
                                                        <strong>
                                                            {
                                                                name
                                                            }
                                                        </strong>
                                                    </div>

                                                    <div className="conversation-client-field">
                                                        <span>
                                                            ID платформы
                                                        </span>
                                                        <strong>
                                                            {
                                                                account.externalId
                                                            }
                                                        </strong>
                                                    </div>

                                                    {account.username && (
                                                        <div className="conversation-client-field">
                                                            <span>
                                                                Username
                                                            </span>
                                                            <strong>
                                                                @
                                                                {
                                                                    account.username
                                                                }
                                                            </strong>
                                                        </div>
                                                    )}

                                                    {account.phone && (
                                                        <div className="conversation-client-field">
                                                            <span>
                                                                Телефон
                                                            </span>
                                                            <strong>
                                                                {
                                                                    account.phone
                                                                }
                                                            </strong>
                                                        </div>
                                                    )}
                                                </div>
                                            )
                                        },
                                    )}
                            </>
                        )}
                    </div>
                </div>
            )}

            {error && (
                <div className="conversation-client-panel-error">
                    {error}
                </div>
            )}

            <div className="conversation-client-panel-section">
                <h3>Канал</h3>

                {channel ? (
                    <div className="conversation-channel-card">
                        <div className="conversation-channel-info">
                            <PlatformIcon
                                type={channel.type}
                                size={20}
                            />

                            <div>
                                <strong>
                                    {channel.name}
                                </strong>

                                <span>
                                    <PlatformName
                                        type={
                                            channel.type
                                        }
                                    />
                                </span>
                            </div>
                        </div>

                        <div className="conversation-client-field">
                            <span>Статус</span>
                            <strong>
                                {channel.status}
                            </strong>
                        </div>

                        {channel.account && (
                            <div className="conversation-client-field">
                                <span>
                                    Аккаунт канала
                                </span>

                                <strong>
                                    {channel.account.displayName ||
                                        channel.account.username ||
                                        channel.account.phone ||
                                        channel.account.externalId}
                                </strong>
                            </div>
                        )}
                    </div>
                ) : (
                    <div className="conversation-client-panel-state">
                        Канал не найден
                    </div>
                )}
            </div>
        </aside>
    )
}

export default ConversationClientPanel