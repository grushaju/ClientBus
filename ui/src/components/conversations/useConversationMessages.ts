import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getConversationMessages, getMessage, retryOutboundMessage, syncConversationHistory, resumeConversationHistory } from '../../api/messageApi'
import { historyOperationsEnabled, hasHistoryRecovery, wakeHistory } from '../../api/historySyncApi'
import { getApiErrorStatus } from '../../api/apiClient'
import type { MessageDto } from '../../api/types/message'
import { useUiInvalidation } from '../../api/events/InvalidationContext'
import { withCancellation } from '../../api/events/requestScope'
import { affectsConversation, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { bindRequestBudget, type RequestBudget } from '../../api/events/requestBudget'

const PAGE_SIZE = 100
const REFRESH_INTERVAL = 5000

function compareTimestamps(left: string | null | undefined, right: string | null | undefined): number {
    const leftMillis = typeof left === 'string' ? Date.parse(left) : NaN
    const rightMillis = typeof right === 'string' ? Date.parse(right) : NaN
    // A newly accepted message may not have its persistence timestamp yet.
    // Keep unknown times last until REST reconciliation supplies the real value.
    if (!Number.isFinite(leftMillis)) return Number.isFinite(rightMillis) ? 1 : 0
    if (!Number.isFinite(rightMillis)) return -1
    const millis = leftMillis - rightMillis
    if (millis) return millis
    // REST Instants retain PostgreSQL microseconds (and may include nanoseconds).
    // Date.parse alone collapses these into one millisecond and changes keyset order.
    const remainder = (value: string | null | undefined) => Number((value?.match(/\.(\d+)/)?.[1] ?? '').padEnd(9, '0').slice(3, 9))
    return remainder(left) - remainder(right)
}

export function mergeMessages(current: MessageDto[], incoming: MessageDto[]): MessageDto[] {
    const byId = new Map(current.map(message => [message.id, message]))
    incoming.forEach(message => byId.set(message.id, message))
    return [...byId.values()].sort((left, right) => {
        const time = compareTimestamps(left.sentAt ?? left.createdAt, right.sentAt ?? right.createdAt)
        return time || compareTimestamps(left.createdAt, right.createdAt) || left.id.localeCompare(right.id)
    })
}

function newestStableMessage(items: MessageDto[]): string | null {
    // Pending/failed messages can move when retried. Neither is a stable frontier.
    const stable = mergeMessages([], items.filter(message => message.sentAt &&
        (message.direction === 'INBOUND' || ['SENT', 'DELIVERED', 'READ'].includes(message.deliveryStatus))))
    return stable[stable.length - 1]?.id ?? null
}

function oldestExternalMessage(items: MessageDto[]): MessageDto | undefined {
    return items.find(message => message.externalId && (message.direction === 'INBOUND'
        || ['SENT', 'DELIVERED', 'READ'].includes(message.deliveryStatus)))
}

function createSession(conversationId: string | undefined, budget?: RequestBudget) {
    return { conversationId, budget, active: true, unavailable: false, generation: 0, initialPending: false, olderPending: false,
        historyPending: false, historyRecoveryNeeded: false,
        refreshPending: false, refreshFailure: undefined as { error: unknown } | undefined,
        statusRemaining: undefined as Set<string> | undefined, localPage: 0, localHasMore: true,
        localAnchor: null as string | null, refreshAnchor: null as string | null,
        reads: new Set<AbortController>(), retrying: new Set<string>(), messageVersions: new Map<string, number>() }
}
type Session = ReturnType<typeof createSession>
function beginRead(session: Session) {
    const controller = new AbortController()
    bindRequestBudget(controller.signal, session.budget)
    session.reads.add(controller)
    return {
        signal: controller.signal,
        read: <T,>(action: (signal: AbortSignal) => Promise<T>, timeout = 35_000) => withCancellation(action, [controller.signal], timeout),
        close: () => { session.reads.delete(controller) },
    }
}
function emptyState(session: Session) {
    return { session, messages: [] as MessageDto[], loading: !!session.conversationId, loadingOlder: false,
        hasMore: !!session.conversationId, historyStartReached: false, error: null as string | null,
        refreshError: null as string | null, retryingIds: [] as string[] }
}
type State = ReturnType<typeof emptyState>

export function useConversationMessages(conversationId: string | undefined, visible = true, onUnavailable?: (error: unknown) => void,
    workspaceId?: string | null) {
    const scope = useUiScope()
    const session = useMemo(() => createSession(conversationId, scope?.requestBudget), [conversationId, scope])
    const currentSession = useRef(session)
    currentSession.current = session
    const unavailableHandler = useRef(onUnavailable)
    unavailableHandler.current = onUnavailable
    const [state, setState] = useState(() => emptyState(session))
    const current = state.session === session ? state : emptyState(session)
    const latest = useRef(current)
    latest.current = current
    const update = useCallback((change: (state: State) => State) => {
        if (!session.active) return
        setState(previous => session.active ? change(previous.session === session ? previous : emptyState(session)) : previous)
    }, [session])
    const isCurrent = (generation: number) => session.active && currentSession.current === session
        && (!scope || scope.isCurrent()) && session.generation === generation
    const handleUnavailable = useCallback((error: unknown, generation: number) => {
        if (!isCurrent(generation) || ![403, 404].includes(getApiErrorStatus(error) ?? 0)) return false
        session.unavailable = true
        session.statusRemaining = undefined
        session.reads.forEach(controller => controller.abort())
        session.generation += 1
        session.initialPending = session.olderPending = session.refreshPending = false
        session.historyPending = session.historyRecoveryNeeded = false
        update(() => ({ ...emptyState(session), loading: false, hasMore: false,
            error: error instanceof Error ? error.message : 'Диалог недоступен' }))
        unavailableHandler.current?.(error)
        return true
    }, [session, update])

    const reloadMessages = useCallback(async () => {
        if (!conversationId) return
        session.reads.forEach(controller => controller.abort())
        session.unavailable = false
        const generation = ++session.generation
        session.statusRemaining = undefined
        const request = beginRead(session)
        const versions = new Map(session.messageVersions)
        session.initialPending = true
        session.historyPending = session.historyRecoveryNeeded = false
        session.olderPending = false
        session.refreshPending = false
        session.localPage = 0
        session.localHasMore = true
        session.localAnchor = null
        update(previous => ({ ...previous, loading: true, loadingOlder: false, error: null, refreshError: null }))
        try {
            const page = await request.read(signal => getConversationMessages(conversationId, 0, PAGE_SIZE, signal))
            if (!isCurrent(generation) || request.signal.aborted) return
            session.localPage = 1
            session.localHasMore = !page.last
            session.localAnchor = page.content[page.content.length - 1]?.id ?? null
            let messages = mergeMessages([], page.content)
            let historyMessages: MessageDto[] = []
            let historyStartReached = false
            let historyError: string | null = null
            const context = scope?.historyAccount ? { account: scope.historyAccount, workspaceId,
                anchorMessageId: oldestExternalMessage(messages)?.id ?? null } : undefined
            // History status waiting is independent of cache visibility and live reconciliation.
            session.initialPending = false
            session.historyPending = !!context
            session.historyRecoveryNeeded = !!context && typeof resumeConversationHistory === 'function'
            session.refreshAnchor = newestStableMessage(messages)
            update(previous => ({ ...previous, messages: mergeMessages(messages, previous.messages.filter(message =>
                (session.messageVersions.get(message.id) ?? 0) !== (versions.get(message.id) ?? 0))),
                loading: !messages.length, loadingOlder: !!context && messages.length > 0, error: null }))
            const resumed = context && typeof resumeConversationHistory === 'function'
                ? await request.read(signal => resumeConversationHistory(conversationId, context, signal), 180_000) : null
            if (!isCurrent(generation) || request.signal.aborted) return
            session.historyRecoveryNeeded = false
            if (resumed) {
                historyMessages = resumed.messages
                messages = mergeMessages(messages, resumed.messages)
                historyStartReached = resumed.historyStartReached
                historyError = resumed.error ?? null
                session.localHasMore = resumed.cacheHasMore ?? session.localHasMore
                session.localAnchor = resumed.cacheAnchorMessageId ?? session.localAnchor
            }
            if (!messages.length && !resumed) {
                session.historyPending = true
                const result = await request.read(signal => syncConversationHistory(conversationId, null, PAGE_SIZE, signal, context), 180_000)
                if (!isCurrent(generation) || request.signal.aborted) return
                historyMessages = result.messages
                messages = mergeMessages([], result.messages)
                historyStartReached = result.historyStartReached
                historyError = result.error ?? null
                session.localHasMore = result.cacheHasMore ?? session.localHasMore
                session.localAnchor = result.cacheAnchorMessageId ?? session.localAnchor
            }
            session.refreshAnchor ??= newestStableMessage(messages)
            update(previous => ({ ...previous, messages: mergeMessages(previous.messages, historyMessages.filter(message =>
                (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0))), hasMore: !historyStartReached,
                historyStartReached, error: historyError }))
        } catch (error) {
            if (request.signal.aborted) return
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось загрузить сообщения' }))
        } finally {
            request.close()
            if (isCurrent(generation)) {
                session.initialPending = false
                session.historyPending = false
                update(previous => ({ ...previous, loading: false, loadingOlder: false }))
            }
        }
    }, [session, conversationId, update, handleUnavailable])

    const loadOlder = useCallback(async (manual = false, onStart?: () => void) => {
        const snapshot = latest.current
        if (!conversationId || session.unavailable || session.initialPending || session.olderPending || session.historyPending || session.refreshPending
            || snapshot.historyStartReached || (!manual && (!snapshot.hasMore || !!snapshot.error))) return
        const generation = session.generation
        const request = beginRead(session)
        const versions = new Map(session.messageVersions)
        session.olderPending = true
        onStart?.()
        update(previous => ({ ...previous, loadingOlder: true, error: null }))
        try {
            let incoming: MessageDto[]
            let historyStartReached = false
            let hasMore = true
            if (session.localHasMore) {
                const stable = !!scope?.historyAccount && historyOperationsEnabled()
                const page = await request.read(signal => getConversationMessages(conversationId, stable ? 0 : session.localPage,
                    PAGE_SIZE, signal, stable ? session.localAnchor ?? undefined : undefined))
                if (!isCurrent(generation) || request.signal.aborted) return
                session.localPage += 1
                session.localHasMore = !page.last && page.content.length > 0
                session.localAnchor = page.content[page.content.length - 1]?.id ?? session.localAnchor
                incoming = page.content
            } else {
                const frontier = scope?.historyAccount ? oldestExternalMessage(snapshot.messages)
                    : snapshot.messages.find(message => message.externalId)
                const cursor = frontier?.externalId ?? null
                const context = scope?.historyAccount ? { account: scope.historyAccount, workspaceId,
                    anchorMessageId: frontier?.id ?? null, manual } : undefined
                session.historyPending = true
                const result = await request.read(signal => syncConversationHistory(conversationId, cursor, PAGE_SIZE, signal, context), 180_000)
                if (!isCurrent(generation) || request.signal.aborted) return
                incoming = result.messages
                historyStartReached = result.historyStartReached
                session.localHasMore = result.cacheHasMore ?? session.localHasMore
                session.localAnchor = result.cacheAnchorMessageId ?? session.localAnchor
                if (result.error) update(previous => ({ ...previous, error: result.error ?? null }))
                const existing = new Set(snapshot.messages.map(message => message.id))
                hasMore = !historyStartReached && (!!result.cacheHasMore || incoming.some(message => !existing.has(message.id)))
            }
            incoming = incoming.filter(message => (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0))
            update(previous => ({ ...previous, messages: mergeMessages(previous.messages, incoming),
                historyStartReached, hasMore }))
        } catch (error) {
            if (request.signal.aborted) return
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось загрузить старые сообщения' }))
        } finally {
            request.close()
            if (isCurrent(generation)) {
                session.olderPending = false
                session.historyPending = false
                update(previous => ({ ...previous, loadingOlder: false }))
            }
        }
    }, [session, conversationId, update, handleUnavailable])

    const recoverHistory = useCallback(async () => {
        if (!conversationId || !scope?.historyAccount || session.historyPending || session.unavailable) return
        const generation = session.generation
        const request = beginRead(session)
        const versions = new Map(session.messageVersions)
        const retryDiscovery = session.historyRecoveryNeeded
        const context = { account: scope.historyAccount, workspaceId,
            anchorMessageId: oldestExternalMessage(latest.current.messages)?.id ?? null }
        session.historyPending = true
        update(previous => ({ ...previous, loadingOlder: true }))
        try {
            let recovered = await request.read(signal => resumeConversationHistory(conversationId, context, signal), 180_000)
            if (!isCurrent(generation) || request.signal.aborted) return
            // A failed discovery on an empty first load must not permanently skip
            // the initial submission once discovery becomes available again.
            if (!recovered && retryDiscovery && !latest.current.messages.length) {
                recovered = await request.read(signal => syncConversationHistory(conversationId, null, PAGE_SIZE, signal, context), 180_000)
                if (!isCurrent(generation) || request.signal.aborted) return
            }
            session.historyRecoveryNeeded = false
            if (recovered) {
                const result = recovered
                session.localHasMore = result.cacheHasMore ?? session.localHasMore
                session.localAnchor = result.cacheAnchorMessageId ?? session.localAnchor
                update(previous => ({ ...previous, messages: mergeMessages(previous.messages, result.messages.filter(message =>
                    (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0))),
                    loading: false, historyStartReached: result.historyStartReached, hasMore: !result.historyStartReached,
                    error: result.error ?? null }))
            } else if (retryDiscovery) update(previous => ({ ...previous, error: null }))
        } catch (error) {
            if (request.signal.aborted || handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось восстановить загрузку истории' }))
        } finally {
            request.close()
            if (isCurrent(generation)) {
                session.historyPending = false
                update(previous => ({ ...previous, loadingOlder: session.olderPending }))
            }
        }
    }, [session, conversationId, scope, workspaceId, update, handleUnavailable])

    const refreshMessages = useCallback(async () => {
        if (!conversationId || session.unavailable || session.initialPending || session.refreshPending
            || (session.olderPending && !session.historyPending)) return
        const generation = session.generation
        const request = beginRead(session)
        const versions = new Map(session.messageVersions)
        const snapshot = latest.current
        const anchor = session.refreshAnchor
        session.refreshPending = true
        session.refreshFailure = undefined
        try {
            let page = await request.read(signal => getConversationMessages(conversationId, 0, PAGE_SIZE, signal))
            if (!isCurrent(generation) || request.signal.aborted) return
            const firstPage = page
            const incomingById = new Map(page.content.map(message => [message.id, message]))
            let pageNumber = 0
            // Returning to a busy conversation can reveal several new pages.
            // Read back to the previous server frontier, not a newly sent local
            // message, which may sit above a still-unfilled gap.
            while ((anchor ? !incomingById.has(anchor) : snapshot.messages.length > 0 || snapshot.historyStartReached)
                && !page.last && page.content.length > 0) {
                pageNumber++
                page = await request.read(signal => getConversationMessages(conversationId, pageNumber, PAGE_SIZE, signal))
                if (!isCurrent(generation) || request.signal.aborted) return
                const previousSize = incomingById.size
                page.content.forEach(message => incomingById.set(message.id, message))
                if (incomingById.size === previousSize && !page.last && page.content.length > 0) {
                    throw new Error('Не удалось дочитать новые сообщения. Повторите обновление.')
                }
            }
            const pageMessages = mergeMessages([], [...incomingById.values()])
            const recoveredInitial = snapshot.messages.length === 0 && !!snapshot.error && pageMessages.length > 0
            if (recoveredInitial && session.localPage === 0) {
                session.localPage = 1
                session.localHasMore = !firstPage.last
                session.localAnchor = firstPage.content[firstPage.content.length - 1]?.id ?? null
            }
            session.localPage = Math.max(session.localPage, pageNumber + 1)
            session.refreshAnchor = newestStableMessage(pageMessages)
            const unchangedVersion = (message: MessageDto) =>
                (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0)
            update(previous => ({ ...previous, messages: mergeMessages(previous.messages, pageMessages.filter(unchangedVersion)),
                refreshError: null, ...(pageMessages.length ? { loading: false } : {}), ...(recoveredInitial ? { error: null } : {}) }))

            // Keep a finite status pass across deadlines. Even unchanged SENT/
            // DELIVERED responses count as progress so they cannot starve later IDs.
            const eligible = snapshot.messages.filter(message => message.direction === 'OUTBOUND'
                && ['PENDING', 'SENT', 'DELIVERED'].includes(message.deliveryStatus)
                && message.processingStatus !== 'FAILED' && !incomingById.has(message.id))
            const eligibleIds = new Set(eligible.map(message => message.id))
            const remaining = session.statusRemaining ??= new Set(eligibleIds)
            for (const id of remaining) if (!eligibleIds.has(id)) remaining.delete(id)
            const pending = eligible.filter(message => remaining.has(message.id))
            let next = 0
            let statusFailure: { error: unknown } | undefined
            await Promise.all(Array.from({ length: Math.min(4, pending.length) }, async () => {
                while (next < pending.length && isCurrent(generation) && !request.signal.aborted && !statusFailure) {
                    const message = pending[next++]
                    try {
                        const status = await request.read(signal => getMessage(message.id, signal))
                        if (!isCurrent(generation) || request.signal.aborted) return
                        update(previous => !isCurrent(generation) ? previous : { ...previous,
                            messages: mergeMessages(previous.messages, unchangedVersion(status) ? [status] : []) })
                        remaining.delete(message.id)
                    }
                    catch (err) {
                        if (request.signal.aborted) return
                        if (handleUnavailable(err, generation)) return
                        statusFailure = { error: err }
                    }
                }
            }))
            if (!isCurrent(generation) || request.signal.aborted) return
            if (remaining.size === 0) session.statusRemaining = undefined
            if (statusFailure) throw statusFailure.error
            // A lost POST/status/cache response is recovered by the normal reconnect/fallback pass.
            if (scope?.historyAccount && !session.historyPending
                && (session.historyRecoveryNeeded || hasHistoryRecovery(scope.historyAccount, conversationId))
                && typeof resumeConversationHistory === 'function') {
                // Status waiting has its own cancellable read. It must not keep
                // the live-message reconciliation subscription busy for minutes.
                void recoverHistory()
            }
        } catch (error) {
            if (request.signal.aborted) return
            if (handleUnavailable(error, generation)) return
            session.refreshFailure = { error }
            if (isCurrent(generation)) update(previous => ({ ...previous,
                refreshError: error instanceof Error ? error.message : 'Не удалось обновить сообщения' }))
        } finally {
            request.close()
            if (isCurrent(generation)) {
                session.refreshPending = false
                update(previous => ({ ...previous, loadingOlder: session.historyPending || session.olderPending }))
            }
        }
    }, [session, conversationId, update, handleUnavailable, recoverHistory])

    const acceptMessage = useCallback((message: MessageDto) => {
        if (message.conversationId !== conversationId || currentSession.current !== session || (scope && !scope.isCurrent())) return
        session.messageVersions.set(message.id, (session.messageVersions.get(message.id) ?? 0) + 1)
        update(previous => ({ ...previous, messages: mergeMessages(previous.messages, [message]) }))
    }, [session, conversationId, update])

    const retryMessage = useCallback(async (messageId: string) => {
        if (!conversationId || session.unavailable || session.retrying.has(messageId)) return
        const generation = session.generation
        session.retrying.add(messageId)
        session.messageVersions.set(messageId, (session.messageVersions.get(messageId) ?? 0) + 1)
        update(previous => ({ ...previous, retryingIds: [...session.retrying] }))
        try {
            const message = await retryOutboundMessage(messageId)
            if (isCurrent(generation)) acceptMessage(message)
        } catch (error) {
            handleUnavailable(error, generation)
            throw error
        } finally {
            session.retrying.delete(messageId)
            if (session.active) update(previous => ({ ...previous, retryingIds: [...session.retrying] }))
        }
    }, [session, conversationId, acceptMessage, update, handleUnavailable])

    useEffect(() => {
        session.active = true
        void reloadMessages()
        return () => { session.active = false; session.generation += 1; session.reads.forEach(controller => controller.abort()) }
    }, [session, reloadMessages])

    useEffect(() => {
        if (!conversationId || !visible || scope?.managedPolling) return
        let timer: ReturnType<typeof setTimeout>
        let cancelled = false
        const tick = async () => {
            if (session.unavailable) return
            if (typeof document === 'undefined' || !document.hidden) await refreshMessages()
            if (!cancelled && !session.unavailable) timer = setTimeout(tick, REFRESH_INTERVAL)
        }
        timer = setTimeout(tick, REFRESH_INTERVAL)
        return () => { cancelled = true; clearTimeout(timer) }
    }, [conversationId, visible, refreshMessages, scope?.managedPolling])

    useUiInvalidation(conversationId ?? '', !!conversationId,
        event => (event.type === 'messages.invalidated' && event.resource?.id === conversationId)
            || (isAccessEvent(event) && affectsConversation(event, conversationId, workspaceId)),
        async signal => {
            const cancel = () => session.reads.forEach(controller => controller.abort())
            signal.addEventListener('abort', cancel, { once: true })
            try {
            while (!signal.aborted && session.active && !session.unavailable
                && (session.initialPending || session.refreshPending || (session.olderPending && !session.historyPending))) {
                await new Promise(resolve => setTimeout(resolve, 25))
            }
            if (signal.aborted || !session.active || session.unavailable) return
            await refreshMessages()
            if (session.refreshFailure) throw session.refreshFailure.error
            } finally { signal.removeEventListener('abort', cancel) }
        }, () => {
            handleUnavailable(Object.assign(new Error('Проверка доступа к диалогу'), { status: 403 }), session.generation)
        }, visible)

    useUiInvalidation('history-operation:' + (conversationId ?? ''), !!conversationId && !!scope?.historyAccount,
        event => event.type === 'history-operation.invalidated', async () => { if (conversationId) wakeHistory(conversationId) },
        undefined, visible)

    return { ...current, reloadMessages, refreshMessages, acceptMessage, retryMessage,
        loadOlderMessages: useCallback((onStart?: () => void) => loadOlder(false, onStart), [loadOlder]),
        retryOlderMessages: useCallback((onStart?: () => void) => loadOlder(true, onStart), [loadOlder]) }
}
