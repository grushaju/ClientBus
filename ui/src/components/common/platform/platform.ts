export interface PlatformDefinition {
    key: string
    name: string
}

const PLATFORM_DEFINITIONS: Record<string, PlatformDefinition> = {
    WHATSAPP: {
        key: 'WHATSAPP',
        name: 'WhatsApp',
    },

    WHATSAPP_BUSINESS: {
        key: 'WHATSAPP_BUSINESS',
        name: 'WhatsApp',
    },

    TELEGRAM: {
        key: 'TELEGRAM',
        name: 'Telegram',
    },

    TELEGRAM_BOT: {
        key: 'TELEGRAM_BOT',
        name: 'Telegram',
    },

    MAX: {
        key: 'MAX',
        name: 'MAX',
    },

    MAX_BOT: {
        key: 'MAX_BOT',
        name: 'MAX',
    },

    VK: {
        key: 'VK',
        name: 'VK',
    },

    VK_BOT: {
        key: 'VK_BOT',
        name: 'VK',
    },

    AVITO: {
        key: 'AVITO',
        name: 'Avito',
    },

    DIKIDI: {
        key: 'DIKIDI',
        name: 'Dikidi',
    },
}

export function getPlatformDefinition(
    type: string | null | undefined,
): PlatformDefinition | null {
    if (!type) {
        return null
    }

    return (
        PLATFORM_DEFINITIONS[type.toUpperCase()] ??
        null
    )
}

export function getPlatformName(
    type: string | null | undefined,
): string {
    return (
        getPlatformDefinition(type)?.name ??
        type ??
        'Unknown'
    )
}