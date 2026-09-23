import type { ChannelType, ChannelConnectionStatus } from '../../api/types/channel'

export function getChannelTypeLabel(
    type: ChannelType,
): string {
    switch (type) {
        case 'WHATSAPP':
            return 'WhatsApp'

        case 'TELEGRAM':
            return 'Telegram'

        case 'MAX':
            return 'MAX'

        case 'VK':
            return 'VK'

        case 'AVITO':
            return 'Avito'

        case 'DIKIDI':
            return 'Dikidi'

        case 'WHATSAPP_BUSINESS':
            return 'WhatsApp Business'

        case 'TELEGRAM_BOT':
            return 'Telegram Bot'

        case 'MAX_BOT':
            return 'MAX Bot'

        case 'VK_BOT':
            return 'VK Bot'
    }
}

export function getChannelStatusLabel(
    status: ChannelConnectionStatus,
): string {
    switch (status) {
        case 'CREATED':
            return 'Создан'

        case 'CONNECTING':
            return 'Подключение'

        case 'CONNECTED':
            return 'Подключён'

        case 'DISCONNECTED':
            return 'Отключён'

        case 'ERROR':
            return 'Ошибка'

        case 'DISABLED':
            return 'Отключён'
    }
}