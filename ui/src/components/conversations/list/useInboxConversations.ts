import { useCallback, useEffect, useRef, useState } from 'react'
import type { ConversationDto, ConversationSearchRequest } from '../../../api/types/conversation'
import { loadInboxConversations } from './inboxData'
import { getApiErrorStatus } from '../../../api/apiClient'
import { useUiInvalidation } from '../../../api/events/InvalidationContext'
import { bindRequestBudget } from '../../../api/events/requestBudget'
import { abortError, withCancellation } from '../../../api/events/requestScope'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../../../api/events/uiScope'

type InboxData = NonNullable<Awaited<ReturnType<typeof loadInboxConversations>>>

export function useInboxConversations(workspaceId: string, request: ConversationSearchRequest, enabled: boolean, visible = true) {
    const scope = useUiScope()
    const [result, setResult] = useState<{ key: string; data: InboxData; scope: object | null } | null>(null)
    const activeRequest = useRef<AbortController>()
    const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
    const [pending, setPending] = useState(false)
    const [version, setVersion] = useState(0)
    const generation = useRef(0)
    const refresh = useRef<(signal?: AbortSignal) => Promise<void>>(async () => {})
    const queryKey = JSON.stringify([workspaceId, request])
    const reload = useCallback(() => {
        // Invalidate immediately, before React runs the next effect cleanup.
        generation.current += 1
        setVersion(current => current + 1)
    }, [])

    useEffect(() => {
        if (!enabled) return
        let active = true
        const currentGeneration = ++generation.current
        const isCurrent = () => active && generation.current === currentGeneration && (!scope || scope.isCurrent())
        const [, query] = JSON.parse(queryKey) as [string, ConversationSearchRequest]
        let running: Promise<void> | undefined
        let controller: AbortController | undefined
        const load = (signal?: AbortSignal): Promise<void> => {
            if (!isCurrent()) return Promise.resolve()
            if (signal?.aborted) return Promise.reject(abortError())
            if (running) {
                const previous = running, previousController = controller
                // Ready requires a read started after ready, not just the earlier initial request.
                const cancel = () => previousController?.abort()
                signal?.addEventListener('abort', cancel, { once: true })
                return previous.finally(() => signal?.removeEventListener('abort', cancel))
                    .then(() => load(signal))
            }
            const request = new AbortController()
            bindRequestBudget(request.signal, scope?.requestBudget)
            activeRequest.current = request
            controller = request
            const allowed = () => isCurrent() && !request.signal.aborted && !signal?.aborted
            setPending(true)
            setFailure(null)
            running = withCancellation(async requestSignal => {
                const data = await loadInboxConversations(workspaceId, query,
                    () => allowed() && !requestSignal.aborted, requestSignal)
                if (allowed() && !requestSignal.aborted && data) setResult({ key: queryKey, data, scope })
            }, [request.signal, ...(signal ? [signal] : [])])
            .catch(err => {
                if (allowed()) {
                    if ([403, 404].includes(getApiErrorStatus(err) ?? 0)) setResult(null)
                    setFailure({ key: queryKey, message: err instanceof Error ? err.message : 'Не удалось загрузить диалоги' })
                }
                // A confirmed denial is terminal for this scope, not a transient retry loop.
                if (![403, 404].includes(getApiErrorStatus(err) ?? 0)) throw err
            })
            .finally(() => {
                if (controller === request) {
                    running = undefined; controller = undefined
                    if (isCurrent()) setPending(false)
                }
            })
            return running
        }
        refresh.current = load
        void load().catch(() => {})
        return () => { active = false; controller?.abort() }
    }, [workspaceId, queryKey, enabled, version, scope])

    const invalidate = useUiInvalidation(queryKey, enabled, event => affectsWorkspace(event, workspaceId)
        && (isAccessEvent(event) || event.type === 'inbox.invalidated' || event.type === 'messages.invalidated'),
        async signal => { if (!signal.aborted) await refresh.current(signal) }, event => {
            activeRequest.current?.abort()
            const id = event?.resource?.kind === 'conversation' ? event.resource.id : null
            if (id) setResult(current => current && ({ ...current, data: { ...current.data,
                items: current.data.items.filter(item => item.conversation.id !== id),
            } }))
            else setResult(null)
            setFailure(null); reload()
        }, visible)

    useEffect(() => {
        const onUpdated = (event: Event) => {
            const updated = (event as CustomEvent<ConversationDto>).detail
            if (updated?.id && updated.workspaceId === workspaceId) {
                // Local read/send/assignment results join the same dirty queue as SSE.
                // Cancelling this read would fail ready and repeatedly reconnect the stream.
                if (invalidate) invalidate()
                else reload()
            }
        }
        const onUnavailable = (event: Event) => {
            const id = (event as CustomEvent<{ id: string }>).detail?.id
            if (!id) return
            // A confirmed denial must hide the card even if reconciliation
            // fails. Invalidate pending reads before requesting a fresh list.
            setResult(current => current && ({ ...current, data: { ...current.data,
                items: current.data.items.filter(item => item.conversation.id !== id),
            } }))
            reload()
        }
        window.addEventListener('clientbus:conversation-updated', onUpdated)
        window.addEventListener('clientbus:conversation-unavailable', onUnavailable)
        return () => {
            window.removeEventListener('clientbus:conversation-updated', onUpdated)
            window.removeEventListener('clientbus:conversation-unavailable', onUnavailable)
        }
    }, [workspaceId, reload, invalidate])

    const error = enabled && failure?.key === queryKey ? failure.message : null
    const data = enabled && result?.key === queryKey && result.scope === scope ? result.data : null
    return {
        // A refresh must not remove mounted rows (scroll and group menus).
        // A different query still has no data until its own response arrives.
        items: data?.items ?? [],
        channels: data?.channels ?? [],
        loading: enabled && !data && (pending || !error),
        refreshing: enabled && !!data && pending,
        error,
        reload,
    }
}
