import {
    useEffect,
    useMemo,
    useRef,
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
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { useAuth } from '../../auth/AuthContext'

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

function DiscoveryAvatar({ preview, displayName }: {
    preview: ClientAccountDiscoveryDto['avatarPreview']
    displayName: string
}) {
    const [imageUrl, setImageUrl] = useState<string | null>(null)
    const objectUrl = useRef<string | null>(null)
    const release = () => {
        if (objectUrl.current) URL.revokeObjectURL(objectUrl.current)
        objectUrl.current = null
    }

    useEffect(() => {
        setImageUrl(null)
        // Only bounded raster content is accepted; no remote URL or SVG is rendered.
        if (preview && ['image/jpeg', 'image/png', 'image/webp'].includes(preview.contentType)
            && typeof preview.base64 === 'string' && preview.base64.length > 0
            && preview.base64.length <= Math.ceil(256 * 1024 / 3) * 4) {
            try {
                const binary = atob(preview.base64)
                if (binary.length > 256 * 1024) return
                const bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
                objectUrl.current = URL.createObjectURL(new Blob([bytes], { type: preview.contentType }))
                setImageUrl(objectUrl.current)
            } catch {
                release()
            }
        }
        return release
    }, [preview])

    return <span className="new-conversation-result-avatar" aria-hidden="true">
        {imageUrl ? <img src={imageUrl} alt="" onError={() => { release(); setImageUrl(null) }} />
            : displayName.trim().charAt(0).toUpperCase() || '•'}
    </span>
}

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
                    channel.type === 'TELEGRAM' &&
                    channel.status === 'CONNECTED' &&
                    channel.account !== null,
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

    const { sessionEpoch } = useAuth()
    const lifetime = useUiLifetime(`new-conversation:${workspaceId}:${selectedChannel?.account?.id ?? ''}:${sessionEpoch}`)
    const [discoveryState, setDiscovery] = useState<{
        owner: typeof lifetime
        account: ClientAccountDiscoveryDto
    } | null>(null)
    const discovery = discoveryState?.owner === lifetime ? discoveryState.account : null
    const flight = useRef<{ owner: typeof lifetime; kind: 'search' | 'create' } | null>(null)

    useEffect(() => {
        setDiscovery(null)
        setLoading(false)
        setCreating(false)
        setError(null)
        flight.current = null
    }, [lifetime])

    const resetSearch = () => {
        lifetime.reset()
        flight.current = null
        setLoading(false)
        setCreating(false)
        setDiscovery(null)
        setError(null)
    }
    const handleClose = () => {
        resetSearch()
        onClose()
    }

    useEffect(() => {
        if (telegramChannels.length === 0) {
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
                !creating
            ) {
                lifetime.reset()
                flight.current = null
                setDiscovery(null)
                setLoading(false)
                setError(null)
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
        lifetime,
        onClose,
    ])

    const handleSearchModeChange = (
        mode: SearchMode,
    ) => {
        setSearchMode(mode)
        setSearchValue('')
        resetSearch()
    }

    const handleSearch = async () => {
        if (!lifetime.isCurrent() || flight.current?.owner === lifetime) return
        if (!selectedChannel?.account) {
            setError(
                'Выберите подключённый Telegram-канал.',
            )
            return
        }
        const channelAccountId = selectedChannel.account.id

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

        lifetime.reset()
        const allowed = lifetime.capture()
        const request = { owner: lifetime, kind: 'search' as const }
        flight.current = request
        setLoading(true)
        setError(null)
        setDiscovery(null)

        try {
            const result =
                await lifetime.read(signal => findTelegramClientAccount(
                    channelAccountId,
                    searchMode === 'phone'
                        ? {
                            phone: value,
                        }
                        : {
                            username: value,
                        },
                    signal,
                ))

            if (allowed()) setDiscovery({ owner: lifetime, account: result })
        } catch (err) {
            if (!allowed()) return
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось найти пользователя.',
            )
        } finally {
            if (flight.current === request) flight.current = null
            if (allowed()) setLoading(false)
        }
    }

    const handleCreate = async () => {
        if (
            !selectedChannel ||
            !selectedChannel.account ||
            !discovery ||
            !lifetime.isCurrent() ||
            flight.current?.owner === lifetime
        ) {
            return
        }
        const channelAccountId = selectedChannel.account.id

        const allowed = lifetime.capture()
        const request = { owner: lifetime, kind: 'create' as const }
        flight.current = request
        setCreating(true)
        setError(null)

        try {
            const conversation =
                await lifetime.read(signal => createOutboundConversation({
                    workspaceId,
                    channelAccountId:
                    channelAccountId,
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
                }, signal))

            if (!allowed()) return
            resetSearch()
            onCreated(
                conversation.id,
            )
        } catch (err) {
            if (!allowed()) return
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать диалог.',
            )
        } finally {
            if (flight.current === request) flight.current = null
            if (allowed()) setCreating(false)
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
            className="ui-dialog-overlay"
            onMouseDown={event => {
                if (
                    event.target ===
                    event.currentTarget &&
                    !creating
                ) {
                    handleClose()
                }
            }}
        >
            <div
                className="ui-dialog new-conversation-dialog"
                role="dialog"
                aria-modal="true"
                aria-labelledby="new-conversation-title"
                onMouseDown={event =>
                    event.stopPropagation()
                }
            >
                <div className="ui-dialog-header">
                    <div>
                        <h2
                            id="new-conversation-title"
                            className="ui-dialog-title"
                        >
                            Новый диалог
                        </h2>

                        <div className="new-conversation-dialog-subtitle">
                            Найдите пользователя
                            и начните диалог
                        </div>
                    </div>

                    <button
                        type="button"
                        className="ui-dialog-close"
                        onClick={handleClose}
                        disabled={creating}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                {telegramChannels.length === 0 ? (
                    <>
                        <div className="ui-dialog-body">
                            <div className="ui-form-hint new-conversation-dialog-hint">
                                Нет подключённых
                                Telegram-каналов,
                                доступных для
                                поиска.
                            </div>

                            <div
                                className="ui-form-hint new-conversation-dialog-hint"
                                style={{
                                    marginTop:
                                        'var(--ui-space-3)',
                                }}
                            >
                                Сначала подключите
                                Telegram в разделе
                                «Каналы».
                            </div>
                        </div>

                        <div className="ui-dialog-footer">
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary"
                                onClick={handleClose}
                            >
                                Закрыть
                            </button>
                        </div>
                    </>
                ) : (
                    <>
                        <div className="ui-dialog-body">
                            <div className="ui-form">
                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        Канал
                                    </span>

                                    <select
                                        className="ui-select"
                                        value={
                                            selectedChannelId
                                        }
                                        onChange={event => {
                                            resetSearch()
                                            setSelectedChannelId(
                                                event.target.value,
                                            )
                                        }}
                                        disabled={creating}
                                    >
                                        {telegramChannels.map(
                                            channel => (
                                                <option
                                                    key={channel.id}
                                                    value={channel.id}
                                                >
                                                    {channel.name}
                                                </option>
                                            ),
                                        )}
                                    </select>
                                </label>

                                <div className="new-conversation-search-tabs">
                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-secondary"
                                        data-active={
                                            searchMode ===
                                            'phone'
                                        }
                                        onClick={() =>
                                            handleSearchModeChange(
                                                'phone',
                                            )
                                        }
                                        disabled={creating}
                                    >
                                        Телефон
                                    </button>

                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-secondary"
                                        data-active={
                                            searchMode ===
                                            'username'
                                        }
                                        onClick={() =>
                                            handleSearchModeChange(
                                                'username',
                                            )
                                        }
                                        disabled={creating}
                                    >
                                        Username
                                    </button>
                                </div>

                                <label className="ui-form-field">
                                    <span className="ui-form-label">
                                        {searchMode ===
                                        'phone'
                                            ? 'Номер телефона'
                                            : 'Username'}
                                    </span>

                                    <input
                                        className="ui-input"
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
                                            resetSearch()
                                            setSearchValue(
                                                event.target.value,
                                            )
                                        }}
                                        placeholder={
                                            searchMode ===
                                            'phone'
                                                ? '+49...'
                                                : '@username'
                                        }
                                        disabled={creating}
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
                                    <div className="ui-form-error">
                                        {error}
                                    </div>
                                )}

                                {discovery && (
                                    <div className="new-conversation-result">
                                        <div className="new-conversation-result-title">
                                            Пользователь
                                            найден
                                        </div>

                                        <div className="new-conversation-result-identity">
                                            <DiscoveryAvatar preview={discovery.avatarPreview} displayName={displayName} />
                                            <div className="new-conversation-result-name">{displayName}</div>
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
                        </div>

                        <div className="ui-dialog-footer">
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary"
                                onClick={handleClose}
                                disabled={creating}
                            >
                                Отмена
                            </button>

                            {!discovery ? (
                                <button
                                    type="button"
                                    className="ui-button ui-button-sm ui-button-primary"
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
                                        className="ui-button ui-button-sm ui-button-secondary"
                                        onClick={resetSearch}
                                        disabled={
                                            creating
                                        }
                                    >
                                        Новый поиск
                                    </button>

                                    <button
                                        type="button"
                                        className="ui-button ui-button-sm ui-button-primary"
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
