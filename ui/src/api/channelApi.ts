import { apiFetch } from './apiClient'

import type {
    ChannelDto,
    CreateChannelRequest,
    UpdateChannelAccountRequest,
    UpdateChannelRequest,
} from './types/channel'

export async function getWorkspaceChannels(
    workspaceId: string,
): Promise<ChannelDto[]> {
    const response = await apiFetch(
        `/api/channels/workspace/${workspaceId}`,
    )

    return response.json()
}

export async function getChannel(
    id: string,
): Promise<ChannelDto> {
    const response = await apiFetch(
        `/api/channels/${id}`,
    )

    return response.json()
}

export async function createChannel(
    request: CreateChannelRequest,
): Promise<ChannelDto> {
    const response = await apiFetch(
        '/api/channels',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateChannel(
    id: string,
    request: UpdateChannelRequest,
): Promise<ChannelDto> {
    const response = await apiFetch(
        `/api/channels/${id}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function deleteChannel(
    id: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${id}`,
        {
            method: 'DELETE',
        },
    )
}

export async function updateChannelAccount(
    channelId: string,
    request: UpdateChannelAccountRequest,
): Promise<ChannelDto['account']> {
    const response = await apiFetch(
        `/api/channels/${channelId}/account`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}