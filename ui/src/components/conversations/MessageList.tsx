import {
    useCallback,
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
    historyStartReached: boolean
    error: string | null
    onLoadOlder: (onStart?: () => void) => Promise<void>
    refreshError?: string | null
    onReload?: () => Promise<void>
    onRetryOlder?: (onStart?: () => void) => Promise<void>
    onRetryMessage?: (messageId: string) => Promise<void>
    retryingIds?: string[]
}

function getMessageDate(
    message: MessageDto,
): string {
    const value =
        message.sentAt ??
        message.createdAt

    if (!value) return ''

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
                         historyStartReached,
                         error,
                         onLoadOlder,
                         refreshError,
                         onReload,
                         onRetryOlder,
                         onRetryMessage,
                         retryingIds = [],
                     }: Props) {
    const containerRef =
        useRef<HTMLDivElement | null>(null)

    const initialScrollDoneRef =
        useRef(false)

    const atBottomRef = useRef(true)
    const previousLastId = useRef<string | undefined>(undefined)
    const loadingOlderRef = useRef(false)

    const preserveScrollRef =
        useRef<{
            scrollHeight: number
            scrollTop: number
        } | null>(null)

    const preserveScroll = useCallback(() => {
        const container = containerRef.current
        if (container) preserveScrollRef.current = {
            scrollHeight: container.scrollHeight, scrollTop: container.scrollTop,
        }
    }, [])

    useLayoutEffect(() => {
        const container =
            containerRef.current

        if (!container) {
            return
        }

        const currentCount =
            messages.length

        if (currentCount === 0) {
            initialScrollDoneRef.current = false
        }

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
            atBottomRef.current = true
        }

        /*
         * После prepend старых сообщений
         * сохраняем визуальную позицию пользователя.
         */
        const preservedScroll =
            preserveScrollRef.current

        if (
            preservedScroll &&
            !loadingOlder
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

        const lastId = messages[messages.length - 1]?.id
        if (!preservedScroll && atBottomRef.current && lastId !== previousLastId.current) {
            container.scrollTop = container.scrollHeight
        }
        previousLastId.current = lastId

    }, [messages, loadingOlder, loading])

    const tryLoadOlder =
        useCallback(async () => {
            const container =
                containerRef.current

            if (!container) {
                return
            }

            if (
                loadingOlder || loadingOlderRef.current || !!error || loading ||
                !hasMore ||
                historyStartReached
            ) {
                return
            }

            const threshold = 120

            const reachedTop =
                container.scrollTop <=
                threshold

            const contentDoesNotScroll =
                container.scrollHeight <=
                container.clientHeight

            if (
                !reachedTop &&
                !contentDoesNotScroll
            ) {
                return
            }

            loadingOlderRef.current = true
            try { await onLoadOlder(preserveScroll) } finally { loadingOlderRef.current = false }
        }, [
            loadingOlder,
            hasMore,
            historyStartReached,
            onLoadOlder,
            error,
            loading,
            preserveScroll,
        ])

    useLayoutEffect(() => {
        const container =
            containerRef.current

        if (!container) {
            return
        }

        if (
            loading || !!error ||
            loadingOlder ||
            !hasMore ||
            historyStartReached ||
            messages.length === 0
        ) {
            return
        }

        const contentDoesNotScroll =
            container.scrollHeight <=
            container.clientHeight

        if (!contentDoesNotScroll) {
            return
        }

        void tryLoadOlder()
    }, [
        messages,
        loading,
        loadingOlder,
        hasMore,
        historyStartReached,
        tryLoadOlder,
        error,
    ])

    const retryOlder = () => {
        void onRetryOlder?.(preserveScroll)
    }
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
                {onReload && <button type="button" className="ui-button ui-button-secondary" onClick={() => void onReload()}>Повторить загрузку сообщений</button>}
            </div>
        )
    }

    if (messages.length === 0) {
        return (
            <div className="message-list message-list-state">
                Сообщений пока нет
                {onReload && <button type="button" className="ui-button ui-button-secondary" onClick={() => void onReload()}>Обновить сообщения</button>}
            </div>
        )
    }



    const handleScroll = async () => {
        const container =
            containerRef.current

        if (!container) {
            return
        }
        atBottomRef.current = container.scrollHeight - container.scrollTop - container.clientHeight < 80

        if (
            container.scrollTop <=
            120
        ) {
            await tryLoadOlder()
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
            {error && <div className="message-history-error" role="alert">{error}
                {onRetryOlder && <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={loadingOlder}
                    onClick={retryOlder}>Повторить загрузку истории</button>}
            </div>}
            {refreshError && <div className="message-history-error" role="alert">{refreshError}
                {onReload && <button type="button" className="ui-button ui-button-secondary ui-button-sm" onClick={() => void onReload()}>Обновить сообщения</button>}
            </div>}
            {!error && !hasMore && !historyStartReached && onRetryOlder && <button type="button" className="ui-button ui-button-secondary ui-button-sm"
                disabled={loadingOlder} onClick={retryOlder}>Загрузить более ранние сообщения</button>}
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
                        !!messageDate && messageDate !== previousDate

                    return (

                        <div key={message.id}>
                            {index === 0 &&
                                historyStartReached && (
                                    <div className="message-date-separator">
                                        <span>
                                            Начало диалога
                                        </span>
                                    </div>
                                )}
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
                                onRetry={onRetryMessage}
                                retrying={retryingIds.includes(message.id)}
                            />
                        </div>
                    )
                },
            )}
        </div>
    )
}

export default MessageList
