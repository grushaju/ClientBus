export interface ClientDto {
    id: string
    workspaceId: string
    firstName: string
    lastName: string
    phoneList: string[]
    enabled: boolean
    createdAt: string
    updatedAt: string
}

export interface CreateClientRequest {
    workspaceId: string
    firstName: string
    lastName: string
    phoneList: string[]
}