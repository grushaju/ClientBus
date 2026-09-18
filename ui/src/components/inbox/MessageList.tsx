import { useEffect, useRef } from 'react'
import type { MessageDto } from '../../api/types/message'
import MessageBubble from './MessageBubble'

interface Props {
    messages: MessageDto[]
    loading: boolean
    error: string | null
}

function MessageList({
                         messages,
                         loading,
                         error,
                     }: Props) {
    const containerRef = useRef<HTMLDivElement | null>(null)

    useEffect(() => {
        const container = containerRef.current

        if (!container) {
            return
        }

        container.scrollTop = container.scrollHeight
    }, [messages])

    if (loading) {
        return (
            <div className="message-list message-list-state">
                Загрузка сообщений…
            </div>
        )
    }

    if (error) {
        return (
            <div className="message-list message-list-state message-list-error">
                {error}
            </div>
        )
    }

    if (messages.length === 0) {
        return (
            <div className="message-list message-list-state">
                Сообщений пока нет
            </div>
        )
    }

    return (
        <div
            ref={containerRef}
            className="message-list"
        >
            {messages.map((message) => (
                <MessageBubble
                    key={message.id}
                    message={message}
                />
            ))}
        </div>
    )
}

export default MessageList