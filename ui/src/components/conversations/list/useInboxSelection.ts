import { useEffect } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import type { ConversationListItem } from '../../../api/types/conversation'
import {
    conversationUrl, normalizeSelection, selectionFromTab,
    type InboxAccess, type InboxSelection,
} from './inboxSelection'

export function useInboxSelection(access: InboxAccess) {
    const navigate = useNavigate()
    const { conversationId } = useParams<{ conversationId?: string }>()
    const [params] = useSearchParams()
    const requested = selectionFromTab(params.get('tab'), params.get('groupId'))
    const selection = normalizeSelection(requested, access)
    const canonicalUrl = conversationUrl(conversationId, selection)

    useEffect(() => {
        const canonicalParams = new URLSearchParams(canonicalUrl.split('?')[1])
        if (params.has('tab') && (
            params.get('tab') !== canonicalParams.get('tab') ||
            params.get('groupId') !== canonicalParams.get('groupId')
        )) {
            navigate(canonicalUrl, { replace: true })
        }
    }, [canonicalUrl, navigate, params])

    const select = (next: InboxSelection) => {
        navigate(conversationUrl(conversationId, normalizeSelection(next, access)), { replace: true })
    }

    return {
        selection,
        conversationId,
        select,
        openConversation: (id: string) => navigate(conversationUrl(id, selection)),
    }
}

export function useFirstConversation(
    selection: InboxSelection,
    conversationId: string | undefined,
    items: ConversationListItem[],
    loading: boolean,
    error: string | null,
) {
    const navigate = useNavigate()
    const firstId = !conversationId && !loading && !error ? items[0]?.conversation.id : undefined
    const firstUrl = firstId ? conversationUrl(firstId, selection) : null
    useEffect(() => {
        if (firstUrl) navigate(firstUrl, { replace: true })
    }, [firstUrl, navigate])
}
