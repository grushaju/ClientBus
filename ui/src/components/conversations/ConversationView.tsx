import {
    useCallback,
    useEffect,
    useRef,
    useMemo,
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
import { getApiErrorStatus } from '../../api/apiClient'

import { getWorkspaceChannels } from '../../api/channelApi'

import { getWorkspaceEmployees } from '../../api/employeeApi'

import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'

import { useAuth } from '../../auth/AuthContext'
import { useUiInvalidation } from '../../api/events/InvalidationContext'
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { useUiScope, affectsConversation, isAccessEvent } from '../../api/events/uiScope'
import { singleFlightRead } from '../../api/events/singleFlightRead'

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

    const scope = useUiScope()
    const accessVersion = scope?.accessVersion
    const lifetime = useUiLifetime(conversationId ?? '')
    const metadata = useMemo(() => singleFlightRead(signal => lifetime.read(
        requestSignal => getConversation(conversationId!, requestSignal), signal)), [lifetime, conversationId])
    useEffect(() => () => metadata.cancel(), [metadata])
    const navigate =
        useNavigate()
    const loadGeneration = useRef(0)
    const currentConversationId = useRef(conversationId)
    currentConversationId.current = conversationId
    const assignmentRequest = useRef(0)
    const refreshRequest = useRef(0)
    const assignmentPending = useRef<string | null>(null)
    const assignmentFlight = useRef<{ id: string; promise: Promise<void> }>()
    const detailsRequest = useRef(0)
    const previousLifetime = useRef(lifetime)
    if (previousLifetime.current !== lifetime) {
        loadGeneration.current++; detailsRequest.current++; assignmentRequest.current++
        assignmentPending.current = null; assignmentFlight.current = undefined; previousLifetime.current = lifetime
    }

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
    const loadedLifetime = useRef(lifetime)

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
    const conversationWorkspaceId = conversation?.workspaceId ?? scope?.workspaceId
    const membershipKey = !!employeeWorkspaces.find(item => item.workspaceId === conversationWorkspaceId)?.isSupervisor

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)
    const latestReadError = useRef(error)
    latestReadError.current = error

    const [employees, setEmployees] =
        useState<EmployeeDto[]>([])

    const [actionError, setActionError] =
        useState<string | null>(null)

    const handleReadError = useCallback((failure: unknown) => {
        const message = failure instanceof Error ? failure.message : 'Не удалось обновить диалог'
        const status = getApiErrorStatus(failure)
        if (status === 403 || status === 404) {
            window.dispatchEvent(new CustomEvent('clientbus:conversation-unavailable', { detail: { id: currentConversationId.current } }))
            lifetime.reset()
            metadata.cancel()
            loadGeneration.current += 1
            detailsRequest.current += 1
            setLoading(false)
            setRefreshingDetails(false)
            setError(message)
            setConversation(null)
            setClientAccount(null)
            setChannel(null)
            setEmployees([])
        } else {
            setActionError(message)
        }
    }, [lifetime, metadata])

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
    } = useConversationMessages(conversationId, visible && !error, handleReadError, conversation?.workspaceId)

    const loadDetails = useCallback(async (target: ConversationDto, notifyList = false, parentSignal?: AbortSignal) => {
        const generation = loadGeneration.current
        const allowed = lifetime.capture()
        const request = ++detailsRequest.current
        const isCurrent = () => allowed() && !parentSignal?.aborted && currentConversationId.current === target.id
            && generation === loadGeneration.current && request === detailsRequest.current
        if (!isCurrent()) return
        setRefreshingDetails(true)
        let results: [PromiseSettledResult<Awaited<ReturnType<typeof getClientAccount>>>,
            PromiseSettledResult<Awaited<ReturnType<typeof getWorkspaceChannels>>>]
        try { results = await lifetime.read(signal => Promise.allSettled([
            getClientAccount(target.clientAccountId, signal),
            getWorkspaceChannels(target.workspaceId, signal),
        ] as const), parentSignal) }
        catch (err) {
            if (allowed() && generation === loadGeneration.current && request === detailsRequest.current) {
                setRefreshingDetails(false)
                if (!parentSignal?.aborted) setAccountError(err instanceof Error ? err.message : 'Не удалось обновить сведения')
            }
            if (parentSignal) throw err
            return
        }
        const [accountResult, channelsResult] = results
        if (!isCurrent()) return
        if (accountResult.status === 'fulfilled') {
            setClientAccount(accountResult.value)
            setAccountError(null)
        } else {
            const denied = [403,404].includes(getApiErrorStatus(accountResult.reason) ?? 0)
            setClientAccount(previous => !denied && previous?.id === target.clientAccountId ? previous : null)
            setAccountError(accountResult.reason instanceof Error ? accountResult.reason.message : 'Не удалось загрузить аккаунт')
        }
        if (channelsResult.status === 'fulfilled') {
            setChannel(channelsResult.value.find(item => item.account?.id === target.channelAccountId) ?? null)
            setChannelError(null)
        } else {
            const denied = [403,404].includes(getApiErrorStatus(channelsResult.reason) ?? 0)
            setChannel(previous => !denied && previous?.workspaceId === target.workspaceId
                && previous.account?.id === target.channelAccountId ? previous : null)
            setChannelError(channelsResult.reason instanceof Error ? channelsResult.reason.message : 'Не удалось загрузить канал')
        }
        setRefreshingDetails(false)
        // A command may have committed even when its response was lost. A
        // successful manual retry must reconcile the filtered list as well.
        if (notifyList && accountResult.status === 'fulfilled') {
            publishConversationUpdate(latestConversation.current ?? target)
        }
        if (parentSignal) {
            if (accountResult.status === 'rejected' && ![403,404].includes(getApiErrorStatus(accountResult.reason) ?? 0)) throw accountResult.reason
            if (channelsResult.status === 'rejected' && ![403,404].includes(getApiErrorStatus(channelsResult.reason) ?? 0)) throw channelsResult.reason
        }
    }, [lifetime])

    const loadConversation =
        useCallback(
            async (background = false, signal?: AbortSignal, propagateFailure = false) => {
                const allowed = lifetime.capture()
                if (conversationId !== currentConversationId.current) return
                const generation = ++loadGeneration.current
                const isCurrent = () => allowed() && !signal?.aborted && generation === loadGeneration.current
                    && currentConversationId.current === conversationId
                if (!conversationId) {
                    setConversation(null)
                    setClientAccount(null)
                    setChannel(null)
                    setEmployees([])
                    return
                }

                if (!background) setLoading(true)
                const retryingRead = !!latestReadError.current
                setError(null)

                try {
                    const result =
                        await metadata.read(signal)
                    if (!isCurrent()) return
                    workspaceSelector.current(
                        result.workspaceId,
                    )

                    loadedLifetime.current = lifetime
                    setConversation(result)
                    // Resume a denied message session only after access to the
                    // conversation has been confirmed by the explicit retry.
                    if (retryingRead) void reloadMessages()

                    publishConversationUpdate(
                        result,
                    )

                    await loadDetails(result, false, signal)
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
                                await lifetime.read(signal => getWorkspaceEmployees(result.workspaceId, signal))
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
                    if ([403, 404].includes(getApiErrorStatus(err) ?? 0)) {
                        // No DTO (and therefore no workspace) may have loaded yet.
                        window.dispatchEvent(new CustomEvent('clientbus:conversation-unavailable', { detail: { id: conversationId } }))
                    }
                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить диалог',
                    )

                    setConversation(null)
                    setClientAccount(null)
                    setChannel(null)
                    setEmployees([])
                    if (propagateFailure && ![403,404].includes(getApiErrorStatus(err) ?? 0)) throw err
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
                reloadMessages,
                lifetime, metadata,
            ],
        )

    useEffect(() => {
        setActionError(null)
        void loadConversation()
        return () => { loadGeneration.current += 1 }
    }, [loadConversation])

    const refreshConversation = useCallback(async (signal?: AbortSignal) => {
        const allowed = lifetime.capture()
        if (!conversationId) return
        // Keep the invalidation pending until the command has settled. Its
        // response alone cannot reconcile changes made by another operator.
        while (assignmentFlight.current?.id === conversationId) {
            const pending = assignmentFlight.current.promise
            await lifetime.read(() => pending, signal)
            if (!allowed() || signal?.aborted) return
        }
        const generation = loadGeneration.current
        const command = assignmentRequest.current
        const request = ++refreshRequest.current
        try {
            const updated = await metadata.read(signal, true)
            if (!allowed() || signal?.aborted || currentConversationId.current !== conversationId || loadGeneration.current !== generation
                || assignmentRequest.current !== command || refreshRequest.current !== request) return
            if (latestConversation.current?.id === conversationId && JSON.stringify(latestConversation.current) !== JSON.stringify(updated)) {
                setConversation(updated)
                publishConversationUpdate(updated)
            }
        } catch (err) {
            if (allowed() && !signal?.aborted && currentConversationId.current === conversationId && loadGeneration.current === generation
                && assignmentRequest.current === command && refreshRequest.current === request) {
                handleReadError(err)
                throw err
            }
        }
    }, [conversationId, handleReadError, lifetime, metadata])

    useEffect(() => {
        if (!conversationId || !visible || error || scope?.managedPolling) return
        let stopped = false
        let timer: ReturnType<typeof setTimeout>
        const tick = async () => {
            if (typeof document === 'undefined' || !document.hidden) await refreshConversation().catch(() => {})
            if (!stopped) timer = setTimeout(tick, 10000)
        }
        timer = setTimeout(tick, 10000)
        return () => { stopped = true; clearTimeout(timer) }
    }, [conversationId, visible, error, refreshConversation, scope?.managedPolling])

    useUiInvalidation('conversation:' + (conversationId ?? ''), !!conversationId,
        event => affectsConversation(event, conversationId, conversation?.workspaceId)
            && (isAccessEvent(event) || ['inbox.invalidated', 'messages.invalidated', 'clients.invalidated',
                'channels.invalidated', 'employees.invalidated', 'workspace.invalidated'].includes(event.type)),
        async signal => {
            if (error) { await loadConversation(false, signal, true); return }
            await refreshConversation(signal)
            const target = latestConversation.current
            if (!signal.aborted && target) await loadDetails(target, false, signal)
            if (!signal.aborted && target && canManageAssignments) {
                const allowed = lifetime.capture()
                const items = await lifetime.read(requestSignal => getWorkspaceEmployees(target.workspaceId, requestSignal), signal)
                if (allowed() && !signal.aborted) setEmployees(items)
            }
        }, () => handleReadError(Object.assign(new Error('Проверка доступа к диалогу'), { status: 403 })), visible)

    // Loading metadata and retaining the composer do not imply that the
    // operator can see the thread. In particular the mobile list hides it.
    useEffect(() => {
        if (!visible || loading || messagesLoading || messagesError || !isEmployee || loadedLifetime.current !== lifetime
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
    }, [visible, loading, messagesLoading, messagesError, isEmployee, conversationId, conversation?.id, conversation?.unreadCount, lifetime])

    if (!conversationId) {
        return (
            <div className="conversation-empty">
                Выберите диалог
            </div>
        )
    }

    if (loading || (conversation && (conversation.id !== conversationId || loadedLifetime.current !== lifetime))) {
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
        const allowed = lifetime.capture()
        const canNotify = () => !scope || (scope.isCurrent() && scope.accessVersion === accessVersion)
        if (assignmentPending.current === id) return
        assignmentPending.current = id
        let complete!: () => void
        const pending = { id, promise: new Promise<void>(resolve => { complete = resolve }) }
        assignmentFlight.current = pending
        const request = ++assignmentRequest.current
        setActionError(null)
        try {
            const updated = await operation()
            if (!canNotify()) return
            publishConversationUpdate(updated)
            if (!allowed()) return
            if (currentConversationId.current !== id || assignmentRequest.current !== request || loadGeneration.current !== generation) return
            setConversation(updated)
            if (tab) navigate(`/inbox/${id}?tab=${tab}`, { replace: true })
        } catch (err) {
            if (currentConversationId.current !== id || assignmentRequest.current !== request || loadGeneration.current !== generation) return
            setActionError(err instanceof Error ? err.message : 'Не удалось изменить ответственного')
            publishConversationUpdate(conversation)
            // The server may have rejected a concurrent assignment. Re-read its
            // state without clearing the draft or hiding the operation error.
            try {
                const updated = await metadata.read(undefined, true)
                if (currentConversationId.current === id && assignmentRequest.current === request && loadGeneration.current === generation) {
                    setConversation(updated)
                    publishConversationUpdate(updated)
                }
            } catch (failure) {
                if (currentConversationId.current === id && assignmentRequest.current === request && loadGeneration.current === generation
                    && [403, 404].includes(getApiErrorStatus(failure) ?? 0)) {
                    handleReadError(failure)
                }
            }
        } finally {
            if (assignmentRequest.current === request) assignmentPending.current = null
            if (assignmentFlight.current === pending) assignmentFlight.current = undefined
            complete()
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
        if (!lifetime.isCurrent() || currentConversationId.current !== conversation.id) return
        if (message) acceptMessage(message)
        await refreshConversation()
    }
    const detailsGeneration = loadGeneration.current
    const handleClientChanged = (account: ClientAccountSummary) => {
        // A confirmed command invalidates the original workspace list even
        // after this view has navigated elsewhere or has been unmounted.
        if (scope && (!scope.isCurrent() || scope.accessVersion !== accessVersion)) return
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
                visible={visible}
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
