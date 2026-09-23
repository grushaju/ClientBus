import type {
    ChannelDto,
} from '../../api/types/channel'

interface ChannelDetailsDialogProps {
    channel: ChannelDto | null
    onClose: () => void
}

import {
    getChannelStatusLabel,
    getChannelTypeLabel,
} from './channelLabels'

export function ChannelDetailsDialog({
                                         channel,
                                         onClose,
                                     }: ChannelDetailsDialogProps) {
    if (!channel) {
        return null
    }

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
            <div className="channel-modal">
                <div className="channel-modal-header">
                    <h2>{channel.name}</h2>

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
                    <div className="channel-details-row">
                        <span>Тип</span>

                        <strong>
                            {getChannelTypeLabel(
                                channel.type,
                            )}
                        </strong>
                    </div>

                    <div className="channel-details-row">
                        <span>Статус</span>

                        <strong>
                            {getChannelStatusLabel(
                                channel.status,
                            )}
                        </strong>
                    </div>

                    <div className="channel-details-row">
                        <span>Workspace</span>

                        <strong>
                            {channel.workspaceId}
                        </strong>
                    </div>

                    {channel.account && (
                        <>
                            <div className="channel-details-divider" />

                            <div className="channel-details-section-title">
                                Аккаунт
                            </div>

                            <div className="channel-details-row">
                                <span>ID</span>

                                <strong>
                                    {
                                        channel.account
                                            .id
                                    }
                                </strong>
                            </div>

                            <div className="channel-details-row">
                                <span>External ID</span>

                                <strong>
                                    {
                                        channel.account
                                            .externalId
                                    }
                                </strong>
                            </div>

                            {channel.account.username && (
                                <div className="channel-details-row">
                                    <span>
                                        Username
                                    </span>

                                    <strong>
                                        @
                                        {
                                            channel
                                                .account
                                                .username
                                        }
                                    </strong>
                                </div>
                            )}

                            {channel.account.phone && (
                                <div className="channel-details-row">
                                    <span>
                                        Телефон
                                    </span>

                                    <strong>
                                        {
                                            channel
                                                .account
                                                .phone
                                        }
                                    </strong>
                                </div>
                            )}

                            {channel.account
                                .displayName && (
                                <div className="channel-details-row">
                                    <span>
                                        Имя
                                    </span>

                                    <strong>
                                        {
                                            channel
                                                .account
                                                .displayName
                                        }
                                    </strong>
                                </div>
                            )}
                        </>
                    )}
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