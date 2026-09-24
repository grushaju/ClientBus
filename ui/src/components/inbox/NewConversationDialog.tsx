import {
    useEffect,
    useMemo,
    useState,
} from 'react'

import {
    createOutboundConversation,
} from '../../api/conversationApi'

import {
    findTelegramClientAccount,
} from '../../api/telegramChannelApi'

import type {
    ChannelDto,
    ClientAccountDiscoveryDto,
} from '../../api/types/channel'

interface NewConversationDialogProps {
    workspaceId: string
    channels: ChannelDto[]
    onClose: () => void
    onCreated: (
        conversationId: string,
    ) => void
}

type SearchMode =
    | 'phone'
    | 'username'

function NewConversationDialog({
                                   workspaceId,
                                   channels,
                                   onClose,
                                   onCreated,
                               }: NewConversationDialogProps) {
    const telegramChannels = useMemo(
        () =>
            channels.filter(
                channel =>
                    channel.type ===
                    'TELEGRAM' &&
                    channel.status ===
                    'CONNECTED' &&
                    channel.account !==
                    null,
            ),
        [channels],
    )

    const [
        selectedChannelId,
        setSelectedChannelId,
    ] = useState<string>(
        telegramChannels[0]?.id ?? '',
    )

    const [
        searchMode,
        setSearchMode,
    ] = useState<SearchMode>('phone')

    const [
        searchValue,
        setSearchValue,
    ] = useState('')

    const [
        discovery,
        setDiscovery,
    ] = useState<ClientAccountDiscoveryDto | null>(
        null,
    )

    const [
        loading,
        setLoading,
    ] = useState(false)

    const [
        creating,
        setCreating,
    ] = useState(false)

    const [
        error,
        setError,
    ] = useState<string | null>(null)

    const selectedChannel =
        telegramChannels.find(
            channel =>
                channel.id ===
                selectedChannelId,
        ) ?? null

    useEffect(() => {
        if (
            telegramChannels.length ===
            0
        ) {
            setSelectedChannelId('')
            return
        }

        const stillExists =
            telegramChannels.some(
                channel =>
                    channel.id ===
                    selectedChannelId,
            )

        if (!stillExists) {
            setSelectedChannelId(
                telegramChannels[0].id,
            )
        }
    }, [
        selectedChannelId,
        telegramChannels,
    ])

    useEffect(() => {
        const handleKeyDown = (
            event: KeyboardEvent,
        ) => {
            if (
                event.key === 'Escape' &&
                !loading &&
                !creating
            ) {
                onClose()
            }
        }

        window.addEventListener(
            'keydown',
            handleKeyDown,
        )

        return () => {
            window.removeEventListener(
                'keydown',
                handleKeyDown,
            )
        }
    }, [
        creating,
        loading,
        onClose,
    ])

    const handleSearchModeChange = (
        mode: SearchMode,
    ) => {
        setSearchMode(mode)
        setSearchValue('')
        setDiscovery(null)
        setError(null)
    }

    const handleSearch = async () => {
        if (!selectedChannel?.account) {
            setError(
                'Выберите подключённый Telegram-канал.',
            )
            return
        }

        const value =
            searchValue.trim()

        if (!value) {
            setError(
                searchMode === 'phone'
                    ? 'Введите номер телефона.'
                    : 'Введите username.',
            )
            return
        }

        setLoading(true)
        setError(null)
        setDiscovery(null)

        try {
            const result =
                await findTelegramClientAccount(
                    selectedChannel.account.id,
                    searchMode === 'phone'
                        ? {
                            phone: value,
                        }
                        : {
                            username: value,
                        },
                )

            setDiscovery(result)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось найти пользователя.',
            )
        } finally {
            setLoading(false)
        }
    }

    const handleCreate = async () => {
        if (
            !selectedChannel ||
            !selectedChannel.account ||
            !discovery
        ) {
            return
        }

        setCreating(true)
        setError(null)

        try {
            const conversation =
                await createOutboundConversation(
                    {
                        workspaceId,
                        channelAccountId:
                        selectedChannel
                            .account.id,
                        channelType:
                        selectedChannel.type,
                        externalId:
                        discovery.externalId,
                        username:
                        discovery.username,
                        phone:
                        discovery.phone,
                        displayName:
                        discovery.displayName,
                    },
                )

            onCreated(
                conversation.id,
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать диалог.',
            )
        } finally {
            setCreating(false)
        }
    }

    const displayName =
        discovery?.displayName ||
        discovery?.username ||
        discovery?.phone ||
        discovery?.externalId ||
        'Пользователь'

    return (
        <div
            className="channel-modal-overlay"
            onMouseDown={event => {
                if (
                    event.target ===
                    event.currentTarget &&
                    !loading &&
                    !creating
                ) {
                    onClose()
                }
            }}
        >
            <div
                className="channel-modal new-conversation-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="new-conversation-title"
                onMouseDown={event =>
                    event.stopPropagation()
                }
            >
                <div className="channel-modal-header">
                    <div>
                        <h2 id="new-conversation-title">
                            Новый диалог
                        </h2>

                        <div className="channel-modal-subtitle">
                            Найдите пользователя
                            и начните диалог
                        </div>
                    </div>

                    <button
                        type="button"
                        className="channel-modal-close"
                        onClick={onClose}
                        disabled={
                            loading ||
                            creating
                        }
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                {telegramChannels.length ===
                0 ? (
                    <>
                        <div className="channel-form">
                            <div className="channel-form-hint">
                                Нет подключённых
                                Telegram-каналов,
                                доступных для
                                поиска.
                            </div>

                            <div className="channel-form-hint">
                                Сначала подключите
                                Telegram в разделе
                                «Каналы».
                            </div>
                        </div>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="channel-button channel-button-secondary"
                                onClick={
                                    onClose
                                }
                            >
                                Закрыть
                            </button>
                        </div>
                    </>
                ) : (
                    <>
                        <div className="channel-form">
                            <label className="channel-form-field">
                                <span>
                                    Канал
                                </span>

                                <select
                                    value={
                                        selectedChannelId
                                    }
                                    onChange={event => {
                                        setSelectedChannelId(
                                            event
                                                .target
                                                .value,
                                        )
                                        setDiscovery(
                                            null,
                                        )
                                        setError(
                                            null,
                                        )
                                    }}
                                    disabled={
                                        loading ||
                                        creating
                                    }
                                >
                                    {telegramChannels.map(
                                        channel => (
                                            <option
                                                key={
                                                    channel.id
                                                }
                                                value={
                                                    channel.id
                                                }
                                            >
                                                {
                                                    channel.name
                                                }
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <div className="new-conversation-search-tabs">
                                <button
                                    type="button"
                                    className={
                                        searchMode ===
                                        'phone'
                                            ? 'active'
                                            : ''
                                    }
                                    onClick={() =>
                                        handleSearchModeChange(
                                            'phone',
                                        )
                                    }
                                    disabled={
                                        loading ||
                                        creating
                                    }
                                >
                                    Телефон
                                </button>

                                <button
                                    type="button"
                                    className={
                                        searchMode ===
                                        'username'
                                            ? 'active'
                                            : ''
                                    }
                                    onClick={() =>
                                        handleSearchModeChange(
                                            'username',
                                        )
                                    }
                                    disabled={
                                        loading ||
                                        creating
                                    }
                                >
                                    Username
                                </button>
                            </div>

                            <label className="channel-form-field">
                                <span>
                                    {searchMode ===
                                    'phone'
                                        ? 'Номер телефона'
                                        : 'Username'}
                                </span>

                                <input
                                    type={
                                        searchMode ===
                                        'phone'
                                            ? 'tel'
                                            : 'text'
                                    }
                                    value={
                                        searchValue
                                    }
                                    onChange={event => {
                                        setSearchValue(
                                            event
                                                .target
                                                .value,
                                        )
                                        setDiscovery(
                                            null,
                                        )
                                        setError(
                                            null,
                                        )
                                    }}
                                    placeholder={
                                        searchMode ===
                                        'phone'
                                            ? '+49...'
                                            : '@username'
                                    }
                                    disabled={
                                        loading ||
                                        creating
                                    }
                                    autoFocus
                                    onKeyDown={event => {
                                        if (
                                            event.key ===
                                            'Enter' &&
                                            !loading &&
                                            !creating
                                        ) {
                                            event.preventDefault()
                                            void handleSearch()
                                        }
                                    }}
                                />
                            </label>

                            {error && (
                                <div className="channel-form-error">
                                    {error}
                                </div>
                            )}

                            {discovery && (
                                <div className="new-conversation-result">
                                    <div className="new-conversation-result-title">
                                        Пользователь
                                        найден
                                    </div>

                                    <div className="new-conversation-result-name">
                                        {displayName}
                                    </div>

                                    {discovery.username && (
                                        <div className="new-conversation-result-row">
                                            <span>
                                                Username
                                            </span>

                                            <strong>
                                                @
                                                {
                                                    discovery.username
                                                }
                                            </strong>
                                        </div>
                                    )}

                                    {discovery.phone && (
                                        <div className="new-conversation-result-row">
                                            <span>
                                                Телефон
                                            </span>

                                            <strong>
                                                {
                                                    discovery.phone
                                                }
                                            </strong>
                                        </div>
                                    )}

                                    <div className="new-conversation-result-row">
                                        <span>
                                            Telegram ID
                                        </span>

                                        <strong>
                                            {
                                                discovery.externalId
                                            }
                                        </strong>
                                    </div>

                                    <div className="new-conversation-result-existing">
                                        {discovery.existingClientAccountId
                                            ? 'Клиент уже есть в ClientBus. Будет использован существующий аккаунт.'
                                            : 'Клиент ещё не связан с ClientBus. Аккаунт будет создан автоматически.'}
                                    </div>
                                </div>
                            )}
                        </div>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="channel-button channel-button-secondary"
                                onClick={
                                    onClose
                                }
                                disabled={
                                    loading ||
                                    creating
                                }
                            >
                                Отмена
                            </button>

                            {!discovery ? (
                                <button
                                    type="button"
                                    className="channel-button channel-button-primary"
                                    onClick={() =>
                                        void handleSearch()
                                    }
                                    disabled={
                                        loading ||
                                        creating ||
                                        !searchValue.trim() ||
                                        !selectedChannel
                                    }
                                >
                                    {loading
                                        ? 'Поиск…'
                                        : 'Найти'}
                                </button>
                            ) : (
                                <>
                                    <button
                                        type="button"
                                        className="channel-button channel-button-secondary"
                                        onClick={() => {
                                            setDiscovery(
                                                null,
                                            )
                                            setError(
                                                null,
                                            )
                                        }}
                                        disabled={
                                            creating
                                        }
                                    >
                                        Новый поиск
                                    </button>

                                    <button
                                        type="button"
                                        className="channel-button channel-button-primary"
                                        onClick={() =>
                                            void handleCreate()
                                        }
                                        disabled={
                                            creating ||
                                            !selectedChannel
                                        }
                                    >
                                        {creating
                                            ? 'Создание…'
                                            : 'Открыть диалог'}
                                    </button>
                                </>
                            )}
                        </div>
                    </>
                )}
            </div>
        </div>
    )
}

export default NewConversationDialog