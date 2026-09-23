import {
    useState,
} from 'react'

import type {
    ChannelDto,
} from '../../api/types/channel'

interface DeleteChannelDialogProps {
    channel: ChannelDto | null
    onClose: () => void
    onConfirm: () => Promise<void> | void
}

export function DeleteChannelDialog({
                                        channel,
                                        onClose,
                                        onConfirm,
                                    }: DeleteChannelDialogProps) {
    const [deleting, setDeleting] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    if (!channel) {
        return null
    }

    async function handleConfirm() {
        setDeleting(true)
        setError(null)

        try {
            await onConfirm()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить канал',
            )
        } finally {
            setDeleting(false)
        }
    }

    return (
        <div
            className="channel-modal-overlay"
            onMouseDown={(event) => {
                if (
                    event.target ===
                    event.currentTarget &&
                    !deleting
                ) {
                    onClose()
                }
            }}
        >
            <div className="channel-modal">
                <div className="channel-modal-header">
                    <h2>Удалить канал?</h2>

                    <button
                        type="button"
                        className="channel-modal-close"
                        onClick={onClose}
                        disabled={deleting}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <div className="channel-delete-content">
                    <p>
                        Канал{' '}
                        <strong>
                            {channel.name}
                        </strong>{' '}
                        будет удалён.
                    </p>

                    <p>
                        Это действие нельзя отменить.
                    </p>

                    {error && (
                        <div className="channel-form-error">
                            {error}
                        </div>
                    )}
                </div>

                <div className="channel-modal-actions">
                    <button
                        type="button"
                        className="channel-button channel-button-secondary"
                        onClick={onClose}
                        disabled={deleting}
                    >
                        Отмена
                    </button>

                    <button
                        type="button"
                        className="channel-button channel-button-danger"
                        onClick={() =>
                            void handleConfirm()
                        }
                        disabled={deleting}
                    >
                        {deleting
                            ? 'Удаление…'
                            : 'Удалить'}
                    </button>
                </div>
            </div>
        </div>
    )
}