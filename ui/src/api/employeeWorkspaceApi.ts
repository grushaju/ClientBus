import { apiFetch } from './apiClient'

import type {
    EmployeeWorkspaceDto,
} from '../auth/types'

export async function getCurrentEmployeeWorkspaces(): Promise<
    EmployeeWorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/employees/me/workspaces',
    )

    return response.json()
}

export async function getEmployeeWorkspaces(
    employeeId: string,
): Promise<EmployeeWorkspaceDto[]> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces`,
    )

    return response.json()
}

export async function assignEmployeeWorkspace(
    employeeId: string,
    workspaceId: string,
): Promise<EmployeeWorkspaceDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces`,
        {
            method: 'POST',
            body: JSON.stringify({
                workspaceId,
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