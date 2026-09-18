import type {
    MessageDto
} from '../../api/types/message'

interface MessageBubbleProps {
    message: MessageDto
}

function MessageBubble({
                           message
                       }: MessageBubbleProps) {
    const isOutbound =
        message.direction ===
        'OUTBOUND'

    const isSystem =
        message.senderType ===
        'SYSTEM'

    const className = [
        'message-row',
        isOutbound
            ? 'outbound'
            : 'inbound',
        isSystem
            ? 'system'
            : ''
    ]
        .filter(Boolean)
        .join(' ')

    return (
        <div className={className}>
            <div className="message-bubble">
                {message.content && (
                    <div className="message-content">
                        {message.content}
                    </div>
                )}

                {message.type !== 'TEXT' &&
                    !message.content && (
                        <div className="message-type">
                            {message.type}
                        </div>
                    )}

                <div className="message-meta">
                    <time>
                        {formatMessageTime(
                            message.sentAt ??
                            message.createdAt
                        )}
                    </time>

                    {isOutbound && (
                        <span>
                            {getDeliveryLabel(
                                message
                            )}
                        </span>
                    )}
                </div>
            </div>
        </div>
    )
}

function formatMessageTime(
    value: string
): string {
    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return ''
    }

    return date.toLocaleTimeString(
        'ru-RU',
        {
            hour: '2-digit',
            minute: '2-digit'
        }
    )
}

function getDeliveryLabel(
    message: MessageDto
): string {
    if (
        message.deliveryStatus ===
        'READ'
    ) {
        return 'Прочитано'
    }

    if (
        message.deliveryStatus ===
        'DELIVERED'
    ) {
        return 'Доставлено'
    }

    if (
        message.deliveryStatus ===
        'SENT'
    ) {
        return 'Отправлено'
    }

    if (
        message.deliveryStatus ===
        'FAILED'
    ) {
        return 'Ошибка'
    }

    return 'Отправляется'
}

export default MessageBubble