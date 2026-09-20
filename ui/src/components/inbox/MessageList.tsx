import {
    useLayoutEffect,
    useRef,
} from 'react'

import type { MessageDto } from '../../api/types/message'

import MessageBubble from './MessageBubble'

interface Props {
    messages: MessageDto[]
    loading: boolean
    loadingOlder: boolean
    hasMore: boolean
    error: string | null
    onLoadOlder: () => Promise<void>
}

function MessageList({
                         messages,
                         loading,
                         loadingOlder,
                         hasMore,
                         error,
                         onLoadOlder,
                     }: Props) {
    const containerRef =
        useRef<HTMLDivElement | null>(null)

    const initialScrollDoneRef =
        useRef(false)

    const previousMessageCountRef =
        useRef(0)

    const preserveScrollRef =
        useRef<{
            scrollHeight: number
            scrollTop: number
        } | null>(null)

    useLayoutEffect(() => {
        const container =
            containerRef.current

        if (!container) {
            return
        }

        const previousCount =
            previousMessageCountRef.current

        const currentCount =
            messages.length

        /*
         * Первоначальная загрузка:
         * показываем последние сообщения.
         */
        if (
            !initialScrollDoneRef.current &&
            currentCount > 0
        ) {
            container.scrollTop =
                container.scrollHeight

            initialScrollDoneRef.current =
                true
        }

        /*
         * После prepend старых сообщений
         * сохраняем визуальную позицию пользователя.
         */
        const preservedScroll =
            preserveScrollRef.current

        if (
            preservedScroll &&
            currentCount > previousCount
        ) {
            const heightDelta =
                container.scrollHeight -
                preservedScroll.scrollHeight

            container.scrollTop =
                preservedScroll.scrollTop +
                heightDelta

            preserveScrollRef.current =
                null
        }

        previousMessageCountRef.current =
            currentCount
    }, [messages])

    /*
     * При смене conversationId MessageList
     * фактически получает новый набор сообщений.
     *
     * Если сообщений стало 0, разрешаем
     * следующей загрузке снова сделать
     * initial scroll вниз.
     */
    if (loading) {
        return (
            <div className="message-list message-list-state">
                Загрузка сообщений…
            </div>
        )
    }

    if (error && messages.length === 0) {
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

    const handleScroll = async () => {
        const container =
            containerRef.current

        if (!container) {
            return
        }

        const threshold = 120

        if (
            container.scrollTop <=
            threshold
        ) {
            if (
                loadingOlder ||
                !hasMore
            ) {
                return
            }

            preserveScrollRef.current = {
                scrollHeight:
                    container.scrollHeight,
                scrollTop:
                    container.scrollTop,
            }

            await onLoadOlder()
        }
    }

    return (
        <div
            ref={containerRef}
            className="message-list"
            onScroll={() => {
                void handleScroll()
            }}
        >
            {loadingOlder && (
                <div className="message-list-loading-older">
                    Загрузка старых сообщений…
                </div>
            )}

            {messages.map(
                message => (
                    <MessageBubble
                        key={message.id}
                        message={message}
                    />
                ),
            )}
        </div>
    )
}

export default MessageList
