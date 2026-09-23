import type { UserRole } from '../../auth/userRole'

export type ChannelType =
    | 'WHATSAPP'
    | 'TELEGRAM'
    | 'MAX'
    | 'VK'
    | 'AVITO'
    | 'DIKIDI'
    | 'WHATSAPP_BUSINESS'
    | 'TELEGRAM_BOT'
    | 'MAX_BOT'
    | 'VK_BOT'

export type ChannelConnectionStatus =
    | 'CREATED'
    | 'CONNECTING'
    | 'CONNECTED'
    | 'DISCONNECTED'
    | 'ERROR'
    | 'DISABLED'

export type TelegramAuthorizationStatus =
    | 'WAIT_PHONE_NUMBER'
    | 'WAIT_CODE'
    | 'WAIT_PASSWORD'
    | 'WAIT_EMAIL'
    | 'READY'
    | 'ERROR'
    | 'CLOSED'

export interface ChannelAccountDto {
    id: string
    externalId: string
    username: string | null
    phone: string | null
    displayName: string | null
}

export interface ChannelDto {
    id: string
    workspaceId: string
    type: ChannelType
    status: ChannelConnectionStatus
    name: string
    account: ChannelAccountDto | null
}

export interface CreateChannelAccountRequest {
    externalId?: string
    username?: string | null
    phone?: string | null
    displayName?: string | null
}

export interface CreateChannelRequest {
    workspaceId: string
    type: ChannelType
    name: string
    account: CreateChannelAccountRequest
}

export interface UpdateChannelRequest {
    name: string
}

export interface UpdateChannelAccountRequest {
    username?: string | null
    phone?: string | null
    displayName?: string | null
}

export type ChannelAccountField =
    | 'externalId'
    | 'username'
    | 'phone'
    | 'displayName'

export type ChannelAuthorizationType =
    | 'NONE'
    | 'TELEGRAM'

export interface ChannelAccountFieldConfig {
    required: boolean
    editable: boolean
    label: string
}

export interface ChannelTypeConfig {
    accountFields: Record<
        ChannelAccountField,
        ChannelAccountFieldConfig
    >
    authorization: ChannelAuthorizationType
}

export function isChannelAdmin(
    role: UserRole | undefined,
): boolean {
    return role === 'SUPER_ADMIN'
}