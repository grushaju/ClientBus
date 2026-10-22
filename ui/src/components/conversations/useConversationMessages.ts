import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getConversationMessages, getMessage, retryOutboundMessage, syncConversationHistory } from '../../api/messageApi'
import { getApiErrorStatus } from '../../api/apiClient'
import type { MessageDto } from '../../api/types/message'
import { useUiInvalidation } from '../../api/events/InvalidationContext'
import { withCancellation } from '../../api/events/requestScope'
import { affectsConversation, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { bindRequestBudget, type RequestBudget } from '../../api/events/requestBudget'

const PAGE_SIZE = 100
const REFRESH_INTERVAL = 5000

export function mergeMessages(current: MessageDto[], incoming: MessageDto[]): MessageDto[] {
    const byId = new Map(current.map(message => [message.id, message]))
    incoming.forEach(message => byId.set(message.id, message))
    return [...byId.values()].sort((left, right) => {
        const time = Date.parse(left.sentAt ?? left.createdAt) - Date.parse(right.sentAt ?? right.createdAt)
        return time || left.id.localeCompare(right.id)
    })
}

function newestStableMessage(items: MessageDto[]): string | null {
    // Pending messages with null sentAt sort ahead of sent messages in the DB,
    // and failed messages can move when retried. Neither is a stable frontier.
    const stable = mergeMessages([], items.filter(message => message.sentAt &&
        (message.direction === 'INBOUND' || ['SENT', 'DELIVERED', 'READ'].includes(message.deliveryStatus))))
    return stable[stable.length - 1]?.id ?? null
}

function createSession(conversationId: string | undefined, budget?: RequestBudget) {
    return { conversationId, budget, active: true, unavailable: false, generation: 0, initialPending: false, olderPending: false,
        refreshPending: false, refreshFailure: undefined as { error: unknown } | undefined,
        statusRemaining: undefined as Set<string> | undefined, localPage: 0, localHasMore: true, refreshAnchor: null as string | null,
        reads: new Set<AbortController>(), retrying: new Set<string>(), messageVersions: new Map<string, number>() }
}
type Session = ReturnType<typeof createSession>
function beginRead(session: Session) {
    const controller = new AbortController()
    bindRequestBudget(controller.signal, session.budget)
    session.reads.add(controller)
    return {
        signal: controller.signal,
        read: <T,>(action: (signal: AbortSignal) => Promise<T>) => withCancellation(action, [controller.signal], 35_000),
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
        session.olderPending = false
        session.refreshPending = false
        session.localPage = 0
        session.localHasMore = true
        update(previous => ({ ...previous, loading: true, loadingOlder: false, error: null, refreshError: null }))
        try {
            const page = await request.read(signal => getConversationMessages(conversationId, 0, PAGE_SIZE, signal))
            if (!isCurrent(generation) || request.signal.aborted) return
            session.localPage = 1
            session.localHasMore = !page.last
            let messages = mergeMessages([], page.content)
            let historyStartReached = false
            if (!messages.length) {
                const result = await request.read(signal => syncConversationHistory(conversationId, null, PAGE_SIZE, signal))
                if (!isCurrent(generation) || request.signal.aborted) return
                messages = mergeMessages([], result.messages)
                historyStartReached = result.historyStartReached
            }
            session.refreshAnchor = newestStableMessage(messages)
            update(previous => ({ ...previous, messages: mergeMessages(messages, previous.messages.filter(message =>
                (session.messageVersions.get(message.id) ?? 0) !== (versions.get(message.id) ?? 0))), hasMore: !historyStartReached,
                historyStartReached, error: null }))
        } catch (error) {
            if (request.signal.aborted) return
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось загрузить сообщения' }))
        } finally {
            request.close()
            if (isCurrent(generation)) {
                session.initialPending = false
                update(previous => ({ ...previous, loading: false }))
            }
        }
    }, [session, conversationId, update, handleUnavailable])

    const loadOlder = useCallback(async (manual = false, onStart?: () => void) => {
        const snapshot = latest.current
        if (!conversationId || session.unavailable || session.initialPending || session.olderPending || session.refreshPending
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
                const page = await request.read(signal => getConversationMessages(conversationId, session.localPage, PAGE_SIZE, signal))
                if (!isCurrent(generation) || request.signal.aborted) return
                session.localPage += 1
                session.localHasMore = !page.last && page.content.length > 0
                incoming = page.content
            } else {
                const cursor = snapshot.messages.find(message => message.externalId)?.externalId ?? null
                const result = await request.read(signal => syncConversationHistory(conversationId, cursor, PAGE_SIZE, signal))
                if (!isCurrent(generation) || request.signal.aborted) return
                incoming = result.messages
                historyStartReached = result.historyStartReached
                const existing = new Set(snapshot.messages.map(message => message.id))
                hasMore = !historyStartReached && incoming.some(message => !existing.has(message.id))
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
                update(previous => ({ ...previous, loadingOlder: false }))
            }
        }
    }, [session, conversationId, update, handleUnavailable])

    const refreshMessages = useCallback(async () => {
        if (!conversationId || session.unavailable || session.initialPending || session.refreshPending || session.olderPending) return
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
            }
            session.localPage = Math.max(session.localPage, pageNumber + 1)
            session.refreshAnchor = newestStableMessage(pageMessages)
            const unchangedVersion = (message: MessageDto) =>
                (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0)
            update(previous => ({ ...previous, messages: mergeMessages(previous.messages, pageMessages.filter(unchangedVersion)),
                refreshError: null, ...(recoveredInitial ? { error: null } : {}) }))

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
        } catch (error) {
            if (request.signal.aborted) return
            if (handleUnavailable(error, generation)) return
            session.refreshFailure = { error }
            if (isCurrent(generation)) update(previous => ({ ...previous,
                refreshError: error instanceof Error ? error.message : 'Не удалось обновить сообщения' }))
        } finally {
            request.close()
            if (isCurrent(generation)) session.refreshPending = false
        }
    }, [session, conversationId, update, handleUnavailable])

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
                && (session.initialPending || session.refreshPending || session.olderPending)) {
                await new Promise(resolve => setTimeout(resolve, 25))
            }
            if (signal.aborted || !session.active || session.unavailable) return
            await refreshMessages()
            if (session.refreshFailure) throw session.refreshFailure.error
            } finally { signal.removeEventListener('abort', cancel) }
        }, () => {
            handleUnavailable(Object.assign(new Error('Проверка доступа к диалогу'), { status: 403 }), session.generation)
        }, visible)

    return { ...current, reloadMessages, refreshMessages, acceptMessage, retryMessage,
        loadOlderMessages: useCallback((onStart?: () => void) => loadOlder(false, onStart), [loadOlder]),
        retryOlderMessages: useCallback((onStart?: () => void) => loadOlder(true, onStart), [loadOlder]) }
}
