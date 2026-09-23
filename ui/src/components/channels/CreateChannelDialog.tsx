import {
    useEffect,
    useMemo,
    useState,
} from 'react'

import {
    createChannel,
} from '../../api/channelApi'

import type {
    ChannelDto,
    ChannelType,
    CreateChannelRequest,
} from '../../api/types/channel'

import {
    getChannelTypeConfig,
} from './channelTypeConfig'

interface CreateChannelDialogProps {
    open: boolean
    workspaceId: string
    onClose: () => void
    onCreated: (channel: ChannelDto) => Promise<void> | void
}

const channelTypes: {
    value: ChannelType
    label: string
}[] = [
    {
        value: 'WHATSAPP',
        label: 'WhatsApp',
    },
    {
        value: 'TELEGRAM',
        label: 'Telegram',
    },
    {
        value: 'MAX',
        label: 'MAX',
    },
    {
        value: 'VK',
        label: 'VK',
    },
    {
        value: 'AVITO',
        label: 'Avito',
    },
    {
        value: 'DIKIDI',
        label: 'Dikidi',
    },
    {
        value: 'WHATSAPP_BUSINESS',
        label: 'WhatsApp Business',
    },
    {
        value: 'TELEGRAM_BOT',
        label: 'Telegram Bot',
    },
    {
        value: 'MAX_BOT',
        label: 'MAX Bot',
    },
    {
        value: 'VK_BOT',
        label: 'VK Bot',
    },
]

export function CreateChannelDialog({
                                        open,
                                        workspaceId,
                                        onClose,
                                        onCreated,
                                    }: CreateChannelDialogProps) {
    const [name, setName] = useState('')
    const [type, setType] =
        useState<ChannelType>('TELEGRAM')

    const [externalId, setExternalId] = useState('')
    const [username, setUsername] = useState('')
    const [phone, setPhone] = useState('')
    const [displayName, setDisplayName] = useState('')

    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const config = useMemo(
        () => getChannelTypeConfig(type),
        [type],
    )

    useEffect(() => {
        if (!open) {
            return
        }

        setName('')
        setType('TELEGRAM')
        setExternalId('')
        setUsername('')
        setPhone('')
        setDisplayName('')
        setSaving(false)
        setError(null)
    }, [open])

    if (!open) {
        return null
    }

    function getFieldValue(
        field:
            | 'externalId'
            | 'username'
            | 'phone'
            | 'displayName',
    ): string {
        switch (field) {
            case 'externalId':
                return externalId

            case 'username':
                return username

            case 'phone':
                return phone

            case 'displayName':
                return displayName
        }
    }

    function setFieldValue(
        field:
            | 'externalId'
            | 'username'
            | 'phone'
            | 'displayName',
        value: string,
    ) {
        switch (field) {
            case 'externalId':
                setExternalId(value)
                break

            case 'username':
                setUsername(value)
                break

            case 'phone':
                setPhone(value)
                break

            case 'displayName':
                setDisplayName(value)
                break
        }
    }

    async function handleSubmit(
        event: React.FormEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        const normalizedName = name.trim()

        if (!normalizedName) {
            setError('Введите название канала')
            return
        }

        for (const [
            field,
            fieldConfig,
        ] of Object.entries(config.accountFields)) {
            if (!fieldConfig.required) {
                continue
            }

            const value = getFieldValue(
                field as
                    | 'externalId'
                    | 'username'
                    | 'phone'
                    | 'displayName',
            ).trim()

            if (!value) {
                setError(
                    `Заполните поле «${fieldConfig.label}»`,
                )
                return
            }
        }

        const account: CreateChannelRequest['account'] = {}

        if (externalId.trim()) {
            account.externalId = externalId.trim()
        }

        if (username.trim()) {
            account.username = username.trim()
        }

        if (phone.trim()) {
            account.phone = phone.trim()
        }

        if (displayName.trim()) {
            account.displayName = displayName.trim()
        }

        const request: CreateChannelRequest = {
            workspaceId,
            type,
            name: normalizedName,
            account,
        }

        setSaving(true)
        setError(null)

        try {
            const created =
                await createChannel(request)

            await onCreated(created)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать канал',
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
                    <h2>Добавить канал</h2>

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
                            placeholder="Например, Основной Telegram"
                            disabled={saving}
                            autoFocus
                        />
                    </label>

                    <label className="channel-form-field">
                        <span>
                            Тип канала
                            <span className="channel-form-required">
                                {' '}
                                *
                            </span>
                        </span>

                        <select
                            value={type}
                            onChange={(event) =>
                                setType(
                                    event.target
                                        .value as ChannelType,
                                )
                            }
                            disabled={saving}
                        >
                            {channelTypes.map(
                                (channelType) => (
                                    <option
                                        key={
                                            channelType.value
                                        }
                                        value={
                                            channelType.value
                                        }
                                    >
                                        {
                                            channelType.label
                                        }
                                    </option>
                                ),
                            )}
                        </select>
                    </label>

                    {(
                        Object.entries(
                            config.accountFields,
                        ) as [
                            keyof typeof config.accountFields,
                            (typeof config.accountFields)[keyof typeof config.accountFields],
                        ][]
                    )
                        .filter(
                            ([, fieldConfig]) =>
                                fieldConfig.editable,
                        )
                        .map(
                            ([
                                 field,
                                 fieldConfig,
                             ]) => (
                                <label
                                    key={field}
                                    className="channel-form-field"
                                >
                                    <span>
                                        {
                                            fieldConfig.label
                                        }

                                        {fieldConfig.required && (
                                            <span className="channel-form-required">
                                                {' '}
                                                *
                                            </span>
                                        )}
                                    </span>

                                    <input
                                        type={
                                            field ===
                                            'phone'
                                                ? 'tel'
                                                : 'text'
                                        }
                                        value={getFieldValue(
                                            field,
                                        )}
                                        onChange={(
                                            event,
                                        ) =>
                                            setFieldValue(
                                                field,
                                                event
                                                    .target
                                                    .value,
                                            )
                                        }
                                        disabled={saving}
                                    />
                                </label>
                            ),
                        )}

                    {type === 'TELEGRAM' && (
                        <div className="channel-form-hint">
                            После создания Telegram
                            попросит подтвердить
                            авторизацию кодом из Telegram.
                            При необходимости также
                            будет запрошен пароль
                            двухфакторной авторизации.
                        </div>
                    )}

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
                                ? 'Создание…'
                                : 'Создать'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}