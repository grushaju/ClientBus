import {
    useCallback,
    useEffect,
    useRef,
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
    markConversationAsRead,
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
import {
    useWorkspace,
} from '../../workspace/WorkspaceContext'


function publishConversationUpdate(conversation: ConversationDto) {
    window.dispatchEvent(new CustomEvent('clientbus:conversation-updated', { detail: conversation }))
}

function ConversationView({ visible = true }: { visible?: boolean }) {
    const {
        conversationId,
    } = useParams()

    const navigate =
        useNavigate()
    const loadGeneration = useRef(0)
    const currentConversationId = useRef(conversationId)
    currentConversationId.current = conversationId

    const {
        currentEmployee,
        employeeWorkspaces,
        isEmployee,
        isSuperAdmin,
    } = useAuth()

    const [conversation, setConversation] =
        useState<ConversationDto | null>(
            null,
        )

    const canManageAssignments =
        isSuperAdmin ||
        (
            isEmployee &&
            !!conversation &&
            employeeWorkspaces.some(
                membership =>
                    membership.workspaceId === conversation.workspaceId &&
                    membership.isSupervisor,
            )
        )

    const [clientAccount, setClientAccount] =
        useState<ClientAccountSummary | null>(
            null,
        )

    const [channel, setChannel] =
        useState<ChannelSummary | null>(
            null,
        )

    const {
        setCurrentWorkspaceId,
    } = useWorkspace()

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
        loadingOlder: messagesLoadingOlder,
        hasMore: messagesHasMore,
        historyStartReached,
        error: messagesError,
        reloadMessages,
        loadOlderMessages,
    } = useConversationMessages(conversationId)

    const loadConversation =
        useCallback(
            async () => {
                if (conversationId !== currentConversationId.current) return
                const generation = ++loadGeneration.current
                const isCurrent = () => generation === loadGeneration.current
                    && currentConversationId.current === conversationId
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
                    if (!isCurrent()) return
                    setCurrentWorkspaceId(
                        result.workspaceId,
                    )

                    setConversation(result)

                    publishConversationUpdate(
                        result,
                    )

                    try {
                        const account =
                            await getClientAccount(
                                result.clientAccountId,
                            )
                        if (!isCurrent()) return

                        setClientAccount(
                            account,
                        )
                    } catch (err) {
                        if (!isCurrent()) return
                        console.error(
                            'ClientAccount load failed:',
                            result.clientAccountId,
                            err,
                        )

                        setClientAccount(
                            null,
                        )
                    }

                    try {
                        const channels =
                            await getWorkspaceChannels(
                                result.workspaceId,
                            )
                        if (!isCurrent()) return

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
                        if (!isCurrent()) return
                        setChannel(null)
                    }

                    const canManageWorkspaceAssignments =
                        isSuperAdmin ||
                        (
                            isEmployee &&
                            employeeWorkspaces.some(
                                membership =>
                                    membership.workspaceId ===
                                    result.workspaceId &&
                                    membership.isSupervisor,
                            )
                        )

                    if (canManageWorkspaceAssignments) {
                        try {
                            const workspaceEmployees =
                                await getWorkspaceEmployees(
                                    result.workspaceId,
                                )
                            if (!isCurrent()) return

                            setEmployees(workspaceEmployees)
                        } catch (err) {
                            if (!isCurrent()) return
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
                    if (!isCurrent()) return
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
                    if (isCurrent()) setLoading(false)
                }
            },
            [
                conversationId,
                isEmployee,
                isSuperAdmin,
            ],
        )

    useEffect(() => {
        void loadConversation()
        return () => { loadGeneration.current += 1 }
    }, [loadConversation])

    // Loading metadata and retaining the composer do not imply that the
    // operator can see the thread. In particular the mobile list hides it.
    useEffect(() => {
        if (!visible || loading || messagesLoading || messagesError || !isEmployee
            || !conversation || conversation.id !== conversationId || conversation.unreadCount <= 0) return
        let active = true
        const id = conversation.id
        void markConversationAsRead(id).then(updated => {
            if (!active || currentConversationId.current !== id) return
            setConversation(previous => previous?.id === id ? { ...previous, unreadCount: updated.unreadCount } : previous)
            publishConversationUpdate(updated)
        }).catch(err => {
            if (active) console.error('Conversation read failed:', id, err)
        })
        return () => { active = false }
    }, [visible, loading, messagesLoading, messagesError, isEmployee, conversationId, conversation?.id, conversation?.unreadCount])

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

    const handleTake =
        async () => {
            if (!isEmployee) {
                return
            }

            const updated =
                await assignConversationToMe(
                    conversation.id,
                )

            setConversation(updated)
            publishConversationUpdate(
                updated,
            )

            navigate(
                `/inbox/${conversation.id}?tab=mine`,
                {
                    replace: true,
                },
            )
        }

    const handleRelease =
        async () => {
            if (!isEmployee) {
                return
            }

            const updated =
                await unassignConversationFromMe(
                    conversation.id,
                )

            setConversation(updated)
            publishConversationUpdate(
                updated,
            )

            navigate(
                `/inbox/${conversation.id}?tab=unassigned`,
                {
                    replace: true,
                },
            )
        }

    const handleAssign =
        async (
            employeeId: string,
        ) => {
            if (!canManageAssignments) {
                return
            }

            setActionError(null)

            try {
                const updated =
                    await assignConversation(
                        conversation.id,
                        employeeId,
                    )

                setConversation(
                    updated,
                )

                publishConversationUpdate(
                    updated,
                )
            } catch (err) {
                setActionError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось назначить сотрудника',
                )
            }
        }

    const handleUnassign =
        async () => {
            if (!canManageAssignments) {
                return
            }

            setActionError(null)

            try {
                const updated =
                    await unassignConversation(
                        conversation.id,
                    )

                setConversation(
                    updated,
                )

                publishConversationUpdate(
                    updated,
                )
            } catch (err) {
                setActionError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось снять назначение',
                )
            }
        }

    const handleMessageSent =
        async () => {
            await reloadMessages()
            await loadConversation()
        }

    return (
        <div className="conversation-view">
            <div className="conversation-main">
                <ConversationHeader
                    conversation={
                        conversation
                    }
                    clientAccount={
                        clientAccount
                    }
                    channel={channel}
                    employees={
                        employees
                    }
                    currentEmployeeId={
                        currentEmployee?.id ??
                        null
                    }
                    isEmployee={
                        isEmployee
                    }
                    canManageAssignments={
                        canManageAssignments
                    }
                    actionError={
                        actionError
                    }
                    onTake={
                        handleTake
                    }
                    onRelease={
                        handleRelease
                    }
                    onAssign={
                        handleAssign
                    }
                    onUnassign={
                        handleUnassign
                    }
                />

                <MessageList
                    messages={messages}
                    loading={messagesLoading}
                    loadingOlder={messagesLoadingOlder}
                    hasMore={messagesHasMore}
                    historyStartReached={historyStartReached}
                    error={messagesError}
                    onLoadOlder={loadOlderMessages}
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
                conversation={
                    conversation
                }
                clientAccount={
                    clientAccount
                }
                channel={channel}
                onChanged={
                    loadConversation
                }
            />
        </div>
    )
}

export default ConversationView
