import { apiFetch } from './apiClient'
import type { ClientAccountSummary } from './types/conversation'

export async function getClientAccount(
    id: string,
): Promise<ClientAccountSummary> {
    const response = await apiFetch(
        `/api/clientaccounts/${id}`,
    )

    return response.json()
}

export async function getClientAccountsByIds(
    ids: string[],
): Promise<ClientAccountSummary[]> {
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
): Promise<ClientAccountSummary[]> {
    const response = await apiFetch(
        `/api/clientaccounts/client/${clientId}`,
    )

    return response.json()
}