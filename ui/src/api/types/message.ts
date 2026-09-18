export type MessageType =
    | 'TEXT'
    | 'IMAGE'
    | 'VIDEO'
    | 'AUDIO'
    | 'DOCUMENT'
    | 'STICKER'
    | 'LOCATION'
    | 'CONTACT'
    | 'SYSTEM'

export type MessageDirection =
    | 'INBOUND'
    | 'OUTBOUND'

export type MessageSenderType =
    | 'CLIENT'
    | 'EMPLOYEE'
    | 'SYSTEM'

export type MessageProcessingStatus =
    | 'RECEIVED'
    | 'PROCESSING'
    | 'PROCESSED'
    | 'QUEUED'
    | 'FAILED'

export type MessageDeliveryStatus =
    | 'PENDING'
    | 'SENT'
    | 'DELIVERED'
    | 'READ'
    | 'FAILED'

export interface MessageDto {
    id: string
    conversationId: string
    type: MessageType
    direction: MessageDirection
    senderType: MessageSenderType
    clientAccountId: string | null
    employeeId: string | null
    replyToMessageId: string | null
    forwardedFromMessageId: string | null
    externalId: string | null
    content: string | null
    metadata: string | null
    sentAt: string | null
    createdAt: string
    processingStatus: MessageProcessingStatus
    deliveryStatus: MessageDeliveryStatus
    processedAt: string | null
    deliveredAt: string | null
    readAt: string | null
}

export interface OutboundMessageRequest {
    conversationId: string
    type: MessageType
    content: string | null
    metadata: string | null
    replyToMessageId: string | null
}

export interface MessagePage {
    content: MessageDto[]
    totalElements: number
    totalPages: number
    size: number
    number: number
    first: boolean
    last: boolean
    numberOfElements: number
    empty: boolean
}