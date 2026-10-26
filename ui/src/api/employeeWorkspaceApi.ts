import { apiFetch } from './apiClient'

import type {
    EmployeeWorkspaceDto,
} from '../auth/types'

export async function getOrganizationMemberships(organizationId: string, signal?: AbortSignal): Promise<EmployeeWorkspaceDto[]> {
    return (await apiFetch(`/api/employees/organization/${organizationId}/memberships`, { signal })).json()
}

export async function getWorkspaceMemberships(workspaceId: string, signal?: AbortSignal): Promise<EmployeeWorkspaceDto[]> {
    return (await apiFetch(`/api/workspaces/${workspaceId}/memberships`, { signal })).json()
}

export async function getCurrentEmployeeWorkspaces(signal?: AbortSignal): Promise<
    EmployeeWorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/employees/me/workspaces',
        { signal },
    )

    return response.json()
}

export async function getEmployeeWorkspaces(
    employeeId: string,
    signal?: AbortSignal,
): Promise<EmployeeWorkspaceDto[]> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces`,
        { signal },
    )

    return response.json()
}


export async function assignEmployeeWorkspace(
    employeeId: string,
    workspaceId: string,
    isSupervisor = false,
): Promise<EmployeeWorkspaceDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces`,
        {
            method: 'POST',
            body: JSON.stringify({
                workspaceId,
                isSupervisor,
            }),
        },
    )

    return response.json()
}


export async function removeEmployeeWorkspace(
    employeeId: string,
    workspaceId: string,
): Promise<void> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces/${workspaceId}`,
        {
            method: 'DELETE',
        },
    )

    if (response.status !== 204) {
        throw new Error(
            'Не удалось удалить Workspace у сотрудника',
        )
    }
}


export async function setEmployeeWorkspaceSupervisor(
    employeeId: string,
    workspaceId: string,
    isSupervisor: boolean,
): Promise<EmployeeWorkspaceDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces/${workspaceId}/supervisor`,
        {
            method: 'PATCH',
            body: JSON.stringify({
                isSupervisor,
            }),
        },
    )

    return response.json()
}
