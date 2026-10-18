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
import type { MessageDto } from '../../api/types/message'
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
    const assignmentRequest = useRef(0)
    const refreshRequest = useRef(0)
    const assignmentPending = useRef<string | null>(null)
    const detailsRequest = useRef(0)

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
    const latestConversation = useRef(conversation)
    latestConversation.current = conversation

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
    const [accountError, setAccountError] = useState<string | null>(null)
    const [channelError, setChannelError] = useState<string | null>(null)
    const [refreshingDetails, setRefreshingDetails] = useState(false)

    const {
        setCurrentWorkspaceId,
    } = useWorkspace()
    const workspaceSelector = useRef(setCurrentWorkspaceId)
    workspaceSelector.current = setCurrentWorkspaceId
    const memberships = useRef(employeeWorkspaces)
    memberships.current = employeeWorkspaces
    const membershipKey = employeeWorkspaces.map(item => `${item.workspaceId}:${item.isSupervisor}`).sort().join(',')

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
        refreshMessages,
        refreshError,
        acceptMessage,
        retryMessage,
        retryingIds,
        retryOlderMessages,
        loadOlderMessages,
    } = useConversationMessages(conversationId, visible)

    const loadDetails = useCallback(async (target: ConversationDto, notifyList = false) => {
        const generation = loadGeneration.current
        const request = ++detailsRequest.current
        const isCurrent = () => currentConversationId.current === target.id
            && generation === loadGeneration.current && request === detailsRequest.current
        if (!isCurrent()) return
        setRefreshingDetails(true)
        const [accountResult, channelsResult] = await Promise.allSettled([
            getClientAccount(target.clientAccountId),
            getWorkspaceChannels(target.workspaceId),
        ])
        if (!isCurrent()) return
        if (accountResult.status === 'fulfilled') {
            setClientAccount(accountResult.value)
            setAccountError(null)
        } else {
            setClientAccount(previous => previous?.id === target.clientAccountId ? previous : null)
            setAccountError(accountResult.reason instanceof Error ? accountResult.reason.message : 'Не удалось загрузить аккаунт')
        }
        if (channelsResult.status === 'fulfilled') {
            setChannel(channelsResult.value.find(item => item.account?.id === target.channelAccountId) ?? null)
            setChannelError(null)
        } else {
            setChannel(previous => previous?.workspaceId === target.workspaceId
                && previous.account?.id === target.channelAccountId ? previous : null)
            setChannelError(channelsResult.reason instanceof Error ? channelsResult.reason.message : 'Не удалось загрузить канал')
        }
        setRefreshingDetails(false)
        // A command may have committed even when its response was lost. A
        // successful manual retry must reconcile the filtered list as well.
        if (notifyList && accountResult.status === 'fulfilled') {
            publishConversationUpdate(latestConversation.current ?? target)
        }
    }, [])

    const loadConversation =
        useCallback(
            async (background = false) => {
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

                if (!background) setLoading(true)
                setError(null)

                try {
                    const result =
                        await getConversation(
                            conversationId,
                        )
                    if (!isCurrent()) return
                    workspaceSelector.current(
                        result.workspaceId,
                    )

                    setConversation(result)

                    publishConversationUpdate(
                        result,
                    )

                    await loadDetails(result)
                    if (!isCurrent()) return

                    const canManageWorkspaceAssignments =
                        isSuperAdmin ||
                        (
                            isEmployee &&
                            memberships.current.some(
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
                membershipKey,
                loadDetails,
            ],
        )

    useEffect(() => {
        setActionError(null)
        void loadConversation()
        return () => { loadGeneration.current += 1 }
    }, [loadConversation])

    const refreshConversation = useCallback(async () => {
        if (!conversationId || assignmentPending.current === conversationId) return
        const generation = loadGeneration.current
        const command = assignmentRequest.current
        const request = ++refreshRequest.current
        try {
            const updated = await getConversation(conversationId)
            if (currentConversationId.current !== conversationId || loadGeneration.current !== generation
                || assignmentRequest.current !== command || refreshRequest.current !== request) return
            if (latestConversation.current?.id === conversationId && JSON.stringify(latestConversation.current) !== JSON.stringify(updated)) {
                setConversation(updated)
                publishConversationUpdate(updated)
            }
        } catch (err) {
            if (currentConversationId.current === conversationId && loadGeneration.current === generation
                && assignmentRequest.current === command && refreshRequest.current === request) {
                setActionError(err instanceof Error ? err.message : 'Не удалось обновить диалог')
            }
        }
    }, [conversationId])

    useEffect(() => {
        if (!conversationId || !visible) return
        let stopped = false
        let timer: ReturnType<typeof setTimeout>
        const tick = async () => {
            if (typeof document === 'undefined' || !document.hidden) await refreshConversation()
            if (!stopped) timer = setTimeout(tick, 10000)
        }
        timer = setTimeout(tick, 10000)
        return () => { stopped = true; clearTimeout(timer) }
    }, [conversationId, visible, refreshConversation])

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

    if (loading || (conversation && conversation.id !== conversationId)) {
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
                <button type="button" className="ui-button ui-button-secondary" onClick={() => void loadConversation()}>Повторить загрузку диалога</button>
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

    const runAssignment = async (operation: () => Promise<ConversationDto>, tab?: 'mine' | 'unassigned') => {
        const id = conversation.id
        const generation = loadGeneration.current
        if (assignmentPending.current === id) return
        assignmentPending.current = id
        const request = ++assignmentRequest.current
        setActionError(null)
        try {
            const updated = await operation()
            publishConversationUpdate(updated)
            if (currentConversationId.current !== id || assignmentRequest.current !== request || loadGeneration.current !== generation) return
            setConversation(updated)
            if (tab) navigate(`/inbox/${id}?tab=${tab}`, { replace: true })
        } catch (err) {
            if (currentConversationId.current !== id || assignmentRequest.current !== request || loadGeneration.current !== generation) return
            setActionError(err instanceof Error ? err.message : 'Не удалось изменить ответственного')
            // The server may have rejected a concurrent assignment. Re-read its
            // state without clearing the draft or hiding the operation error.
            try {
                const updated = await getConversation(id)
                if (currentConversationId.current === id && assignmentRequest.current === request && loadGeneration.current === generation) {
                    setConversation(updated)
                    publishConversationUpdate(updated)
                }
            } catch { /* Keep the original operation error visible. */ }
        } finally {
            if (assignmentRequest.current === request) assignmentPending.current = null
        }
    }
    const handleTake = async () => {
        if (isEmployee) await runAssignment(() => assignConversationToMe(conversation.id), 'mine')
    }
    const handleRelease = async () => {
        if (isEmployee) await runAssignment(() => unassignConversationFromMe(conversation.id), 'unassigned')
    }
    const handleAssign = async (employeeId: string) => {
        if (canManageAssignments) await runAssignment(() => assignConversation(conversation.id, employeeId))
    }
    const handleUnassign = async () => {
        if (canManageAssignments) await runAssignment(() => unassignConversation(conversation.id))
    }
    const handleMessageSent = async (message: MessageDto) => {
        if (currentConversationId.current !== conversation.id) return
        if (message) acceptMessage(message)
        await refreshConversation()
    }
    const detailsGeneration = loadGeneration.current
    const handleClientChanged = (account: ClientAccountSummary) => {
        // A confirmed command invalidates the original workspace list even
        // after this view has navigated elsewhere or has been unmounted.
        publishConversationUpdate(conversation)
        if (currentConversationId.current !== conversation.id || detailsGeneration !== loadGeneration.current) return
        // Invalidate a read started before this successful change of relationship.
        detailsRequest.current += 1
        setRefreshingDetails(false)
        setClientAccount(account)
        setAccountError(null)
    }
    return (
        <div className="conversation-view">
            <div className="conversation-main">
                <ConversationHeader key={`header:${conversation.id}`}
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

                <MessageList key={`messages:${conversation.id}`}
                    messages={messages}
                    refreshError={refreshError} onReload={messages.length ? refreshMessages : reloadMessages}
                    onRetryOlder={retryOlderMessages} onRetryMessage={canSend ? retryMessage : undefined} retryingIds={retryingIds}
                    loading={messagesLoading}
                    loadingOlder={messagesLoadingOlder}
                    hasMore={messagesHasMore}
                    historyStartReached={historyStartReached}
                    error={messagesError}
                    onLoadOlder={loadOlderMessages}
                />

                {canSend && (
                    <MessageComposer key={`composer:${conversation.id}`}
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
                accountError={accountError}
                channelError={channelError}
                refreshingDetails={refreshingDetails}
                onRetryDetails={() => void loadDetails(conversation, true)}
                onChanged={
                    handleClientChanged
                }
            />
        </div>
    )
}

export default ConversationView
