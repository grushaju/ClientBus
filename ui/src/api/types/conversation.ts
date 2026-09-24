import type { ClientAccountDto } from './clientAccount'
import type { ChannelType } from './channel'

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

export interface CreateOutboundConversationRequest {
    workspaceId: string
    channelAccountId: string
    channelType: ChannelType
    externalId: string
    username?: string | null
    phone?: string | null
    displayName?: string | null
}

export interface ConversationListItem {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    channel: ChannelSummary | null
}

/**
 * Compatibility alias for Inbox components.
 *
 * ClientAccountDto is the canonical ClientAccount model.
 * Keep this alias temporarily so existing Inbox code
 * does not need to be migrated in the same change.
 */
export type ClientAccountSummary =
    ClientAccountDto

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