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

function getMessageDate(
    message: MessageDto,
): string {
    const value =
        message.sentAt ??
        message.createdAt

    const date =
        new Date(value)

    if (Number.isNaN(date.getTime())) {
        return ''
    }

    return [
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
    ].join('-')
}

function formatMessageDate(
    dateKey: string,
): string {
    if (!dateKey) {
        return ''
    }

    const [
        year,
        month,
        day,
    ] = dateKey
        .split('-')
        .map(Number)

    const date =
        new Date(
            year,
            month,
            day,
        )

    const today =
        new Date()

    const todayKey = [
        today.getFullYear(),
        today.getMonth(),
        today.getDate(),
    ].join('-')

    if (dateKey === todayKey) {
        return 'Сегодня'
    }

    const yesterday =
        new Date(
            today.getFullYear(),
            today.getMonth(),
            today.getDate() - 1,
        )

    const yesterdayKey = [
        yesterday.getFullYear(),
        yesterday.getMonth(),
        yesterday.getDate(),
    ].join('-')

    if (dateKey === yesterdayKey) {
        return 'Вчера'
    }

    return date.toLocaleDateString(
        'ru-RU',
        {
            day: 'numeric',
            month: 'long',
            year: 'numeric',
        },
    )
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
                (message, index) => {
                    const messageDate =
                        getMessageDate(message)

                    const previousMessage =
                        index > 0
                            ? messages[index - 1]
                            : null

                    const previousDate =
                        previousMessage
                            ? getMessageDate(
                                previousMessage,
                            )
                            : null

                    const showDateSeparator =
                        messageDate !== previousDate

                    return (
                        <div key={message.id}>
                            {showDateSeparator && (
                                <div className="message-date-separator">
                        <span>
                            {formatMessageDate(
                                messageDate,
                            )}
                        </span>
                                </div>
                            )}

                            <MessageBubble
                                message={message}
                            />
                        </div>
                    )
                },
            )}
        </div>
    )
}

export default MessageList
