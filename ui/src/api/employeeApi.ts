import { apiFetch } from './apiClient'
import type { EmployeeDto } from '../auth/types'

export async function getCurrentEmployee(): Promise<EmployeeDto> {
    const response = await apiFetch('/api/employees/me')

    return response.json()
}

export async function getWorkspaceEmployees(
    workspaceId: string,
): Promise<EmployeeDto[]> {
    const response = await apiFetch(
        `/api/employees/workspace/${workspaceId}`,
    )

    return response.json()
}