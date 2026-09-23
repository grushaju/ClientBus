import {
    useEffect,
    useState,
} from 'react'

import {
    Link,
    useNavigate,
    useParams,
} from 'react-router-dom'

import {
    deleteClient,
    getClient,
    getClientAccountsByClient,
    getClientConversations,
    updateClient,
} from '../api/clientApi'

import ClientAccountsSection from '../components/clients/ClientAccountsSection'

import type {
    ClientDto,
    UpdateClientRequest,
} from '../api/types/client'

import type {
    ClientAccountDto,
} from '../api/types/clientAccount'

import type {
    ConversationDto,
} from '../api/types/conversation'

import {
    useWorkspace,
} from '../workspace/WorkspaceContext'

function ClientDetailsPage() {
    const navigate = useNavigate()

    const { clientId } =
        useParams<{
            clientId: string
        }>()

    const [client, setClient] =
        useState<ClientDto | null>(null)

    const [accounts, setAccounts] =
        useState<ClientAccountDto[]>([])

    const [conversations, setConversations] =
        useState<ConversationDto[]>([])

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState<string | null>(null)

    const [editing, setEditing] =
        useState(false)

    const [saving, setSaving] =
        useState(false)

    const [firstName, setFirstName] =
        useState('')

    const [lastName, setLastName] =
        useState('')

    const [phoneList, setPhoneList] =
        useState<string[]>([])

    const [enabled, setEnabled] =
        useState(true)

    const {
        setCurrentWorkspaceId,
    } = useWorkspace()

    useEffect(() => {
        if (!clientId) {
            return
        }

        const load = async () => {
            setLoading(true)
            setError(null)

            try {
                const [
                    clientResult,
                    accountsResult,
                    conversationsResult,
                ] = await Promise.all([
                    getClient(clientId),
                    getClientAccountsByClient(
                        clientId,
                    ),
                    getClientConversations(
                        clientId,
                    ),
                ])

                setClient(clientResult)
                setAccounts(accountsResult)
                setConversations(
                    conversationsResult,
                )

                setFirstName(
                    clientResult.firstName,
                )

                setLastName(
                    clientResult.lastName,
                )

                setPhoneList(
                    clientResult.phoneList,
                )

                setEnabled(
                    clientResult.enabled,
                )
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить клиента',
                )
            } finally {
                setLoading(false)
            }
        }

        void load()
    }, [clientId])

    const handleSave =
        async () => {
            if (!clientId) {
                return
            }

            const request:
                UpdateClientRequest = {
                firstName:
                    firstName.trim(),
                lastName:
                    lastName.trim(),
                phoneList:
                    phoneList.filter(
                        value =>
                            value.trim(),
                    ),
                enabled,
            }

            setSaving(true)
            setError(null)

            try {
                const updated =
                    await updateClient(
                        clientId,
                        request,
                    )

                setClient(updated)
                setEditing(false)
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось сохранить клиента',
                )
            } finally {
                setSaving(false)
            }
        }

    const handleDelete =
        async () => {
            if (!clientId) {
                return
            }

            const confirmed =
                window.confirm(
                    'Удалить этого клиента?',
                )

            if (!confirmed) {
                return
            }

            setError(null)

            try {
                await deleteClient(
                    clientId,
                )

                navigate(
                    '/clients',
                )
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось удалить клиента',
                )
            }
        }

    if (loading) {
        return (
            <div className="clients-state">
                Загрузка…
            </div>
        )
    }

    if (error && !client) {
        return (
            <div className="client-details-page">
                <Link to="/clients">
                    ← Клиенты
                </Link>

                <div className="clients-error">
                    {error}
                </div>
            </div>
        )
    }

    if (!client) {
        return (
            <div className="clients-state">
                Клиент не найден
            </div>
        )
    }

    return (
        <div className="client-details-page">
            <div className="client-details-topbar">
                <Link
                    to="/clients"
                    className="client-back-link"
                >
                    ← Клиенты
                </Link>
            </div>

            {error && (
                <div className="clients-error">
                    {error}
                </div>
            )}

            <div className="client-details-header">
                <div className="client-details-avatar">
                    {getInitials(
                        client.firstName,
                        client.lastName,
                    )}
                </div>

                <div className="client-details-title">
                    <h1>
                        {client.firstName}{' '}
                        {client.lastName}
                    </h1>

                    {!client.enabled && (
                        <span className="client-disabled-badge">
                            Отключён
                        </span>
                    )}
                </div>

                <div className="client-details-actions">
                    <button
                        type="button"
                        onClick={() =>
                            setEditing(
                                value =>
                                    !value,
                            )
                        }
                    >
                        {editing
                            ? 'Отмена'
                            : 'Редактировать'}
                    </button>

                    <button
                        type="button"
                        className="client-danger-button"
                        onClick={
                            handleDelete
                        }
                    >
                        Удалить
                    </button>
                </div>
            </div>

            <div className="client-details-grid">
                <section className="client-details-card">
                    <div className="client-details-card-header">
                        <h2>
                            Данные клиента
                        </h2>
                    </div>

                    {editing ? (
                        <div className="client-edit-form">
                            <label>
                                <span>
                                    Имя
                                </span>

                                <input
                                    value={
                                        firstName
                                    }
                                    onChange={event =>
                                        setFirstName(
                                            event.target
                                                .value,
                                        )
                                    }
                                />
                            </label>

                            <label>
                                <span>
                                    Фамилия
                                </span>

                                <input
                                    value={
                                        lastName
                                    }
                                    onChange={event =>
                                        setLastName(
                                            event.target
                                                .value,
                                        )
                                    }
                                />
                            </label>

                            <label>
                                <span>
                                    Телефоны
                                </span>

                                {phoneList.map(
                                    (
                                        phone,
                                        index,
                                    ) => (
                                        <div
                                            className="client-phone-row"
                                            key={
                                                index
                                            }
                                        >
                                            <input
                                                value={
                                                    phone
                                                }
                                                onChange={event => {
                                                    const next =
                                                        [
                                                            ...phoneList,
                                                        ]

                                                    next[
                                                        index
                                                        ] =
                                                        event
                                                            .target
                                                            .value

                                                    setPhoneList(
                                                        next,
                                                    )
                                                }}
                                            />

                                            <button
                                                type="button"
                                                onClick={() =>
                                                    setPhoneList(
                                                        phoneList.filter(
                                                            (
                                                                _,
                                                                itemIndex,
                                                            ) =>
                                                                itemIndex !==
                                                                index,
                                                        ),
                                                    )
                                                }
                                            >
                                                ×
                                            </button>
                                        </div>
                                    ),
                                )}

                                <button
                                    type="button"
                                    className="client-secondary-button"
                                    onClick={() =>
                                        setPhoneList(
                                            [
                                                ...phoneList,
                                                '',
                                            ],
                                        )
                                    }
                                >
                                    Добавить телефон
                                </button>
                            </label>

                            <label className="client-checkbox">
                                <input
                                    type="checkbox"
                                    checked={
                                        enabled
                                    }
                                    onChange={event =>
                                        setEnabled(
                                            event.target
                                                .checked,
                                        )
                                    }
                                />

                                <span>
                                    Клиент активен
                                </span>
                            </label>

                            <button
                                type="button"
                                className="clients-primary-button"
                                disabled={
                                    saving
                                }
                                onClick={
                                    handleSave
                                }
                            >
                                {saving
                                    ? 'Сохранение…'
                                    : 'Сохранить'}
                            </button>
                        </div>
                    ) : (
                        <div className="client-details-info">
                            <div>
                                <span>
                                    Имя
                                </span>

                                <strong>
                                    {
                                        client.firstName
                                    }{' '}
                                    {
                                        client.lastName
                                    }
                                </strong>
                            </div>

                            <div>
                                <span>
                                    Телефоны
                                </span>

                                <strong>
                                    {client.phoneList
                                        .length
                                        ? client.phoneList.join(
                                            ', ',
                                        )
                                        : 'Не указаны'}
                                </strong>
                            </div>

                            <div>
                                <span>
                                    Создан
                                </span>

                                <strong>
                                    {formatDate(
                                        client.createdAt,
                                    )}
                                </strong>
                            </div>

                            <div>
                                <span>
                                    Изменён
                                </span>

                                <strong>
                                    {formatDate(
                                        client.updatedAt,
                                    )}
                                </strong>
                            </div>
                        </div>
                    )}
                </section>

                <ClientAccountsSection
                    clientId={client.id}
                    accounts={accounts}
                    onAccountsChange={setAccounts}
                />

                <section className="client-details-card client-conversations-card">
                    <div className="client-details-card-header">
                        <h2>
                            Диалоги
                        </h2>

                        <span>
                            {
                                conversations.length
                            }
                        </span>
                    </div>

                    {conversations.length ===
                    0 ? (
                        <div className="client-details-empty">
                            Диалогов нет
                        </div>
                    ) : (
                        <div className="client-conversation-list">
                            {conversations.map(
                                conversation => (
                                    <button
                                        key={
                                            conversation.id
                                        }
                                        type="button"
                                        className="client-conversation-item"
                                        onClick={() => {
                                            setCurrentWorkspaceId(
                                                conversation.workspaceId,
                                            )
                                            navigate(
                                                `/inbox/${conversation.id}`,
                                            )
                                        }}
                                    >
                                        <div>
                                            <strong>
                                                {
                                                    conversation.lastMessagePreview ||
                                                    'Нет сообщений'
                                                }
                                            </strong>

                                            <span>
                                                {
                                                    conversation.lastMessageAt
                                                        ? formatDate(
                                                            conversation.lastMessageAt,
                                                        )
                                                        : 'Нет сообщений'
                                                }
                                            </span>
                                        </div>

                                        {conversation.unreadCount >
                                            0 && (
                                                <span className="unread-badge">
                                                {
                                                    conversation.unreadCount
                                                }
                                            </span>
                                            )}
                                    </button>
                                ),
                            )}
                        </div>
                    )}
                </section>
            </div>
        </div>
    )
}

function getInitials(
    firstName: string,
    lastName: string,
): string {
    return (
        `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`
    ).toUpperCase()
}

function formatDate(
    value: string,
): string {
    return new Intl.DateTimeFormat(
        'ru-RU',
        {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        },
    ).format(new Date(value))
}

export default ClientDetailsPage