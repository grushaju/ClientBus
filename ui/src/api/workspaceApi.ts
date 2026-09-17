import { apiFetch } from './apiClient'
import type { WorkspaceDto } from '../auth/types'

export async function getCurrentUserWorkspaces(): Promise<
    WorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/workspaces/my'
    )

    return response.json()
}