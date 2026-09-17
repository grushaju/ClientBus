import { apiFetch } from './apiClient'
import type { EmployeeWorkspaceDto } from '../auth/types'

export async function getCurrentEmployeeWorkspaces(): Promise<
    EmployeeWorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/employees/me/workspaces'
    )

    return response.json()
}