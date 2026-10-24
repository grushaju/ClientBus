import {
    useEffect,
    useState,
} from 'react'

import {
    getMessageAttachmentBlob,
    getMessageAttachments,
} from '../../api/messageApi'
import { useUiLifetime } from '../../api/events/useUiLifetime'

import type {
    MessageAttachmentDto,
    MessageDto,
} from '../../api/types/message'

import ImageViewer from './ImageViewer'

interface MessageBubbleProps {
    message: MessageDto
    onRetry?: (messageId: string) => Promise<void>
    retrying?: boolean
}

function MessageBubble({
                           message,
                           onRetry,
                           retrying = false,
                       }: MessageBubbleProps) {
    const [retryError, setRetryError] = useState<string | null>(null)
    const retryable = message.deliveryStatus === 'FAILED' && message.processingStatus === 'PROCESSED'
    const isOutbound =
        message.direction === 'OUTBOUND'

    const isSystem =
        message.senderType === 'SYSTEM'

    const className = [
        'message-row',
        isOutbound ? 'outbound' : 'inbound',
        isSystem ? 'system' : '',
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
                                message.type,
                            )}
                        </div>
                    )}

                <div className="message-meta">
                    <time
                        dateTime={
                            message.sentAt ??
                            message.createdAt ?? undefined
                        }
                    >
                        {formatMessageTime(
                            message.sentAt ??
                            message.createdAt,
                        )}
                    </time>

                    {isOutbound && (
                        <span className="message-delivery-status">
                            {getDeliveryLabel(message)}
                        </span>
                    )}
                </div>
                {isOutbound && retryable && onRetry && <div className="message-retry">
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={retrying}
                        onClick={async () => {
                            setRetryError(null)
                            try { await onRetry(message.id) }
                            catch (err) { setRetryError(err instanceof Error ? err.message : 'Не удалось повторить отправку') }
                        }}>{retrying ? 'Повторная отправка…' : 'Повторить отправку'}</button>
                    {retryError && <span role="alert">{retryError}</span>}
                </div>}
            </div>
        </div>
    )
}

interface MessageAttachmentsProps {
    message: MessageDto
}

function MessageAttachments({
                                message,
                            }: MessageAttachmentsProps) {
    const lifetime = useUiLifetime('message-attachments:' + message.id)
    const [result, setResult] = useState(() => ({ owner: lifetime,
        attachments: [] as MessageAttachmentDto[], loading: true, error: null as string | null }))
    const { attachments, loading, error } = result.owner === lifetime ? result
        : { attachments: [], loading: true, error: null }

    useEffect(() => {
        let cancelled = false
        const controller = new AbortController()
        const allowed = lifetime.capture()

        async function loadAttachments() {
            setResult({ owner: lifetime, attachments: [], loading: true, error: null })

            try {
                const result =
                    await lifetime.read(signal => getMessageAttachments(message.id, signal), controller.signal)

                if (!cancelled && allowed()) {
                    setResult({ owner: lifetime, attachments: result, loading: false, error: null })
                }
            } catch (err) {
                if (!cancelled && allowed()) {
                    setResult({ owner: lifetime, attachments: [], loading: false,
                        error: err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить вложения',
                    })
                }
            }
        }

        void loadAttachments()

        return () => {
            cancelled = true
            controller.abort()
        }
    }, [message.id, lifetime])

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
                Не удалось загрузить вложения
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
                               attachment,
                           }: MessageAttachmentProps) {
    const lifetime = useUiLifetime('message-attachment:' + messageId + ':' + attachment.id)
    const [result, setResult] = useState(() => ({ owner: lifetime, url: null as string | null, error: false }))
    const { url, error } = result.owner === lifetime ? result : { url: null, error: false }

    const [
        viewerOpen,
        setViewerOpen,
    ] = useState(false)

    useEffect(() => {
        let cancelled = false
        const controller = new AbortController()
        const allowed = lifetime.capture()
        let objectUrl: string | null = null
        setResult({ owner: lifetime, url: null, error: false })
        setViewerOpen(false)

        async function loadAttachment() {
            try {
                const blob =
                    await lifetime.read(signal => getMessageAttachmentBlob(messageId, attachment.id, signal), controller.signal)

                if (cancelled || !allowed()) {
                    return
                }

                objectUrl =
                    URL.createObjectURL(blob)

                setResult({ owner: lifetime, url: objectUrl, error: false })
            } catch {
                if (!cancelled && allowed()) {
                    setResult({ owner: lifetime, url: null, error: true })
                }
            }
        }

        void loadAttachment()

        return () => {
            cancelled = true
            controller.abort()

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [
        messageId,
        attachment.id,
        lifetime,
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
            <>
                <button
                    type="button"
                    className="message-attachment-image-button"
                    onClick={() => setViewerOpen(true)}
                    aria-label={`Открыть ${attachment.fileName}`}
                >
                    <img
                        className="message-attachment-image"
                        src={url}
                        alt={attachment.fileName}
                        loading="lazy"
                    />
                </button>

                {viewerOpen && (
                    <ImageViewer
                        src={url}
                        alt={attachment.fileName}
                        onClose={() =>
                            setViewerOpen(false)
                        }
                    />
                )}
            </>
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
    value: string | null | undefined,
): string {
    if (!value) return ''
    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return ''
    }

    return date.toLocaleTimeString(
        'ru-RU',
        {
            hour: '2-digit',
            minute: '2-digit',
        },
    )
}

function getMessageTypeLabel(
    type: MessageDto['type'],
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
    message: MessageDto,
): string {
    if (message.processingStatus === 'FAILED') return 'Ошибка'
    switch (message.deliveryStatus) {
        case 'READ':
            return 'Прочитано'

        case 'DELIVERED':
            return 'Доставлено'

        case 'SENT':
            return 'Отправлено'

        case 'FAILED':
            return 'Ошибка'

        case 'PENDING':
            return 'Отправляется'

        default:
            return ''
    }
}

export default MessageBubble
