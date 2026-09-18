import { apiFetch } from './apiClient'

import type {
    ChannelSummary
} from './types/conversation'

export async function getWorkspaceChannels(
    workspaceId: string
): Promise<ChannelSummary[]> {
    const response = await apiFetch(
        `/api/channels/workspace/${workspaceId}`
    )

    return response.json()
}