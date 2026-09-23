import {
    useEffect,
    useState,
} from 'react'

import {
    updateChannel,
} from '../../api/channelApi'

import type {
    ChannelDto,
} from '../../api/types/channel'

interface EditChannelDialogProps {
    channel: ChannelDto | null
    onClose: () => void
    onUpdated: (channel: ChannelDto) => Promise<void> | void
}

export function EditChannelDialog({
                                      channel,
                                      onClose,
                                      onUpdated,
                                  }: EditChannelDialogProps) {
    const [name, setName] = useState('')
    const [saving, setSaving] = useState(false)
    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (!channel) {
            return
        }

        setName(channel.name)
        setError(null)
        setSaving(false)
    }, [channel])

    if (!channel) {
        return null
    }

    async function handleSubmit(
        event: React.FormEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!channel) {
            return
        }

        const normalizedName = name.trim()

        if (!normalizedName) {
            setError('Введите название канала')
            return
        }

        setSaving(true)
        setError(null)

        try {
            const updated =
                await updateChannel(
                    channel.id,
                    {
                        name: normalizedName,
                    },
                )

            await onUpdated(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось изменить канал',
            )
        } finally {
            setSaving(false)
        }
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
                    <h2>Изменить канал</h2>

                    <button
                        type="button"
                        className="channel-modal-close"
                        onClick={onClose}
                        disabled={saving}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <form
                    className="channel-form"
                    onSubmit={handleSubmit}
                >
                    <label className="channel-form-field">
                        <span>
                            Название
                            <span className="channel-form-required">
                                {' '}
                                *
                            </span>
                        </span>

                        <input
                            type="text"
                            value={name}
                            onChange={(event) =>
                                setName(
                                    event.target.value,
                                )
                            }
                            disabled={saving}
                            autoFocus
                        />
                    </label>

                    {error && (
                        <div className="channel-form-error">
                            {error}
                        </div>
                    )}

                    <div className="channel-modal-actions">
                        <button
                            type="button"
                            className="channel-button channel-button-secondary"
                            onClick={onClose}
                            disabled={saving}
                        >
                            Отмена
                        </button>

                        <button
                            type="submit"
                            className="channel-button channel-button-primary"
                            disabled={saving}
                        >
                            {saving
                                ? 'Сохранение…'
                                : 'Сохранить'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}