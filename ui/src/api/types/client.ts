export interface ClientListItemDto {
    id: string
    firstName: string
    lastName: string
    phoneList: string[]
    accountCount: number
    lastContactAt: string | null
    enabled: boolean
}

export interface ClientDto {
    id: string
    organizationId: string
    firstName: string
    lastName: string
    phoneList: string[]
    enabled: boolean
    createdAt: string
    updatedAt: string
}

export interface CreateClientRequest {
    firstName: string
    lastName: string
    phoneList: string[]
}

export interface UpdateClientRequest {
    firstName?: string
    lastName?: string
    phoneList?: string[]
    enabled?: boolean
}