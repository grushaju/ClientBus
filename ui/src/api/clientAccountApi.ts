import { apiFetch } from './apiClient'
import { withCancellation } from './events/requestScope'
import type {
    ClientAccountDto,
    UpdateClientAccountRequest,
} from './types/clientAccount'

export async function getWorkspaceClientAccounts(workspaceId: string, channelAccountIds: string[] = []): Promise<ClientAccountDto[]> {
    const params = new URLSearchParams()
    channelAccountIds.forEach(id => params.append('channelAccountIds', id))
    const response = await apiFetch(`/api/clientaccounts/workspace/${workspaceId}${params.size ? `?${params}` : ''}`)
    return response.json()
}

export async function getClientAccount(
    id: string,
    signal?: AbortSignal,
): Promise<ClientAccountDto> {
    const response = await apiFetch(`/api/clientaccounts/${id}`, { signal })
    return response.json()
}

export async function getClientAccountsByIds(
    ids: string[],
    signal?: AbortSignal,
): Promise<ClientAccountDto[]> {
    if (ids.length === 0) {
        return []
    }

    const params = new URLSearchParams()

    for (const id of ids) {
        params.append('ids', id)
    }

    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/clientaccounts/by-ids?${params.toString()}`,
            { signal: requestSignal },
        )

        return response.json()
    }, signal ? [signal] : [])
}

export async function getClientAccountsByClient(
    clientId: string,
): Promise<ClientAccountDto[]> {
    const response = await apiFetch(
        `/api/clientaccounts/client/${clientId}`,
    )

    return response.json()
}

export async function searchUnassignedClientAccounts(
    query: string,
    signal?: AbortSignal,
): Promise<ClientAccountDto[]> {
    const params = new URLSearchParams()
    params.set('query', query)

    const response = await apiFetch(
        `/api/clientaccounts/unassigned/search?${params.toString()}`,
        { signal },
    )

    return response.json()
}

export async function updateClientAccount(
    id: string,
    request: UpdateClientAccountRequest,
): Promise<ClientAccountDto> {
    const response = await apiFetch(`/api/clientaccounts/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
    })

    return response.json()
}


export async function getClientAccountAvatarContent(
    accountId: string,
    signal?: AbortSignal,
): Promise<Blob> {
    const response = await apiFetch(
        `/api/clientaccounts/${accountId}/avatar/content`,
        { signal },
    )

    return response.blob()
}
