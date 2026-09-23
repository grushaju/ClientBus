import type {
    ChannelDto,
} from '../../api/types/channel'

import {
    getChannelTypeConfig,
} from './channelTypeConfig'

import {
    TelegramConnectionFlow,
} from './TelegramConnectionFlow'

interface ChannelConnectionDialogProps {
    channel: ChannelDto | null
    onClose: () => void
    onConnected: (channel: ChannelDto) => void
}

export function ChannelConnectionDialog({
                                            channel,
                                            onClose,
                                            onConnected,
                                        }: ChannelConnectionDialogProps) {
    if (!channel) {
        return null
    }

    const config =
        getChannelTypeConfig(channel.type)

    let flow: React.ReactNode

    switch (config.authorization) {
        case 'TELEGRAM':
            flow = (
                <TelegramConnectionFlow
                    channel={channel}
                    onClose={onClose}
                    onConnected={onConnected}
                />
            )
            break

        case 'NONE':
        default:
            flow = (
                <>
                    <div className="channel-form">
                        <div className="channel-form-hint">
                            Для этого типа канала отдельная
                            авторизация не требуется.
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
                </>
            )
            break
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
                    <div>
                        <h2>
                            Подключение канала
                        </h2>

                        <div className="channel-modal-subtitle">
                            {channel.name}
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

                {flow}
            </div>
        </div>
    )
}