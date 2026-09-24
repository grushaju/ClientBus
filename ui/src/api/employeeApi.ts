import { apiFetch } from './apiClient'

import type {
    EmployeeDto,
    EmployeeWorkspaceDto,
} from '../auth/types'

export interface CreateEmployeeRequest {
    organizationId: string
    username: string
    email: string
    password: string
    firstName: string
    lastName: string
    phone?: string | null
}

export interface UpdateEmployeeRequest {
    firstName?: string | null
    lastName?: string | null
    phone?: string | null
}

export interface UpdateEmployeeCredentialsRequest {
    username?: string | null
    email?: string | null
}

export interface SetEmployeeEnabledRequest {
    enabled: boolean
}

export interface ResetEmployeePasswordRequest {
    newPassword: string
}

/*
 * =========================
 * SELF SERVICE
 * =========================
 */

export async function getCurrentEmployee(): Promise<EmployeeDto> {
    const response = await apiFetch(
        '/api/employees/me',
    )

    return response.json()
}

export async function updateCurrentEmployee(
    request: UpdateEmployeeRequest,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        '/api/employees/me',
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateCurrentCredentials(
    request: UpdateEmployeeCredentialsRequest,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        '/api/employees/me/credentials',
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function changeCurrentPassword(
    currentPassword: string,
    newPassword: string,
): Promise<void> {
    const response = await apiFetch(
        '/api/employees/me/password',
        {
            method: 'PUT',
            body: JSON.stringify({
                currentPassword,
                newPassword,
            }),
        },
    )

    if (response.status !== 204) {
        throw new Error(
            'Не удалось изменить пароль',
        )
    }
}

export async function getCurrentEmployeeWorkspaces(): Promise<
    EmployeeWorkspaceDto[]
> {
    const response = await apiFetch(
        '/api/employees/me/workspaces',
    )

    return response.json()
}

/*
 * =========================
 * ADMIN
 * =========================
 */

export async function createEmployee(
    request: CreateEmployeeRequest,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        '/api/employees',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function getEmployee(
    employeeId: string,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}`,
    )

    return response.json()
}

export async function getEmployeesByOrganization(
    organizationId: string,
): Promise<EmployeeDto[]> {
    const response = await apiFetch(
        `/api/employees/organization/${organizationId}`,
    )

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

export async function searchEmployees(
    workspaceId: string,
    query?: string,
): Promise<EmployeeDto[]> {
    const params = new URLSearchParams()

    if (query?.trim()) {
        params.set(
            'query',
            query.trim(),
        )
    }

    const queryString =
        params.toString()

    const response = await apiFetch(
        `/api/employees/workspace/${workspaceId}/search${
            queryString
                ? `?${queryString}`
                : ''
        }`,
    )

    return response.json()
}

export async function updateEmployee(
    employeeId: string,
    request: UpdateEmployeeRequest,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function updateEmployeeCredentials(
    employeeId: string,
    request: UpdateEmployeeCredentialsRequest,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/credentials`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )

    return response.json()
}

export async function setEmployeeEnabled(
    employeeId: string,
    enabled: boolean,
): Promise<EmployeeDto> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/enabled`,
        {
            method: 'PATCH',
            body: JSON.stringify({
                enabled,
            } satisfies SetEmployeeEnabledRequest),
        },
    )

    return response.json()
}

export async function resetEmployeePassword(
    employeeId: string,
    newPassword: string,
): Promise<void> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/password/reset`,
        {
            method: 'POST',
            body: JSON.stringify({
                newPassword,
            } satisfies ResetEmployeePasswordRequest),
        },
    )

    /*
     * Backend currently returns 204 No Content,
     * despite the controller's generic return type.
     */
    if (response.status !== 204) {
        throw new Error(
            'Не удалось сбросить пароль',
        )
    }
}

export async function deleteEmployee(
    employeeId: string,
): Promise<void> {
    const response = await apiFetch(
        `/api/employees/${employeeId}`,
        {
            method: 'DELETE',
        },
    )

    if (response.status !== 204) {
        throw new Error(
            'Не удалось удалить сотрудника',
        )
    }
}

export async function getEmployeeWorkspaces(
    employeeId: string,
): Promise<EmployeeWorkspaceDto[]> {
    const response = await apiFetch(
        `/api/employees/${employeeId}/workspaces`,
    )

    return response.json()
}