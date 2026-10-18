import { apiFetch } from './apiClient'

import type {
    MessageAttachmentDto,
    MessageDto,
    MessagePage,
    MessageType
} from './types/message'

export interface SyncConversationHistoryResult {
    messages: MessageDto[]
    historyStartReached: boolean
}

export async function getConversationMessages(
    conversationId: string,
    page = 0,
    size = 50
): Promise<MessagePage> {
    const response = await apiFetch(
        `/api/messages/conversation/${conversationId}?page=${page}&size=${size}`
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
}

export async function getMessageAttachments(
    messageId: string
): Promise<MessageAttachmentDto[]> {
    const response = await apiFetch(
        `/api/messages/${messageId}/attachments`
    )

    return response.json()
}

export async function getMessageAttachmentBlob(
    messageId: string,
    attachmentId: string
): Promise<Blob> {
    const response = await apiFetch(
        `/api/messages/${messageId}/attachments/${attachmentId}`
    )

    return response.blob()
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

export async function syncConversationHistory(
    conversationId: string,
    beforeExternalId: string | null,
    limit = 50,
): Promise<SyncConversationHistoryResult> {
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

    const response = await apiFetch(
        `/api/messages/conversation/${conversationId}/history/sync?${params.toString()}`,
        {
            method: 'POST',
        },
    )

    return response.json()
}

export async function getMessage(messageId: string): Promise<MessageDto> {
    const response = await apiFetch(`/api/messages/${messageId}`)
    return response.json()
}
