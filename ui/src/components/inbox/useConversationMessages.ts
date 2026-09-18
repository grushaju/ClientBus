import { useCallback, useEffect, useState } from 'react'
import { getConversationMessages } from '../../api/messageApi'
import type { MessageDto } from '../../api/types/message'

interface UseConversationMessagesResult {
    messages: MessageDto[]
    loading: boolean
    error: string | null
    reloadMessages: () => Promise<void>
}

export function useConversationMessages(
    conversationId: string | undefined,
): UseConversationMessagesResult {
    const [messages, setMessages] = useState<MessageDto[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const reloadMessages = useCallback(async () => {
        if (!conversationId) {
            setMessages([])
            return
        }

        setLoading(true)
        setError(null)

        try {
            const page = await getConversationMessages(conversationId, 0, 100)

            const sortedMessages = [...page.content].sort((a, b) => {
                const aTime = a.sentAt ?? a.createdAt
                const bTime = b.sentAt ?? b.createdAt

                return (
                    new Date(aTime).getTime() -
                    new Date(bTime).getTime()
                )
            })

            setMessages(sortedMessages)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить сообщения',
            )
        } finally {
            setLoading(false)
        }
    }, [conversationId])

    useEffect(() => {
        void reloadMessages()
    }, [reloadMessages])

    return {
        messages,
        loading,
        error,
        reloadMessages,
    }
}