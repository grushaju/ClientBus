export interface ClientAccountDto {
    id: string
    clientId: string | null
    channelType: string
    externalId: string
    username: string | null
    phone: string | null
    displayName: string | null
}

export interface CreateClientAccountRequest {
    channelType: string
    externalId: string
    username?: string | null
    phone?: string | null
    displayName?: string | null
}

export interface UpdateClientAccountRequest {
    username?: string | null
    phone?: string | null
    displayName?: string | null
}