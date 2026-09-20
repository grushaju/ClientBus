import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react'

import { getConversationMessages } from '../../api/messageApi'
import type { MessageDto } from '../../api/types/message'

interface UseConversationMessagesResult {
    messages: MessageDto[]
    loading: boolean
    loadingOlder: boolean
    hasMore: boolean
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

    const [error, setError] =
        useState<string | null>(null)

    const currentPageRef =
        useRef(0)

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
                currentPageRef.current = 0
                return
            }

            setLoading(true)
            setError(null)

            currentPageRef.current = 0
            loadingOlderRef.current = false
            setHasMore(true)

            try {
                const page =
                    await getConversationMessages(
                        conversationId,
                        0,
                        PAGE_SIZE,
                    )

                setMessages(
                    sortMessages(
                        page.content,
                    ),
                )

                setHasMore(
                    !page.last,
                )
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить сообщения',
                )

                setMessages([])
                setHasMore(false)
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

            loadingOlderRef.current = true
            setLoadingOlder(true)

            const nextPage =
                currentPageRef.current + 1

            try {
                const page =
                    await getConversationMessages(
                        conversationId,
                        nextPage,
                        PAGE_SIZE,
                    )

                const olderMessages =
                    sortMessages(
                        page.content,
                    )

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

                        return [
                            ...uniqueOlderMessages,
                            ...currentMessages,
                        ]
                    },
                )

                currentPageRef.current =
                    nextPage

                setHasMore(
                    !page.last,
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
        ])

    useEffect(() => {
        void reloadMessages()
    }, [reloadMessages])

    return {
        messages,
        loading,
        loadingOlder,
        hasMore,
        error,
        reloadMessages,
        loadOlderMessages,
    }
}
