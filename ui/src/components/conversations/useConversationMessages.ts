import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getConversationMessages, getMessage, retryOutboundMessage, syncConversationHistory } from '../../api/messageApi'
import { getApiErrorStatus } from '../../api/apiClient'
import type { MessageDto } from '../../api/types/message'

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

function createSession(conversationId: string | undefined) {
    return { conversationId, active: true, unavailable: false, generation: 0, initialPending: false, olderPending: false,
        refreshPending: false, localPage: 0, localHasMore: true, refreshAnchor: null as string | null,
        retrying: new Set<string>(), messageVersions: new Map<string, number>() }
}
type Session = ReturnType<typeof createSession>
function emptyState(session: Session) {
    return { session, messages: [] as MessageDto[], loading: !!session.conversationId, loadingOlder: false,
        hasMore: !!session.conversationId, historyStartReached: false, error: null as string | null,
        refreshError: null as string | null, retryingIds: [] as string[] }
}
type State = ReturnType<typeof emptyState>

export function useConversationMessages(conversationId: string | undefined, visible = true, onUnavailable?: (error: unknown) => void) {
    const session = useMemo(() => createSession(conversationId), [conversationId])
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
    const isCurrent = (generation: number) => session.active && currentSession.current === session && session.generation === generation
    const handleUnavailable = useCallback((error: unknown, generation: number) => {
        if (!isCurrent(generation) || ![403, 404].includes(getApiErrorStatus(error) ?? 0)) return false
        session.unavailable = true
        session.generation += 1
        session.initialPending = session.olderPending = session.refreshPending = false
        update(() => ({ ...emptyState(session), loading: false, hasMore: false,
            error: error instanceof Error ? error.message : 'Диалог недоступен' }))
        unavailableHandler.current?.(error)
        return true
    }, [session, update])

    const reloadMessages = useCallback(async () => {
        if (!conversationId) return
        session.unavailable = false
        const generation = ++session.generation
        const versions = new Map(session.messageVersions)
        session.initialPending = true
        session.olderPending = false
        session.refreshPending = false
        session.localPage = 0
        session.localHasMore = true
        update(previous => ({ ...previous, loading: true, loadingOlder: false, error: null, refreshError: null }))
        try {
            const page = await getConversationMessages(conversationId, 0, PAGE_SIZE)
            if (!isCurrent(generation)) return
            session.localPage = 1
            session.localHasMore = !page.last
            let messages = mergeMessages([], page.content)
            let historyStartReached = false
            if (!messages.length) {
                const result = await syncConversationHistory(conversationId, null, PAGE_SIZE)
                if (!isCurrent(generation)) return
                messages = mergeMessages([], result.messages)
                historyStartReached = result.historyStartReached
            }
            session.refreshAnchor = newestStableMessage(messages)
            update(previous => ({ ...previous, messages: mergeMessages(messages, previous.messages.filter(message =>
                (session.messageVersions.get(message.id) ?? 0) !== (versions.get(message.id) ?? 0))), hasMore: !historyStartReached,
                historyStartReached, error: null }))
        } catch (error) {
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось загрузить сообщения' }))
        } finally {
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
        const versions = new Map(session.messageVersions)
        session.olderPending = true
        onStart?.()
        update(previous => ({ ...previous, loadingOlder: true, error: null }))
        try {
            let incoming: MessageDto[]
            let historyStartReached = false
            let hasMore = true
            if (session.localHasMore) {
                const page = await getConversationMessages(conversationId, session.localPage, PAGE_SIZE)
                if (!isCurrent(generation)) return
                session.localPage += 1
                session.localHasMore = !page.last && page.content.length > 0
                incoming = page.content
            } else {
                const cursor = snapshot.messages.find(message => message.externalId)?.externalId ?? null
                const result = await syncConversationHistory(conversationId, cursor, PAGE_SIZE)
                if (!isCurrent(generation)) return
                incoming = result.messages
                historyStartReached = result.historyStartReached
                const existing = new Set(snapshot.messages.map(message => message.id))
                hasMore = !historyStartReached && incoming.some(message => !existing.has(message.id))
            }
            incoming = incoming.filter(message => (session.messageVersions.get(message.id) ?? 0) === (versions.get(message.id) ?? 0))
            update(previous => ({ ...previous, messages: mergeMessages(previous.messages, incoming),
                historyStartReached, hasMore }))
        } catch (error) {
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                error: error instanceof Error ? error.message : 'Не удалось загрузить старые сообщения' }))
        } finally {
            if (isCurrent(generation)) {
                session.olderPending = false
                update(previous => ({ ...previous, loadingOlder: false }))
            }
        }
    }, [session, conversationId, update, handleUnavailable])

    const refreshMessages = useCallback(async () => {
        if (!conversationId || session.unavailable || session.initialPending || session.refreshPending || session.olderPending) return
        const generation = session.generation
        const versions = new Map(session.messageVersions)
        const snapshot = latest.current
        const anchor = session.refreshAnchor
        session.refreshPending = true
        try {
            let page = await getConversationMessages(conversationId, 0, PAGE_SIZE)
            if (!isCurrent(generation)) return
            const firstPage = page
            const incomingById = new Map(page.content.map(message => [message.id, message]))
            let pageNumber = 0
            // Returning to a busy conversation can reveal several new pages.
            // Read back to the previous server frontier, not a newly sent local
            // message, which may sit above a still-unfilled gap.
            while ((anchor ? !incomingById.has(anchor) : snapshot.messages.length > 0 || snapshot.historyStartReached)
                && !page.last && page.content.length > 0) {
                page = await getConversationMessages(conversationId, ++pageNumber, PAGE_SIZE)
                if (!isCurrent(generation)) return
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

            // SENT and DELIVERED can still advance to READ outside the newest
            // page. Limit concurrent status requests for long loaded histories.
            const pending = snapshot.messages.filter(message => message.direction === 'OUTBOUND'
                && ['PENDING', 'SENT', 'DELIVERED'].includes(message.deliveryStatus)
                && message.processingStatus !== 'FAILED' && !incomingById.has(message.id))
            const olderStatuses: MessageDto[] = []
            let next = 0
            let statusError: Error | null = null
            await Promise.all(Array.from({ length: Math.min(4, pending.length) }, async () => {
                while (next < pending.length && isCurrent(generation) && !statusError) {
                    const message = pending[next++]
                    try { olderStatuses.push(await getMessage(message.id)) }
                    catch (err) {
                        if (handleUnavailable(err, generation)) return
                        statusError = err instanceof Error ? err : new Error('Не удалось обновить статус сообщения')
                    }
                }
            }))
            if (!isCurrent(generation)) return
            if (olderStatuses.length) update(previous => ({ ...previous,
                messages: mergeMessages(previous.messages, olderStatuses.filter(unchangedVersion)) }))
            if (statusError) throw statusError
        } catch (error) {
            if (handleUnavailable(error, generation)) return
            if (isCurrent(generation)) update(previous => ({ ...previous,
                refreshError: error instanceof Error ? error.message : 'Не удалось обновить сообщения' }))
        } finally {
            if (isCurrent(generation)) session.refreshPending = false
        }
    }, [session, conversationId, update, handleUnavailable])

    const acceptMessage = useCallback((message: MessageDto) => {
        if (message.conversationId !== conversationId) return
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
        return () => { session.active = false; session.generation += 1 }
    }, [session, reloadMessages])

    useEffect(() => {
        if (!conversationId || !visible) return
        let timer: ReturnType<typeof setTimeout>
        let cancelled = false
        const tick = async () => {
            if (session.unavailable) return
            if (typeof document === 'undefined' || !document.hidden) await refreshMessages()
            if (!cancelled && !session.unavailable) timer = setTimeout(tick, REFRESH_INTERVAL)
        }
        timer = setTimeout(tick, REFRESH_INTERVAL)
        return () => { cancelled = true; clearTimeout(timer) }
    }, [conversationId, visible, refreshMessages])

    return { ...current, reloadMessages, refreshMessages, acceptMessage, retryMessage,
        loadOlderMessages: useCallback((onStart?: () => void) => loadOlder(false, onStart), [loadOlder]),
        retryOlderMessages: useCallback((onStart?: () => void) => loadOlder(true, onStart), [loadOlder]) }
}
