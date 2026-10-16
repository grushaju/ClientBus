import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react'

import {
    getConversationMessages,
    syncConversationHistory,
} from '../../api/messageApi'
import type { MessageDto } from '../../api/types/message'

interface UseConversationMessagesResult {
    messages: MessageDto[]
    loading: boolean
    loadingOlder: boolean
    hasMore: boolean
    historyStartReached: boolean
    error: string | null
    reloadMessages: () => Promise<void>
    loadOlderMessages: () => Promise<void>
}

const PAGE_SIZE = 100

export function useConversationMessages(
    conversationId: string | undefined,
): UseConversationMessagesResult {
    const [messages, setMessages] =
        useState<MessageDto[]>([])

    const [loading, setLoading] =
        useState(false)

    const [loadingOlder, setLoadingOlder] =
        useState(false)

    const [hasMore, setHasMore] =
        useState(true)

    const [historyStartReached, setHistoryStartReached] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    const loadingOlderRef =
        useRef(false)

    const sortMessages = (
        items: MessageDto[],
    ): MessageDto[] => {
        return [...items].sort(
            (a, b) => {
                const aTime =
                    a.sentAt ??
                    a.createdAt

                const bTime =
                    b.sentAt ??
                    b.createdAt

                return (
                    new Date(aTime).getTime() -
                    new Date(bTime).getTime()
                )
            },
        )
    }

    const reloadMessages =
        useCallback(async () => {
            if (!conversationId) {
                setMessages([])
                setHasMore(false)
                setHistoryStartReached(false)
                return
            }

            setLoading(true)
            setError(null)

            loadingOlderRef.current = false
            setHasMore(true)
            setHistoryStartReached(false)

            try {
                const page =
                    await getConversationMessages(
                        conversationId,
                        0,
                        PAGE_SIZE,
                    )

                const sortedMessages =
                    sortMessages(page.content)

                setMessages(sortedMessages)

                /*
                 * Если локальная история отсутствует,
                 * сразу пытаемся загрузить первую страницу
                 * истории с платформы.
                 *
                 * Это важно: MessageList не сможет инициировать
                 * loadOlderMessages через scroll, если сообщений
                 * вообще нет.
                 */
                if (sortedMessages.length === 0) {
                    setLoadingOlder(true)

                    try {
                        const result =
                            await syncConversationHistory(
                                conversationId,
                                null,
                                PAGE_SIZE,
                            )

                        const syncedMessages =
                            sortMessages(
                                result.messages,
                            )

                        setMessages(
                            syncedMessages,
                        )

                        setHistoryStartReached(
                            result.historyStartReached,
                        )

                        setHasMore(
                            !result.historyStartReached,
                        )
                    } finally {
                        setLoadingOlder(false)
                    }

                    return
                }

                /*
                 * Локальная история есть.
                 *
                 * page.last означает, что достигнут конец
                 * локальной БД, но не обязательно конец истории
                 * на платформе. Поэтому historyStartReached здесь
                 * намеренно не устанавливаем в true.
                 */
                setHasMore(!page.last)

            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить сообщения',
                )

                setMessages([])
                setHasMore(false)
                setHistoryStartReached(false)
            } finally {
                setLoading(false)
            }
        }, [conversationId])

    const loadOlderMessages =
        useCallback(async () => {
            if (
                !conversationId ||
                loadingOlderRef.current ||
                !hasMore
            ) {
                return
            }

            const oldestMessage =
                messages[0]

            const beforeExternalId =
                oldestMessage?.externalId ?? null

            if (
                messages.length > 0 &&
                !beforeExternalId
            ) {
                return
            }

            loadingOlderRef.current = true
            setLoadingOlder(true)

            try {
                const result =
                    await syncConversationHistory(
                        conversationId,
                        beforeExternalId,
                        PAGE_SIZE,
                    )

                const olderMessages =
                    sortMessages(
                        result.messages,
                    )

                let addedMessages = false

                setMessages(
                    currentMessages => {
                        const existingIds =
                            new Set(
                                currentMessages.map(
                                    message =>
                                        message.id,
                                ),
                            )

                        const uniqueOlderMessages =
                            olderMessages.filter(
                                message =>
                                    !existingIds.has(
                                        message.id,
                                    ),
                            )

                        if (
                            uniqueOlderMessages.length ===
                            0
                        ) {
                            return currentMessages
                        }

                        addedMessages = true

                        return [
                            ...uniqueOlderMessages,
                            ...currentMessages,
                        ]
                    },
                )

                setHistoryStartReached(
                    result.historyStartReached,
                )

                /*
                 * Если backend не сообщил о конце истории,
                 * но при этом не вернул ни одного нового сообщения,
                 * дальше запрашивать бессмысленно.
                 *
                 * Это также защищает автоматическую догрузку
                 * от бесконечного цикла.
                 */
                setHasMore(
                    addedMessages &&
                    !result.historyStartReached,
                )
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить старые сообщения',
                )
            } finally {
                loadingOlderRef.current =
                    false

                setLoadingOlder(false)
            }
        }, [
            conversationId,
            hasMore,
            messages,
        ])

    useEffect(() => {
        void reloadMessages()
    }, [reloadMessages])

    return {
        messages,
        loading,
        loadingOlder,
        hasMore,
        historyStartReached,
        error,
        reloadMessages,
        loadOlderMessages,
    }
}