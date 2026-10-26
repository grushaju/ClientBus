import { useRef } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useUiResource } from '../api/events/useUiResource'
import { isAccessEvent } from '../api/events/uiScope'
import {
    useEffect,
    useState,
    type ChangeEvent,
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
    clearClientAvatar,
    getClientAvatarContent,
    uploadClientAvatar,
    useClientAccountAvatar,
} from '../api/clientApi'

import ClientAccountsSection from '../components/clients/ClientAccountsSection'
import ClientAvatar from '../components/clients/ClientAvatar'
import ClientAccountAvatar from '../components/clients/ClientAccountAvatar'

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

function ClientDetailsPageContent() {
    const hasLoaded = useRef(false)
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

    const [avatarSrc, setAvatarSrc] =
        useState<string | null>(null)

    const [avatarBusy, setAvatarBusy] =
        useState(false)

    const [selectedAvatarAccountId, setSelectedAvatarAccountId] =
        useState('')

    const [avatarAccountDropdownOpen, setAvatarAccountDropdownOpen] =
        useState(false)

    const avatarAccounts = accounts.filter(
        account => Boolean(account.avatarUrl?.trim()),
    )

    const selectedAvatarAccount = avatarAccounts.find(
        account => account.id === selectedAvatarAccountId,
    )

    const getAvatarAccountName = (account: ClientAccountDto) =>
        account.displayName ||
        account.username ||
        account.phone ||
        account.externalId

    useUiResource('client-details:' + clientId, !!clientId,
        event => isAccessEvent(event) || ['clients.invalidated', 'inbox.invalidated', 'messages.invalidated'].includes(event.type),
        async signal => {
            const [client, accounts, conversations] = await Promise.all([
                getClient(clientId!, signal), getClientAccountsByClient(clientId!, signal), getClientConversations(clientId!, signal),
            ])
            return { client, accounts, conversations }
        }, value => { hasLoaded.current = true; setClient(value.client); setAccounts(value.accounts); setConversations(value.conversations); setError(null) },
        error => setError(error instanceof Error ? error.message : 'Не удалось загрузить клиента'),
        () => { setClient(null); setAccounts([]); setConversations([]); setEditing(false) }, value => { if (!value || !hasLoaded.current) setLoading(value) })
    // Background metadata updates must not overwrite an employee's unsaved form.
    useEffect(() => {
        if (editing || !client) return
        setFirstName(client.firstName); setLastName(client.lastName); setPhoneList(client.phoneList); setEnabled(client.enabled)
    }, [client, editing])

    useEffect(() => {
        if (!clientId || !client?.avatarUrl) {
            setAvatarSrc(null)
            return
        }

        let active = true
        let objectUrl: string | null = null

        setAvatarSrc(null)

        void getClientAvatarContent(clientId)
            .then(blob => {
                if (!active) {
                    return
                }

                objectUrl = URL.createObjectURL(blob)
                setAvatarSrc(objectUrl)
            })
            .catch(err => {
                if (active) {
                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить аватар',
                    )
                }
            })

        return () => {
            active = false

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [clientId, client?.avatarUrl])

    const reloadConversations =
        async () => {
            if (!clientId) {
                return
            }

            try {
                const result =
                    await getClientConversations(
                        clientId,
                    )

                setConversations(result)
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось обновить диалоги',
                )
            }
        }

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

    const handleAvatarUpload = async (
        event: ChangeEvent<HTMLInputElement>,
    ) => {
        const file = event.currentTarget.files?.[0]

        // Позволяет повторно выбрать тот же файл.
        event.currentTarget.value = ''

        if (!clientId || !file) {
            return
        }

        setAvatarBusy(true)
        setError(null)

        try {
            const updated = await uploadClientAvatar(
                clientId,
                file,
            )

            setClient(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить аватар',
            )
        } finally {
            setAvatarBusy(false)
        }
    }

    const handleUseAccountAvatar = async () => {
        if (!clientId || !selectedAvatarAccountId) {
            return
        }

        setAvatarBusy(true)
        setError(null)

        try {
            const updated = await useClientAccountAvatar(
                clientId,
                selectedAvatarAccountId,
            )

            setClient(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось выбрать аватар аккаунта',
            )
        } finally {
            setAvatarBusy(false)
        }
    }

    const handleClearAvatar = async () => {
        if (!clientId || !client?.avatarUrl) {
            return
        }

        setAvatarBusy(true)
        setError(null)

        try {
            const updated = await clearClientAvatar(clientId)

            setClient(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить аватар',
            )
        } finally {
            setAvatarBusy(false)
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
                <ClientAvatar
                    clientId={client.id}
                    avatarUrl={client.avatarUrl}
                    firstName={client.firstName}
                    lastName={client.lastName}
                    size={52}
                    className="client-details-avatar"
                />

                <div className="client-details-title">
                    <div className="client-details-title-row">
                        <h1>
                            {client.firstName}{' '}
                            {client.lastName}
                        </h1>

                        <span
                            className={
                                client.enabled
                                    ? 'client-status-badge client-status-badge-active'
                                    : 'client-status-badge client-status-badge-disabled'
                            }
                        >
                            {client.enabled
                                ? 'Активен'
                                : 'Отключён'}
                        </span>
                    </div>
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
                        className="ui-button ui-button-danger"
                        onClick={
                            handleDelete
                        }
                    >
                        Удалить
                    </button>
                </div>
            </div>

            <div className="client-avatar-controls">
                <label className="client-avatar-file">
                    <span>Загрузить аватар</span>

                    <input
                        type="file"
                        accept="image/jpeg,image/png,image/webp,image/gif"
                        disabled={avatarBusy}
                        onChange={handleAvatarUpload}
                    />
                </label>


                <div className="client-avatar-account-picker">
                    <button
                        type="button"
                        className="client-avatar-account-trigger"
                        aria-haspopup="listbox"
                        aria-expanded={avatarAccountDropdownOpen}
                        disabled={avatarBusy}
                        onClick={() =>
                            setAvatarAccountDropdownOpen(value => !value)
                        }
                        onKeyDown={event => {
                            if (event.key === 'Escape') {
                                setAvatarAccountDropdownOpen(false)
                            }
                        }}
                    >
                        {selectedAvatarAccount ? (
                            <>
                                <ClientAccountAvatar
                                    accountId={selectedAvatarAccount.id}
                                    avatarUrl={selectedAvatarAccount.avatarUrl}
                                    displayName={getAvatarAccountName(selectedAvatarAccount)}
                                    size={28}
                                />
                                <span className="client-avatar-account-name">
                    {getAvatarAccountName(selectedAvatarAccount)}
                </span>
                            </>
                        ) : (
                            <span className="client-avatar-account-placeholder">
                Выбрать аватар аккаунта…
            </span>
                        )}
                        <span className="client-avatar-account-arrow">▾</span>
                    </button>

                    {avatarAccountDropdownOpen && (
                        <div
                            className="client-avatar-account-options"
                            role="listbox"
                            aria-label="Аккаунт для аватара"
                        >
                            {avatarAccounts.length === 0 ? (
                                <div className="client-avatar-account-empty">
                                    Нет аккаунтов с аватаром
                                </div>
                            ) : (
                                avatarAccounts.map(account => (
                                    <button
                                        key={account.id}
                                        type="button"
                                        role="option"
                                        aria-selected={
                                            account.id === selectedAvatarAccountId
                                        }
                                        className="client-avatar-account-option"
                                        disabled={avatarBusy}
                                        onClick={() => {
                                            setSelectedAvatarAccountId(account.id)
                                            setAvatarAccountDropdownOpen(false)
                                        }}
                                    >
                                        <ClientAccountAvatar
                                            accountId={account.id}
                                            avatarUrl={account.avatarUrl}
                                            displayName={getAvatarAccountName(account)}
                                            size={32}
                                        />
                                        <span className="client-avatar-account-name">
                            {getAvatarAccountName(account)}
                        </span>
                                    </button>
                                ))
                            )}
                        </div>
                    )}
                </div>


                <button
                    type="button"
                    disabled={
                        avatarBusy ||
                        !selectedAvatarAccountId
                    }
                    onClick={handleUseAccountAvatar}
                >
                    Использовать аватар аккаунта
                </button>

                <button
                    type="button"
                    disabled={avatarBusy || !client.avatarUrl}
                    onClick={handleClearAvatar}
                >
                    Удалить аватар
                </button>
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
                    onConversationsReload={
                        reloadConversations
                    }
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

function ClientDetailsPage() {
    const route = useParams()
    const { currentEmployee, sessionEpoch } = useAuth()
    const { currentWorkspace } = useWorkspace()
    return <ClientDetailsPageContent key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, currentWorkspace?.id, JSON.stringify(route)].join(':')} />
}

export default ClientDetailsPage
