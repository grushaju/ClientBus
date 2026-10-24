import { apiFetch } from './apiClient'
import { withCancellation } from './events/requestScope'
import { completeHistory, historyFailure, historyOperationsEnabled, hasHistoryRecovery, resumeHistory, startHistory,
    type HistoryContext, type HistoryOutcome } from './historySyncApi'

import type {
    MessageAttachmentDto,
    MessageDto,
    MessagePage,
    MessageType
} from './types/message'

export interface SyncConversationHistoryResult {
    messages: MessageDto[]
    historyStartReached: boolean
    error?: string
    cacheHasMore?: boolean
    cacheAnchorMessageId?: string | null
}

export async function getConversationMessages(
    conversationId: string,
    page = 0,
    size = 50,
    signal?: AbortSignal,
    beforeMessageId?: string,
): Promise<MessagePage> {
    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/messages/conversation/${conversationId}?page=${page}&size=${size}${beforeMessageId ? '&beforeMessageId=' + encodeURIComponent(beforeMessageId) : ''}`,
            { signal: requestSignal },
        )

        // Spring's PagedModel exposes metadata under `page`; older responses
        // expose Page fields at the top level. Keep one UI contract for both.
        const result = await response.json()
        const metadata = result.page ?? result
        const number = metadata.number ?? page
        const totalPages = metadata.totalPages ?? 0
        return {
            content: result.content ?? [],
            totalElements: metadata.totalElements ?? 0,
            totalPages,
            size: metadata.size ?? size,
            number,
            first: result.first ?? number === 0,
            last: result.last ?? number + 1 >= totalPages,
            numberOfElements: result.content?.length ?? 0,
            empty: !result.content?.length,
        }
    }, signal ? [signal] : [])
}

export async function getMessageAttachments(
    messageId: string,
    signal?: AbortSignal,
): Promise<MessageAttachmentDto[]> {
    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/messages/${messageId}/attachments`,
            { signal: requestSignal },
        )

        return response.json()
    }, signal ? [signal] : [])
}

export async function getMessageAttachmentBlob(
    messageId: string,
    attachmentId: string,
    signal?: AbortSignal,
): Promise<Blob> {
    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/messages/${messageId}/attachments/${attachmentId}`,
            { signal: requestSignal },
        )

        return response.blob()
    }, signal ? [signal] : [])
}

export async function sendOutboundMessage(
    conversationId: string,
    type: MessageType,
    content: string | null,
    attachments: File[] = [],
    replyToMessageId: string | null = null
): Promise<MessageDto> {
    const formData = new FormData()

    const request = {
        conversationId,
        type,
        content,
        metadata: null,
        replyToMessageId
    }

    formData.append(
        'request',
        new Blob(
            [JSON.stringify(request)],
            {
                type: 'application/json'
            }
        )
    )

    for (const attachment of attachments) {
        formData.append(
            'attachments',
            attachment
        )
    }

    const response = await apiFetch(
        '/api/messages/outbound',
        {
            method: 'POST',
            body: formData
        }
    )

    return response.json()
}

export async function retryOutboundMessage(
    messageId: string
): Promise<MessageDto> {
    const response = await apiFetch(
        `/api/messages/${messageId}/retry`,
        {
            method: 'POST'
        }
    )

    return response.json()
}

async function cachedHistory(conversationId: string, context: HistoryContext, outcome: HistoryOutcome, signal: AbortSignal): Promise<SyncConversationHistoryResult> {
    const page = await getConversationMessages(conversationId, 0, 100, signal, context.anchorMessageId ?? undefined)
    if (signal.aborted) throw signal.reason
    completeHistory(context.account, conversationId, outcome)
    return { messages: page.content, historyStartReached: !!outcome.operation.result?.historyStartReached && page.last,
        cacheHasMore: !page.last, cacheAnchorMessageId: page.content[page.content.length - 1]?.id ?? context.anchorMessageId ?? null,
        ...(outcome.operation.status === 'FAILED' ? { error: historyFailure(outcome.operation.errorCode) } : {}) }
}
export async function resumeConversationHistory(conversationId: string, context: HistoryContext, signal: AbortSignal): Promise<SyncConversationHistoryResult | null> {
    const outcome = await resumeHistory(conversationId, context, signal)
    return outcome ? cachedHistory(conversationId, context, outcome, signal) : null
}
export async function syncConversationHistory(
    conversationId: string,
    beforeExternalId: string | null,
    limit = 50,
    signal?: AbortSignal,
    context?: HistoryContext,
): Promise<SyncConversationHistoryResult> {
    if (context?.account && (historyOperationsEnabled() || hasHistoryRecovery(context.account, conversationId))) {
        return withCancellation(async child => cachedHistory(conversationId, context,
            await startHistory(conversationId, { beforeExternalId, limit }, context, child), child), signal ? [signal] : [], 180_000)
    }
    const params = new URLSearchParams()

    if (beforeExternalId !== null) {
        params.set(
            'beforeExternalId',
            beforeExternalId,
        )
    }

    params.set(
        'limit',
        String(limit),
    )

    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/messages/conversation/${conversationId}/history/sync?${params.toString()}`,
            {
                method: 'POST',
                signal: requestSignal,
            },
        )

        return response.json()
    }, signal ? [signal] : [], 35_000)
}

export async function getMessage(messageId: string, signal?: AbortSignal): Promise<MessageDto> {
    return withCancellation(async requestSignal => {
        const response = await apiFetch(`/api/messages/${messageId}`, { signal: requestSignal })
        return response.json()
    }, signal ? [signal] : [])
}
