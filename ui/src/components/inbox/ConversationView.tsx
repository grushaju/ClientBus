import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
    assignConversationToMe,
    getConversation,
    unassignConversationFromMe,
} from '../../api/conversationApi'
import { getClientAccount } from '../../api/clientAccountApi'
import type {
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'
import { useAuth } from '../../auth/AuthContext'
import ConversationHeader from './ConversationHeader'
import MessageComposer from './MessageComposer'
import MessageList from './MessageList'
import { useConversationMessages } from './useConversationMessages'

function ConversationView() {
    const { conversationId } = useParams()
    const {
        currentEmployee,
        isEmployee,
        isSuperAdmin,
    } = useAuth()

    const [conversation, setConversation] =
        useState<ConversationDto | null>(null)

    const [clientAccount, setClientAccount] =
        useState<ClientAccountSummary | null>(null)

    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const {
        messages,
        loading: messagesLoading,
        error: messagesError,
        reloadMessages,
    } = useConversationMessages(conversationId)

    const loadConversation = useCallback(async () => {
        if (!conversationId) {
            setConversation(null)
            setClientAccount(null)
            return
        }

        const id = conversationId

        setLoading(true)
        setError(null)

        try {
            const result = await getConversation(id)

            setConversation(result)

            try {
                const account = await getClientAccount(
                    result.clientAccountId,
                )

                setClientAccount(account)
            } catch {
                setClientAccount(null)
            }
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить диалог',
            )
        } finally {
            setLoading(false)
        }
    }, [conversationId])

    useEffect(() => {
        void loadConversation()
    }, [loadConversation])

    if (!conversationId) {
        return (
            <div className="conversation-empty">
                Выберите диалог
            </div>
        )
    }

    if (loading) {
        return (
            <div className="conversation-state">
                Загрузка диалога…
            </div>
        )
    }

    if (error) {
        return (
            <div className="conversation-state conversation-error">
                {error}
            </div>
        )
    }

    if (!conversation) {
        return (
            <div className="conversation-state">
                Диалог не найден
            </div>
        )
    }

    const canSend =
        isEmployee &&
        conversation.assignedEmployeeId === currentEmployee?.id

    const handleTake = async () => {
        if (!isEmployee) {
            return
        }

        await assignConversationToMe(conversation.id)
        await loadConversation()
    }

    const handleRelease = async () => {
        if (!isEmployee) {
            return
        }

        await unassignConversationFromMe(conversation.id)
        await loadConversation()
    }

    const handleMessageSent = async () => {
        await reloadMessages()
        await loadConversation()
    }


    return (
        <div className="conversation-view">
            <ConversationHeader
                conversation={conversation}
                clientAccount={clientAccount}
                isEmployee={isEmployee}
                isSuperAdmin={isSuperAdmin}
                onTake={handleTake}
                onRelease={handleRelease}
            />

            <MessageList
                messages={messages}
                loading={messagesLoading}
                error={messagesError}
            />

            {canSend && (
                <MessageComposer
                    conversationId={conversation.id}
                    onSent={async () => {
                        await reloadMessages()
                        await loadConversation()
                    }}
                />
            )}
        </div>
    )
}

export default ConversationView