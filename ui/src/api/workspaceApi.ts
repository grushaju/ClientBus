import { apiFetch } from './apiClient'

import type {
    WorkspaceDto,
} from '../auth/types'

export async function getCurrentUserWorkspaces(): Promise<
    WorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/workspaces/my',
    )

    return response.json()
}

export async function getAllWorkspaces(): Promise<
    WorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/workspaces/all',
    )

    return response.json()
}

export async function getWorkspace(
    workspaceId: string,
): Promise<WorkspaceDto> {
    const response = await apiFetch(
        `/api/workspaces/${workspaceId}`,
    )

    return response.json()
}

export async function createWorkspace(
    request: {
        organizationId: string
        name: string
    },
): Promise<WorkspaceDto> {
    const response = await apiFetch(
        '/api/workspaces',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateWorkspace(
    workspaceId: string,
    request: {
        name: string
    },
): Promise<WorkspaceDto> {
    const response = await apiFetch(
        `/api/workspaces/${workspaceId}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function deleteWorkspace(
    workspaceId: string,
): Promise<void> {
    await apiFetch(
        `/api/workspaces/${workspaceId}`,
        {
            method: 'DELETE',
        },
    )
}