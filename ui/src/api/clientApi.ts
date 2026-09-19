import { apiFetch } from './apiClient'
import type {
    ClientDto,
    CreateClientRequest,
} from './types/client'
import {ClientAccountSummary} from "./types/conversation";

export async function getClient(
    clientId: string,
): Promise<ClientDto> {
    const response = await apiFetch(
        `/api/clients/${clientId}`,
    )

    return response.json()
}

export async function getWorkspaceClients(
    workspaceId: string,
): Promise<ClientDto[]> {
    const response = await apiFetch(
        `/api/clients/workspace/${workspaceId}`,
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

export async function assignClientAccount(
    clientId: string,
    accountId: string,
): Promise<ClientAccountSummary> {
    const response = await apiFetch(
        `/api/clients/${clientId}/clientaccounts/${accountId}`,
        {
            method: 'POST',
        },
    )

    return response.json()
}

export async function unassignClientAccount(
    accountId: string,
): Promise<void> {
    await apiFetch(
        `/api/clients/clientaccounts/${accountId}`,
        {
            method: 'DELETE',
        },
    )
}

export async function getClientAccountsByClient(
    clientId: string,
): Promise<ClientAccountSummary[]> {
    const response = await apiFetch(
        `/api/clientaccounts/client/${clientId}`,
    )

    return response.json()
}