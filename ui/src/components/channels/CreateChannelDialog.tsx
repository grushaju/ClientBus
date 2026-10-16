import {
    useEffect,
    useMemo,
    useRef,
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

const channelIconFiles: Record<ChannelType, string> = {
    WHATSAPP: 'whatsapp.svg',
    TELEGRAM: 'telegram.svg',
    MAX: 'max.svg',
    VK: 'vk.svg',
    AVITO: 'avito.svg',
    DIKIDI: 'dikidi.svg',
    WHATSAPP_BUSINESS: 'whatsapp.svg',
    TELEGRAM_BOT: 'telegram.svg',
    MAX_BOT: 'max.svg',
    VK_BOT: 'vk.svg',
}

export function CreateChannelDialog({
    open,
    workspaceId,
    onClose,
    onCreated,
}: CreateChannelDialogProps) {
    const [name, setName] = useState('')
    const [type, setType] = useState<ChannelType>('TELEGRAM')

    const [externalId, setExternalId] = useState('')
    const [username, setUsername] = useState('')
    const [phone, setPhone] = useState('')
    const [displayName, setDisplayName] = useState('')

    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [isTypeOpen, setIsTypeOpen] = useState(false)

    const typeSelectRef = useRef<HTMLDivElement>(null)

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
        setIsTypeOpen(false)
    }, [open])

    useEffect(() => {
        if (!isTypeOpen) {
            return
        }

        function handleMouseDown(event: MouseEvent) {
            if (
                typeSelectRef.current &&
                !typeSelectRef.current.contains(event.target as Node)
            ) {
                setIsTypeOpen(false)
            }
        }

        function handleKeyDown(event: KeyboardEvent) {
            if (event.key === 'Escape') {
                setIsTypeOpen(false)
            }
        }

        document.addEventListener('mousedown', handleMouseDown)
        document.addEventListener('keydown', handleKeyDown)

        return () => {
            document.removeEventListener('mousedown', handleMouseDown)
            document.removeEventListener('keydown', handleKeyDown)
        }
    }, [isTypeOpen])

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
            const created = await createChannel(request)

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

    const selectedChannelType = channelTypes.find(
        (channelType) => channelType.value === type,
    )

    return (
        <div
            className="channel-modal-overlay"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
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
                                {' '}*
                            </span>
                        </span>

                        <input
                            type="text"
                            value={name}
                            onChange={(event) =>
                                setName(event.target.value)
                            }
                            placeholder="Например, Основной Telegram"
                            disabled={saving}
                            autoFocus
                        />
                    </label>

                    <div className="channel-form-field">
                        <span id="channel-type-label">
                            Тип канала
                            <span className="channel-form-required">
                                {' '}*
                            </span>
                        </span>

                        <div
                            className="channel-type-select"
                            ref={typeSelectRef}
                        >
                            <button
                                type="button"
                                className="channel-type-select-trigger"
                                aria-labelledby="channel-type-label"
                                aria-haspopup="listbox"
                                aria-expanded={isTypeOpen}
                                disabled={saving}
                                onClick={() =>
                                    setIsTypeOpen((current) => !current)
                                }
                            >
                                <img
                                    className="channel-type-icon"
                                    src={`/assets/channel/${channelIconFiles[type]}`}
                                    alt=""
                                    width={24}
                                    height={24}
                                />

                                <span>
                                    {selectedChannelType?.label}
                                </span>

                                <span
                                    className={`channel-type-chevron${isTypeOpen ? ' is-open' : ''}`}
                                    aria-hidden="true"
                                >
                                    ▾
                                </span>
                            </button>

                            {isTypeOpen && (
                                <div
                                    className="channel-type-select-options"
                                    role="listbox"
                                    aria-labelledby="channel-type-label"
                                >
                                    {channelTypes.map((channelType) => (
                                        <button
                                            key={channelType.value}
                                            type="button"
                                            role="option"
                                            aria-selected={
                                                type === channelType.value
                                            }
                                            className={`channel-type-select-option${type === channelType.value ? ' is-selected' : ''}`}
                                            onClick={() => {
                                                setType(channelType.value)
                                                setIsTypeOpen(false)
                                            }}
                                        >
                                            <img
                                                className="channel-type-icon"
                                                src={`/assets/channel/${channelIconFiles[channelType.value]}`}
                                                alt=""
                                                width={24}
                                                height={24}
                                            />

                                            <span>
                                                {channelType.label}
                                            </span>

                                            {type === channelType.value && (
                                                <span
                                                    className="channel-type-check"
                                                    aria-hidden="true"
                                                >
                                                    ✓
                                                </span>
                                            )}
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>

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
                                        {fieldConfig.label}

                                        {fieldConfig.required && (
                                            <span className="channel-form-required">
                                                {' '}*
                                            </span>
                                        )}
                                    </span>

                                    <input
                                        type={
                                            field === 'phone'
                                                ? 'tel'
                                                : 'text'
                                        }
                                        value={getFieldValue(field)}
                                        onChange={(event) =>
                                            setFieldValue(
                                                field,
                                                event.target.value,
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
                            className="ui-button ui-button-secondary"
                            onClick={onClose}
                            disabled={saving}
                        >
                            Отмена
                        </button>

                        <button
                            type="submit"
                            className="ui-button ui-button-primary"
                            disabled={saving}
                        >
                            {saving ? 'Создание…' : 'Создать'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}
