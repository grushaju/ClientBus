import { useCallback, useEffect, useRef, useState } from 'react'
import type { ConversationDto, ConversationSearchRequest } from '../../../api/types/conversation'
import { loadInboxConversations } from './inboxData'

type InboxData = NonNullable<Awaited<ReturnType<typeof loadInboxConversations>>>

export function useInboxConversations(workspaceId: string, request: ConversationSearchRequest, enabled: boolean) {
    const [result, setResult] = useState<{ key: string; data: InboxData } | null>(null)
    const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
    const [pending, setPending] = useState(false)
    const [version, setVersion] = useState(0)
    const generation = useRef(0)
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
        const isCurrent = () => active && generation.current === currentGeneration
        const [, query] = JSON.parse(queryKey) as [string, ConversationSearchRequest]
        setPending(true)
        setFailure(null)
        void loadInboxConversations(workspaceId, query, isCurrent)
            .then(data => {
                if (isCurrent() && data) setResult({ key: queryKey, data })
            })
            .catch(err => {
                if (isCurrent()) setFailure({ key: queryKey, message: err instanceof Error ? err.message : 'Не удалось загрузить диалоги' })
            })
            .finally(() => {
                if (isCurrent()) setPending(false)
            })
        return () => { active = false }
    }, [workspaceId, queryKey, enabled, version])

    useEffect(() => {
        const onUpdated = (event: Event) => {
            const updated = (event as CustomEvent<ConversationDto>).detail
            if (updated?.id && updated.workspaceId === workspaceId) reload()
        }
        window.addEventListener('clientbus:conversation-updated', onUpdated)
        return () => window.removeEventListener('clientbus:conversation-updated', onUpdated)
    }, [workspaceId, reload])

    const error = enabled && failure?.key === queryKey ? failure.message : null
    const data = enabled && result?.key === queryKey ? result.data : null
    return {
        // A refresh must not remove mounted rows (scroll and group menus).
        // A different query still has no data until its own response arrives.
        items: data?.items ?? [],
        channels: result?.data.channels ?? [],
        loading: enabled && !data && (pending || !error),
        refreshing: enabled && !!data && pending,
        error,
        reload,
    }
}
