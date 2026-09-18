import { apiFetch } from './apiClient'
import type {
    ConversationDto
} from './types/conversation'

export async function getConversation(
    conversationId: string
): Promise<ConversationDto> {
    const response = await apiFetch(
        `/api/conversations/${conversationId}`
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