import type {
    ConversationSearchRequest,
} from './conversation'

export type SavedFilterType =
    | 'SYSTEM'
    | 'PRIVATE'

export interface SaveConversationFilterRequest {
    name: string
    filterType: SavedFilterType
    filterDefinition: ConversationSearchRequest
}

export interface SavedConversationFilterDto {
    id: string
    name: string
    filterType: SavedFilterType
    filterDefinition: ConversationSearchRequest
    createdAt: string
    updatedAt: string
}