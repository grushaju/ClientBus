import { apiFetch } from './apiClient'
import type {
    ClientAccountDto,
    UpdateClientAccountRequest,
} from './types/clientAccount'

export async function getClientAccount(
    id: string,
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clientaccounts/${id}`,
    )

    return response.json()
}

export async function getClientAccountsByIds(
    ids: string[],
): Promise<ClientAccountDto[]> {
    if (ids.length === 0) {
        return []
    }

    const params = new URLSearchParams()

    for (const id of ids) {
        params.append('ids', id)
    }

    const response = await apiFetch(
        `/api/clientaccounts/by-ids?${params.toString()}`,
    )

    return response.json()
}

export async function getClientAccountsByClient(
    clientId: string,
): Promise<ClientAccountDto[]> {
    const response = await apiFetch(
        `/api/clientaccounts/client/${clientId}`,
    )

    return response.json()
}

export async function updateClientAccount(
    id: string,
    request: UpdateClientAccountRequest,
): Promise<ClientAccountDto> {
    const response = await apiFetch(
        `/api/clientaccounts/${id}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}