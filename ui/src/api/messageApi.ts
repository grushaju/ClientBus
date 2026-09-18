import { apiFetch } from './apiClient'

import type {
    MessageDto,
    MessagePage,
    MessageType
} from './types/message'

export async function getConversationMessages(
    conversationId: string,
    page = 0,
    size = 50
): Promise<MessagePage> {
    const response = await apiFetch(
        `/api/messages/conversation/${conversationId}?page=${page}&size=${size}`
    )

    return response.json()
}

export async function sendOutboundMessage(
    conversationId: string,
    content: string,
    attachments: File[] = [],
    replyToMessageId: string | null = null
): Promise<MessageDto> {
    const formData = new FormData()

    const request = {
        conversationId,
        type: 'TEXT' as MessageType,
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