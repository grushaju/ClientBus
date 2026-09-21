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