import { apiFetch } from './apiClient'

import type {
    WorkspaceDto,
} from '../auth/types'

export interface WorkspaceStatisticsDto {
    workspaceId: string; employees: number; channels: number; conversations: number; unread: number; lastActivity: string | null
}
export async function getWorkspaceStatistics(signal?: AbortSignal): Promise<WorkspaceStatisticsDto[]> {
    return (await apiFetch('/api/workspaces/statistics', { signal })).json()
}

export async function getCurrentUserWorkspaces(signal?: AbortSignal): Promise<
    WorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/workspaces/my',
        { signal },
    )

    return response.json()
}

export async function getAllWorkspaces(signal?: AbortSignal): Promise<
    WorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/workspaces/all',
        { signal },
    )

    return response.json()
}

export async function getWorkspace(
    workspaceId: string,
    signal?: AbortSignal,
): Promise<WorkspaceDto> {
    const response = await apiFetch(
        `/api/workspaces/${workspaceId}`,
        { signal },
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


export async function getWorkspaceAvatarContent(
    workspaceId: string,
): Promise<Blob> {
    const response = await apiFetch(
        `/api/workspaces/${workspaceId}/avatar/content`,
    )

    return response.blob()
}

export async function uploadWorkspaceAvatar(
    workspaceId: string,
    file: File,
): Promise<WorkspaceDto> {
    const formData = new FormData()
    formData.append('file', file)

    const response = await apiFetch(
        `/api/workspaces/${workspaceId}/avatar`,
        {
            method: 'PUT',
            body: formData,
        },
    )

    return response.json()
}

export async function clearWorkspaceAvatar(
    workspaceId: string,
): Promise<WorkspaceDto> {
    const response = await apiFetch(
        `/api/workspaces/${workspaceId}/avatar`,
        {
            method: 'DELETE',
        },
    )

    return response.json()
}
