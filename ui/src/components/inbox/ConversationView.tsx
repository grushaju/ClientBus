import {
    useCallback,
    useEffect,
    useState,
} from 'react'

import {
    useNavigate,
    useParams,
} from 'react-router-dom'

import {
    assignConversation,
    assignConversationToMe,
    getConversation,
    unassignConversation,
    unassignConversationFromMe,
} from '../../api/conversationApi'

import { getClientAccount } from '../../api/clientAccountApi'

import { getWorkspaceChannels } from '../../api/channelApi'

import { getWorkspaceEmployees } from '../../api/employeeApi'

import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'

import { useAuth } from '../../auth/AuthContext'

import type { EmployeeDto } from '../../auth/types'

import ConversationHeader from './ConversationHeader'
import ConversationClientPanel from './ConversationClientPanel'
import MessageComposer from './MessageComposer'
import MessageList from './MessageList'
import { useConversationMessages } from './useConversationMessages'

function ConversationView() {
    const {
        conversationId,
    } = useParams()

    const navigate = useNavigate()

    const {
        currentEmployee,
        isEmployee,
        isSuperAdmin,
    } = useAuth()

    const [conversation, setConversation] =
        useState<ConversationDto | null>(null)

    const [clientAccount, setClientAccount] =
        useState<ClientAccountSummary | null>(null)

    const [channel, setChannel] =
        useState<ChannelSummary | null>(null)

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    const [employees, setEmployees] =
        useState<EmployeeDto[]>([])

    const [actionError, setActionError] =
        useState<string | null>(null)

    const {
        messages,
        loading: messagesLoading,
        error: messagesError,
        reloadMessages,
    } = useConversationMessages(conversationId)

    const loadConversation = useCallback(
        async () => {
            if (!conversationId) {
                setConversation(null)
                setClientAccount(null)
                setChannel(null)
                setEmployees([])
                return
            }

            setLoading(true)
            setError(null)

            try {
                const result =
                    await getConversation(
                        conversationId,
                    )

                setConversation(result)

                try {
                    const account =
                        await getClientAccount(
                            result.clientAccountId,
                        )

                    setClientAccount(account)
                } catch (err) {
                    console.error(
                        'ClientAccount load failed:',
                        result.clientAccountId,
                        err,
                    )

                    setClientAccount(null)
                }

                try {
                    const channels =
                        await getWorkspaceChannels(
                            result.workspaceId,
                        )

                    const matchingChannel =
                        channels.find(
                            item =>
                                item.account?.id ===
                                result.channelAccountId,
                        ) ?? null

                    setChannel(
                        matchingChannel,
                    )
                } catch {
                    setChannel(null)
                }

                if (isSuperAdmin) {
                    try {
                        const workspaceEmployees =
                            await getWorkspaceEmployees(
                                result.workspaceId,
                            )

                        setEmployees(
                            workspaceEmployees,
                        )
                    } catch (err) {
                        console.error(
                            'Workspace employees load failed:',
                            result.workspaceId,
                            err,
                        )

                        setEmployees([])
                    }
                } else {
                    setEmployees([])
                }
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить диалог',
                )

                setConversation(null)
                setClientAccount(null)
                setChannel(null)
                setEmployees([])
            } finally {
                setLoading(false)
            }
        },
        [
            conversationId,
            isSuperAdmin,
        ],
    )

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
        conversation.assignedEmployeeId ===
        currentEmployee?.id

    const handleTake = async () => {
        if (!isEmployee) {
            return
        }

        await assignConversationToMe(
            conversation.id,
        )

        await loadConversation()

        navigate(
            `/inbox/${conversation.id}?tab=mine`,
            {
                replace: true,
            },
        )
    }

    const handleRelease = async () => {
        if (!isEmployee) {
            return
        }

        await unassignConversationFromMe(
            conversation.id,
        )

        await loadConversation()

        navigate(
            `/inbox/${conversation.id}?tab=unassigned`,
            {
                replace: true,
            },
        )
    }

    const handleAssign = async (
        employeeId: string,
    ) => {
        if (!isSuperAdmin) {
            return
        }

        setActionError(null)

        try {
            await assignConversation(
                conversation.id,
                employeeId,
            )

            await loadConversation()
        } catch (err) {
            setActionError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось назначить сотрудника',
            )
        }
    }

    const handleUnassign = async () => {
        if (!isSuperAdmin) {
            return
        }

        setActionError(null)

        try {
            await unassignConversation(
                conversation.id,
            )

            await loadConversation()
        } catch (err) {
            setActionError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось снять назначение',
            )
        }
    }

    const handleMessageSent = async () => {
        await reloadMessages()
        await loadConversation()
    }

    return (
        <div className="conversation-view">
            <div className="conversation-main">
                <ConversationHeader
                    conversation={conversation}
                    clientAccount={clientAccount}
                    channel={channel}
                    employees={employees}
                    currentEmployeeId={
                        currentEmployee?.id ??
                        null
                    }
                    isEmployee={isEmployee}
                    isSuperAdmin={isSuperAdmin}
                    actionError={actionError}
                    onTake={handleTake}
                    onRelease={handleRelease}
                    onAssign={handleAssign}
                    onUnassign={handleUnassign}
                />

                <MessageList
                    messages={messages}
                    loading={messagesLoading}
                    error={messagesError}
                />

                {canSend && (
                    <MessageComposer
                        conversationId={
                            conversation.id
                        }
                        onSent={
                            handleMessageSent
                        }
                    />
                )}
            </div>

            <ConversationClientPanel
                conversation={conversation}
                clientAccount={clientAccount}
                channel={channel}
                onChanged={loadConversation}
            />
        </div>
    )
}

export default ConversationView