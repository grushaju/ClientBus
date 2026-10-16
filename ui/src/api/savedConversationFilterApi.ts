import { apiFetch } from './apiClient'

import type {
    SavedFilterType,
    SaveConversationFilterRequest,
    SavedConversationFilterDto,
} from './types/savedConversationFilter'

const getBaseUrl = (workspaceId: string) =>
    `/api/workspaces/${workspaceId}/conversation-filters`

export async function getSavedConversationFilters(
    workspaceId: string,
    filterType: SavedFilterType,
): Promise<SavedConversationFilterDto[]> {
    const response = await apiFetch(
        `${getBaseUrl(workspaceId)}?filterType=${filterType}`,
    )

    return response.json()
}

export async function createSavedConversationFilter(
    workspaceId: string,
    request: SaveConversationFilterRequest,
): Promise<SavedConversationFilterDto> {
    const response = await apiFetch(
        getBaseUrl(workspaceId),
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateSavedConversationFilter(
    workspaceId: string,
    filterId: string,
    request: SaveConversationFilterRequest,
): Promise<SavedConversationFilterDto> {
    const response = await apiFetch(
        `${getBaseUrl(workspaceId)}/${filterId}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function deleteSavedConversationFilter(
    workspaceId: string,
    filterId: string,
): Promise<void> {
    await apiFetch(
        `${getBaseUrl(workspaceId)}/${filterId}`,
        {
            method: 'DELETE',
        },
    )
}