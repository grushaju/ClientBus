import { apiFetch } from './apiClient'
import type {
    ClientDto,
    ClientListItemDto,
    CreateClientRequest,
    UpdateClientRequest,
} from './types/client'
import type {
    ClientAccountDto,
} from './types/clientAccount'
import type { ConversationDto } from './types/conversation'

export async function getClients(
    signal?: AbortSignal,
): Promise<ClientListItemDto[]> {
    const response = await apiFetch(
        '/api/clients',
        {
            signal,
        },
    )

    return response.json()
}

export async function searchClients(
    query: string,
    signal?: AbortSignal,
): Promise<ClientListItemDto[]> {
    const params = new URLSearchParams()
    params.set('query', query)

    const response = await apiFetch(
        `/api/clients/search?${params.toString()}`,
        { signal },
    )

    return response.json()
}

export async function getClient(
    clientId: string,
): Promise<ClientDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}`,
    )

    return response.json()
}

export async function createClient(
    request: CreateClientRequest,
): Promise<ClientDto> {
    const response = await apiFetch(
        '/api/clients',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateClient(
    clientId: string,
    request: UpdateClientRequest,
): Promise<ClientDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function deleteClient(
    clientId: string,
): Promise<void> {
    await apiFetch(
        `/api/clients/${clientId}`,
        {
            method: 'DELETE',
        },
    )
}

export async function getClientConversations(
    clientId: string,
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/clients/${clientId}/conversations`,
    )

    return response.json()
}

export async function createClientFromConversation(
    conversationId: string,
    request: CreateClientRequest,
): Promise<ClientDto> {
    const response = await apiFetch(
        `/api/clients/from-conversation/${conversationId}`,
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function getClientAccountsByClient(
    clientId: string,
): Promise<ClientAccountDto[]> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts`,
    )

    return response.json()
}

export async function addClientAccount(
    clientId: string,
    request: {
        channelType: string
        externalId: string
        username?: string | null
        phone?: string | null
        displayName?: string | null
    },
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts`,
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function assignClientAccount(
    clientId: string,
    accountId: string,
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts/${accountId}`,
        {
            method: 'POST',
        },
    )

    return response.json()
}

export async function reassignClientAccount(
    clientId: string,
    accountId: string,
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts/${accountId}/reassign`,
        {
            method: 'POST',
        },
    )

    return response.json()
}

export async function unassignClientAccount(
    clientId: string,
    accountId: string,
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts/${accountId}`,
        {
            method: 'DELETE',
        },
    )

    return response.json()
}

export async function getClientsWithoutAccounts(): Promise<ClientDto[]> {
    const response = await apiFetch(
        '/api/clients/without-accounts',
    )

    return response.json()
}