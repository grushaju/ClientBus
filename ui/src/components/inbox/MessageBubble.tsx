import {
    useEffect,
    useState
} from 'react'

import {
    getMessageAttachmentBlob,
    getMessageAttachments
} from '../../api/messageApi'

import type {
    MessageAttachmentDto,
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

    const shouldLoadAttachments =
        message.type === 'IMAGE' ||
        message.type === 'AUDIO'

    return (
        <div className={className}>
            <div className="message-bubble">

                {message.content && (
                    <div className="message-content">
                        {message.content}
                    </div>
                )}

                {shouldLoadAttachments && (
                    <MessageAttachments
                        message={message}
                    />
                )}

                {!message.content &&
                    !shouldLoadAttachments &&
                    message.type !== 'TEXT' && (
                        <div className="message-type">
                            {getMessageTypeLabel(
                                message.type
                            )}
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

interface MessageAttachmentsProps {
    message: MessageDto
}

function MessageAttachments({
                                message
                            }: MessageAttachmentsProps) {
    const [
        attachments,
        setAttachments
    ] = useState<MessageAttachmentDto[]>([])

    const [
        loading,
        setLoading
    ] = useState(true)

    const [
        error,
        setError
    ] = useState<string | null>(null)

    useEffect(() => {
        let cancelled = false

        async function loadAttachments() {
            setLoading(true)
            setError(null)

            try {
                const result =
                    await getMessageAttachments(
                        message.id
                    )

                if (!cancelled) {
                    setAttachments(result)
                }
            } catch (err) {
                if (!cancelled) {
                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить вложение'
                    )
                }
            } finally {
                if (!cancelled) {
                    setLoading(false)
                }
            }
        }

        void loadAttachments()

        return () => {
            cancelled = true
        }
    }, [message.id])

    if (loading) {
        return (
            <div className="message-attachments-loading">
                Загрузка…
            </div>
        )
    }

    if (error) {
        return (
            <div className="message-attachments-error">
                Не удалось загрузить вложение
            </div>
        )
    }

    if (attachments.length === 0) {
        return null
    }

    return (
        <div className="message-attachments">
            {attachments.map(attachment => (
                <MessageAttachment
                    key={attachment.id}
                    messageId={message.id}
                    attachment={attachment}
                />
            ))}
        </div>
    )
}

interface MessageAttachmentProps {
    messageId: string
    attachment: MessageAttachmentDto
}

function MessageAttachment({
                               messageId,
                               attachment
                           }: MessageAttachmentProps) {
    const [
        url,
        setUrl
    ] = useState<string | null>(null)

    const [
        error,
        setError
    ] = useState(false)

    useEffect(() => {
        let cancelled = false
        let objectUrl: string | null = null

        async function loadAttachment() {
            try {
                const blob =
                    await getMessageAttachmentBlob(
                        messageId,
                        attachment.id
                    )

                if (cancelled) {
                    return
                }

                objectUrl =
                    URL.createObjectURL(blob)

                setUrl(objectUrl)
            } catch {
                if (!cancelled) {
                    setError(true)
                }
            }
        }

        void loadAttachment()

        return () => {
            cancelled = true

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [
        messageId,
        attachment.id
    ])

    if (error) {
        return (
            <div className="message-attachment-error">
                Не удалось загрузить файл
            </div>
        )
    }

    if (!url) {
        return (
            <div className="message-attachment-loading">
                Загрузка…
            </div>
        )
    }

    if (attachment.type === 'IMAGE') {
        return (
            <img
                className="message-attachment-image"
                src={url}
                alt={attachment.fileName}
                loading="lazy"
            />
        )
    }

    if (attachment.type === 'AUDIO') {
        return (
            <div className="message-attachment-audio">
                <audio
                    controls
                    preload="metadata"
                    src={url}
                />

                <div className="message-attachment-file-name">
                    {attachment.fileName}
                </div>
            </div>
        )
    }

    return null
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

function getMessageTypeLabel(
    type: MessageDto['type']
): string {
    switch (type) {
        case 'VIDEO':
            return 'Видео'

        case 'DOCUMENT':
            return 'Документ'

        case 'STICKER':
            return 'Стикер'

        case 'LOCATION':
            return 'Геопозиция'

        case 'CONTACT':
            return 'Контакт'

        case 'SYSTEM':
            return 'Системное сообщение'

        default:
            return type
    }
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