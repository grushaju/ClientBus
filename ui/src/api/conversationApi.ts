import { apiFetch } from './apiClient'
import { withCancellation } from './events/requestScope'

import type {
    ConversationDto,
    ConversationGroupDto,
    ConversationSearchRequest,
    ConversationSearchResponse,
    CreateOutboundConversationRequest,
} from './types/conversation'

export async function getConversation(
    conversationId: string,
    signal?: AbortSignal,
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}`, { signal },
    )

    return response.json()
}

export async function getWorkspaceConversations(
    workspaceId: string
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}`
    )

    return response.json()
}

export async function getWorkspaceEmployeeConversations(
    workspaceId: string,
    employeeId: string,
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/employee/${employeeId}`,
    )

    return response.json()
}

export async function getEmployeeConversations(
    employeeId: string
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/conversations/employee/${employeeId}`
    )

    return response.json()
}

export async function getUnassignedConversations(
    workspaceId: string
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/unassigned`
    )

    return response.json()
}

export async function createOutboundConversation(
    request: CreateOutboundConversationRequest,
): Promise<ConversationDto> {
    const response = await apiFetch(
        '/api/conversations/outbound',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function assignConversationToMe(
    conversationId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}/assignment/me`,
        {
            method: 'POST'
        }
    )

    return response.json()
}

export async function unassignConversationFromMe(
    conversationId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}/assignment/me`,
        {
            method: 'DELETE'
        }
    )

    return response.json()
}

export async function assignConversation(
    conversationId: string,
    employeeId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}/assignment/${employeeId}`,
        {
            method: 'PUT'
        }
    )

    return response.json()
}

export async function unassignConversation(
    conversationId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}/assignment`,
        {
            method: 'DELETE'
        }
    )

    return response.json()
}

export async function markConversationAsRead(
    conversationId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}/read`,
        {
            method: 'POST'
        }
    )

    return response.json()
}

export async function searchInbox(
    workspaceId: string,
    request: ConversationSearchRequest,
    signal?: AbortSignal,
): Promise<ConversationSearchResponse> {
    return withCancellation(async requestSignal => {
        const response = await apiFetch(
            `/api/conversations/workspace/${workspaceId}/search`,
            {
                method: 'POST',
                body: JSON.stringify(request),
                signal: requestSignal,
            },
        )

        return response.json()
    }, signal ? [signal] : [])
}

export async function createGroup(
    workspaceId: string,
    name: string,
    icon = 'users.svg',
): Promise<ConversationGroupDto> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups`,
        {
            method: 'POST',
            body: JSON.stringify({ name, icon }),
        },
    )

    return response.json()
}


export async function renameGroup(
    workspaceId: string,
    groupId: string,
    name: string,
    icon?: string,
): Promise<ConversationGroupDto> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/${groupId}`,
        {
            method: 'PUT',
            body: JSON.stringify({ name, icon }),
        },
    )

    return response.json()
}

export async function deleteGroup(
    workspaceId: string,
    groupId: string,
): Promise<void> {
    await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/${groupId}`,
        {
            method: 'DELETE',
        },
    )
}


export async function getGroups(
    workspaceId: string,
    signal?: AbortSignal,
): Promise<ConversationGroupDto[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups`,
        { signal },
    )

    return response.json()
}

export async function getGroupConversations(
    workspaceId: string,
    groupId: string,
): Promise<ConversationDto[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/${groupId}/conversations`,
    )

    return response.json()
}

export async function addConversationToGroup(
    workspaceId: string,
    groupId: string,
    conversationId: string,
): Promise<void> {
    await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/${groupId}/members`,
        {
            method: 'POST',
            body: JSON.stringify({ conversationId }),
        },
    )
}

export async function removeConversationFromGroup(
    workspaceId: string,
    groupId: string,
    conversationId: string,
): Promise<void> {
    await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/${groupId}/members/${conversationId}`,
        {
            method: 'DELETE',
        },
    )
}

export async function getConversationGroupIds(
    workspaceId: string,
    conversationId: string,
): Promise<string[]> {
    const response = await apiFetch(
        `/api/conversations/workspace/${workspaceId}/groups/membership?conversationId=${encodeURIComponent(conversationId)}`,
    )

    return response.json()
}

