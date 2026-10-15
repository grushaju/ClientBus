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
    historyStartReached: boolean
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
        avatarUrl: string | null
    } | null
}


export type ConversationView =
    | 'MINE'
    | 'UNASSIGNED'
    | 'GROUP'
    | 'ALL'

export type ConversationClientLinkFilter =
    | 'ANY'
    | 'WITH_CLIENT'
    | 'WITHOUT_CLIENT'

export type ConversationUnreadFilter =
    | 'ANY'
    | 'HAS_UNREAD'
    | 'READ_ONLY'

export type ConversationSort =
    | 'LAST_MESSAGE_DESC'
    | 'LAST_MESSAGE_ASC'

export interface ConversationSearchRequest {
    view: ConversationView
    groupId?: string | null
    search?: string | null
    channelAccountIds?: string[]
    clientAccountIds?: string[]
    clientLink?: ConversationClientLinkFilter | null
    unread?: ConversationUnreadFilter | null
    activityFrom?: string | null
    activityTo?: string | null
    sort?: ConversationSort | null
    page?: number | null
    size?: number | null
}

export interface ConversationSearchResponse {
    content: ConversationDto[]
    page: number
    size: number
    totalElements: number
    totalPages: number
    hasNext: boolean
}

export interface ConversationGroupDto {
    id: string
    workspaceId: string
    ownerEmployeeId: string
    name: string
    icon: string
    createdAt: string
    updatedAt: string
}
