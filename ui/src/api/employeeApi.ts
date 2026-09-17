import { apiFetch } from './apiClient'
import type { EmployeeDto } from '../auth/types'

export async function getCurrentEmployee(): Promise<EmployeeDto> {
    const response = await apiFetch('/api/employees/me')

    return response.json()
}