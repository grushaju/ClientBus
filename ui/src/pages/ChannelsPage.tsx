import {
    useCallback,
    useEffect,
    useState,
} from 'react'

import {
    deleteChannel,
    getWorkspaceChannels,
} from '../api/channelApi'

import {
    disableTelegramChannel,
    enableTelegramChannel,
} from '../api/telegramChannelApi'

import type {
    ChannelDto,
} from '../api/types/channel'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    useWorkspace,
} from '../workspace/WorkspaceContext'

import {
    ChannelConnectionDialog,
} from '../components/channels/ChannelConnectionDialog'

import {
    ChannelDetailsDialog,
} from '../components/channels/ChannelDetailsDialog'

import {
    CreateChannelDialog,
} from '../components/channels/CreateChannelDialog'

import {
    DeleteChannelDialog,
} from '../components/channels/DeleteChannelDialog'

import {
    EditChannelDialog,
} from '../components/channels/EditChannelDialog'

import {
    getChannelStatusLabel,
    getChannelTypeLabel,
} from '../components/channels/channelLabels'

import PlatformIcon
    from '../components/common/platform/PlatformIcon'

function getChannelStatusClass(
    status: ChannelDto['status'],
): string {
    switch (status) {
        case 'CONNECTED':
            return 'channel-status-connected'

        case 'CONNECTING':
            return 'channel-status-connecting'

        case 'ERROR':
            return 'channel-status-error'

        case 'DISCONNECTED':
        case 'DISABLED':
            return 'channel-status-disconnected'

        case 'CREATED':
        default:
            return 'channel-status-created'
    }
}

function sortChannels(
    channels: ChannelDto[],
): ChannelDto[] {
    return [...channels].sort((a, b) => {
        const typeComparison =
            getChannelTypeLabel(a.type).localeCompare(
                getChannelTypeLabel(b.type),
                'ru',
                {
                    sensitivity: 'base',
                },
            )

        if (typeComparison !== 0) {
            return typeComparison
        }

        const nameComparison =
            a.name.localeCompare(
                b.name,
                'ru',
                {
                    sensitivity: 'base',
                },
            )

        if (nameComparison !== 0) {
            return nameComparison
        }

        return a.id.localeCompare(b.id)
    })
}

function ChannelsPage() {
    const {
        currentWorkspace,
    } = useWorkspace()

    const {
        isSuperAdmin,
    } = useAuth()

    const [channels, setChannels] =
        useState<ChannelDto[]>([])

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    const [selectedChannel, setSelectedChannel] =
        useState<ChannelDto | null>(null)

    const [connectionChannel, setConnectionChannel] =
        useState<ChannelDto | null>(null)

    const [createOpen, setCreateOpen] =
        useState(false)

    const [editChannel, setEditChannel] =
        useState<ChannelDto | null>(null)

    const [deleteChannelTarget, setDeleteChannelTarget] =
        useState<ChannelDto | null>(null)

    const loadChannels = useCallback(
        () => {
            if (!currentWorkspace) {
                setChannels([])
                return
            }

            setLoading(true)
            setError(null)

            getWorkspaceChannels(
                currentWorkspace.id,
            )
                .then(result => {
                    setChannels(
                        sortChannels(result),
                    )
                })
                .catch(err => {
                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить каналы',
                    )
                })
                .finally(() => {
                    setLoading(false)
                })
        },
        [currentWorkspace],
    )

    useEffect(() => {
        loadChannels()
    }, [
        loadChannels,
    ])

    function handleCreated(
        channel: ChannelDto,
    ) {
        setChannels(current =>
            sortChannels([
                ...current,
                channel,
            ]),
        )

        setCreateOpen(false)
        setConnectionChannel(channel)
    }

    function handleConnected(
        _channel: ChannelDto,
    ) {
        setConnectionChannel(null)

        loadChannels()
    }

    function handleConnectionClose() {
        setConnectionChannel(null)
    }

    function handleUpdated(
        channel: ChannelDto,
    ) {
        setChannels(current =>
            sortChannels(
                current.map(item =>
                    item.id === channel.id
                        ? channel
                        : item,
                ),
            ),
        )

        setEditChannel(null)
    }

    function handleDisable(
        channel: ChannelDto,
    ) {
        if (!channel.account) {
            setError(
                'У канала отсутствует подключённый аккаунт.',
            )
            return
        }

        setError(null)

        disableTelegramChannel(
            channel.account.id,
        )
            .then(() => {
                setChannels(current =>
                    sortChannels(
                        current.map(item =>
                            item.id === channel.id
                                ? {
                                    ...item,
                                    status: 'DISABLED',
                                }
                                : item,
                        ),
                    ),
                )
            })
            .catch(err => {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось отключить канал',
                )
            })
    }

    function handleEnable(
        channel: ChannelDto,
    ) {
        if (!channel.account) {
            setError(
                'У канала отсутствует подключённый аккаунт.',
            )
            return
        }

        setError(null)

        enableTelegramChannel(
            channel.account.id,
        )
            .then(() => {
                setChannels(current =>
                    sortChannels(
                        current.map(item =>
                            item.id === channel.id
                                ? {
                                    ...item,
                                    status: 'CONNECTED',
                                }
                                : item,
                        ),
                    ),
                )
            })
            .catch(err => {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось включить канал',
                )
            })
    }

    function handleDelete() {
        if (!deleteChannelTarget) {
            return
        }

        const channel =
            deleteChannelTarget

        setError(null)

        deleteChannel(channel.id)
            .then(() => {
                setChannels(current =>
                    current.filter(
                        item =>
                            item.id !==
                            channel.id,
                    ),
                )

                setDeleteChannelTarget(null)

                if (
                    selectedChannel?.id ===
                    channel.id
                ) {
                    setSelectedChannel(null)
                }

                if (
                    connectionChannel?.id ===
                    channel.id
                ) {
                    setConnectionChannel(null)
                }
            })
            .catch(err => {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось удалить канал',
                )
            })
    }

    if (!currentWorkspace) {
        return (
            <div className="channels-page">
                <div className="channels-empty">
                    Workspace не выбран.
                </div>
            </div>
        )
    }

    return (
        <div className="channels-page">
            <div className="channels-header">
                <div>
                    <h1>
                        Каналы
                    </h1>

                    <p className="channels-subtitle">
                        Каналы рабочего пространства
                        «{currentWorkspace.name}»
                    </p>
                </div>

                {isSuperAdmin && (
                    <button
                        type="button"
                        className="ui-button ui-button-primary"
                        onClick={() =>
                            setCreateOpen(true)
                        }
                    >
                        + Добавить
                    </button>
                )}
            </div>

            {error && (
                <div className="channels-error">
                    {error}
                </div>
            )}

            {loading ? (
                <div className="channels-loading">
                    Загрузка каналов…
                </div>
            ) : channels.length === 0 ? (
                <div className="channels-empty">
                    <div className="channels-empty-title">
                        Каналов пока нет
                    </div>

                    {isSuperAdmin && (
                        <button
                            type="button"
                            className="ui-button ui-button-primary"
                            onClick={() =>
                                setCreateOpen(true)
                            }
                        >
                            + Добавить
                        </button>
                    )}
                </div>
            ) : (
                <div className="channels-grid">
                    {channels.map(
                        channel => (
                            <div
                                key={
                                    channel.id
                                }
                                className="channel-card"
                            >
                                <div className="channel-card-header">
                                    <div className="channel-card-header-main">
                                        <div className="channel-card-platform-icon">
                                            <PlatformIcon
                                                type={
                                                    channel.type
                                                }
                                                size={
                                                    24
                                                }
                                            />
                                        </div>

                                        <div className="channel-card-header-info">
                                            <div className="channel-card-title">
                                                {
                                                    channel.name
                                                }
                                            </div>

                                            <div className="channel-card-type">
                                                {
                                                    getChannelTypeLabel(
                                                        channel.type,
                                                    )
                                                }
                                            </div>
                                        </div>
                                    </div>

                                    <span
                                        className={`channel-status ${getChannelStatusClass(
                                            channel.status,
                                        )}`}
                                    >
                                        {
                                            getChannelStatusLabel(
                                                channel.status,
                                            )
                                        }
                                    </span>
                                </div>

                                <div className="channel-card-account">
                                    <div className="channel-card-account-title">
                                        Аккаунт
                                    </div>

                                    {channel.account ? (
                                        <div className="channel-card-account-info">
                                            {channel.account.displayName && (
                                                <div className="channel-card-account-name">
                                                    {
                                                        channel
                                                            .account
                                                            .displayName
                                                    }
                                                </div>
                                            )}

                                            {channel.account.username && (
                                                <div className="channel-card-account-username">
                                                    @
                                                    {
                                                        channel
                                                            .account
                                                            .username
                                                    }
                                                </div>
                                            )}

                                            {channel.account.phone && (
                                                <div className="channel-card-account-phone">
                                                    {
                                                        channel
                                                            .account
                                                            .phone
                                                    }
                                                </div>
                                            )}
                                        </div>
                                    ) : (
                                        <div className="channel-card-account-empty">
                                            Аккаунт не подключён
                                        </div>
                                    )}
                                </div>

                                <div className="channel-card-actions">
                                    <button
                                        type="button"
                                        className="ui-button ui-button-secondary"
                                        onClick={() =>
                                            setSelectedChannel(
                                                channel,
                                            )
                                        }
                                    >
                                        Подробнее
                                    </button>

                                    {channel.status ===
                                        'CREATED' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-primary"
                                                onClick={() =>
                                                    setConnectionChannel(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Подключить
                                            </button>
                                        )}

                                    {channel.status ===
                                        'DISCONNECTED' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-primary"
                                                onClick={() =>
                                                    setConnectionChannel(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Подключить
                                            </button>
                                        )}

                                    {channel.status ===
                                        'ERROR' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-primary"
                                                onClick={() =>
                                                    setConnectionChannel(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Подключить снова
                                            </button>
                                        )}

                                    {channel.status ===
                                        'CONNECTING' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-primary"
                                                onClick={() =>
                                                    setConnectionChannel(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Продолжить подключение
                                            </button>
                                        )}

                                    {channel.status ===
                                        'CONNECTED' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-secondary"
                                                onClick={() =>
                                                    handleDisable(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Отключить
                                            </button>
                                        )}

                                    {channel.status ===
                                        'DISABLED' && (
                                            <button
                                                type="button"
                                                className="ui-button ui-button-primary"
                                                onClick={() =>
                                                    handleEnable(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Включить
                                            </button>
                                        )}

                                    {isSuperAdmin && (
                                        <>
                                            <button
                                                type="button"
                                                className="ui-button ui-button-secondary"
                                                onClick={() =>
                                                    setEditChannel(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Изменить
                                            </button>

                                            <button
                                                type="button"
                                                className="ui-button ui-button-danger"
                                                onClick={() =>
                                                    setDeleteChannelTarget(
                                                        channel,
                                                    )
                                                }
                                            >
                                                Удалить
                                            </button>
                                        </>
                                    )}
                                </div>
                            </div>
                        ),
                    )}
                </div>
            )}

            <CreateChannelDialog
                open={createOpen}
                workspaceId={
                    currentWorkspace.id
                }
                onClose={() =>
                    setCreateOpen(false)
                }
                onCreated={handleCreated}
            />

            <ChannelDetailsDialog
                channel={selectedChannel}
                workspaceName={
                    currentWorkspace?.name ??
                    null
                }
                onClose={() =>
                    setSelectedChannel(
                        null,
                    )
                }
            />

            <EditChannelDialog
                channel={editChannel}
                onClose={() =>
                    setEditChannel(null)
                }
                onUpdated={handleUpdated}
            />

            <DeleteChannelDialog
                channel={
                    deleteChannelTarget
                }
                onClose={() =>
                    setDeleteChannelTarget(
                        null,
                    )
                }
                onConfirm={handleDelete}
            />

            <ChannelConnectionDialog
                channel={
                    connectionChannel
                }
                onClose={
                    handleConnectionClose
                }
                onConnected={
                    handleConnected
                }
            />
        </div>
    )
}

export default ChannelsPage