export interface ConversationDto {
    id: string
    workspaceId: string
    channelAccountId: string
    clientAccountId: string
    assignedEmployeeId: string | null
    lastMessageAt: string | null
    lastMessagePreview: string | null
    unreadCount: number
    createdAt: string
    updatedAt: string
}

export interface CreateConversationRequest {
    workspaceId: string
    channelAccountId: string
    clientAccountId: string
}

export interface ConversationListItem {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    channel: ChannelSummary | null
}

export interface ClientAccountSummary {
    id: string
    clientId: string | null
    channelType: string
    externalId: string
    username: string | null
    phone: string | null
    displayName: string | null
}

export interface ChannelSummary {
    id: string
    workspaceId: string
    type: string
    status: string
    name: string
    account: {
        id: string
        externalId: string
        username: string | null
        phone: string | null
        displayName: string | null
    } | null
}