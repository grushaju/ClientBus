import type {
    ChannelDto,
} from '../../api/types/channel'

import {
    getChannelStatusLabel,
    getChannelTypeLabel,
} from './channelLabels'

import PlatformIcon from '../common/platform/PlatformIcon'

interface ChannelDetailsDialogProps {
    channel: ChannelDto | null
    workspaceName: string | null
    onClose: () => void
}

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

export function ChannelDetailsDialog({
                                         channel,
                                         workspaceName,
                                         onClose,
                                     }: ChannelDetailsDialogProps) {
    if (!channel) {
        return null
    }

    const account = channel.account

    const accountName =
        account?.displayName ||
        (account?.username
            ? `@${account.username}`
            : null)

    return (
        <div
            className="channel-modal-overlay"
            onMouseDown={(event) => {
                if (
                    event.target ===
                    event.currentTarget
                ) {
                    onClose()
                }
            }}
        >
            <div className="channel-modal channel-details-modal">
                <div className="channel-modal-header">
                    <div className="channel-details-title">
                        <div className="channel-details-icon">
                            <PlatformIcon
                                type={channel.type}
                                size={28}
                            />
                        </div>

                        <div className="channel-details-heading">
                            <div className="channel-details-heading-row">
                                <h2>{channel.name}</h2>

                                <span
                                    className={`channel-status ${getChannelStatusClass(
                                        channel.status,
                                    )}`}
                                >
                                    {getChannelStatusLabel(
                                        channel.status,
                                    )}
                                </span>
                            </div>

                            <div className="channel-details-type">
                                {getChannelTypeLabel(
                                    channel.type,
                                )}
                            </div>
                        </div>
                    </div>

                    <button
                        type="button"
                        className="channel-modal-close"
                        onClick={onClose}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <div className="channel-details">
                    <div className="channel-details-section">
                        <div className="channel-details-section-title">
                            Рабочее пространство
                        </div>

                        <div className="channel-details-value">
                            {workspaceName ?? '-'}
                        </div>
                    </div>

                    <div className="channel-details-divider" />

                    <div className="channel-details-section">
                        <div className="channel-details-section-title">
                            Аккаунт
                        </div>

                        {account ? (
                            <div className="channel-details-account">
                                {accountName && (
                                    <div className="channel-details-account-name">
                                        {accountName}
                                    </div>
                                )}

                                {account.username &&
                                    accountName !==
                                    `@${account.username}` && (
                                        <div className="channel-details-account-row">
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
                                    <div className="channel-details-account-row">
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

                                {!accountName &&
                                    !account.phone && (
                                        <div className="channel-details-account-empty">
                                            Аккаунт подключён
                                        </div>
                                    )}
                            </div>
                        ) : (
                            <div className="channel-details-account-empty">
                                Аккаунт не подключён
                            </div>
                        )}
                    </div>
                </div>

                <div className="channel-modal-actions">
                    <button
                        type="button"
                        className="channel-button channel-button-secondary"
                        onClick={onClose}
                    >
                        Закрыть
                    </button>
                </div>
            </div>
        </div>
    )
}