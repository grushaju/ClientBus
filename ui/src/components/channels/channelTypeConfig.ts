import type {
    ChannelAccountField,
    ChannelType,
    ChannelTypeConfig,
} from '../../api/types/channel'

const allOptionalFields: Record<
    ChannelAccountField,
    {
        required: boolean
        editable: boolean
        label: string
    }
> = {
    externalId: {
        required: false,
        editable: true,
        label: 'External ID',
    },
    username: {
        required: false,
        editable: true,
        label: 'Username',
    },
    phone: {
        required: false,
        editable: true,
        label: 'Телефон',
    },
    displayName: {
        required: false,
        editable: true,
        label: 'Отображаемое имя',
    },
}

const telegramConfig: ChannelTypeConfig = {
    accountFields: {
        externalId: {
            required: false,
            editable: false,
            label: 'External ID',
        },
        username: {
            required: false,
            editable: false,
            label: 'Username',
        },
        phone: {
            required: true,
            editable: true,
            label: 'Телефон',
        },
        displayName: {
            required: false,
            editable: false,
            label: 'Отображаемое имя',
        },
    },
    authorization: 'TELEGRAM',
}

const defaultConfig: ChannelTypeConfig = {
    accountFields: allOptionalFields,
    authorization: 'NONE',
}

const configs: Partial<
    Record<ChannelType, ChannelTypeConfig>
> = {
    TELEGRAM: telegramConfig,
}

export function getChannelTypeConfig(
    type: ChannelType,
): ChannelTypeConfig {
    return configs[type] ?? defaultConfig
}