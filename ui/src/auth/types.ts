import type { UserRole } from './userRole'

export interface EmployeeDto {
    id: string
    organizationId: string
    username: string
    email: string
    firstName: string
    lastName: string
    phone: string
    role: UserRole
    enabled: boolean
    createdAt: string
    updatedAt: string
}

export interface EmployeeWorkspaceDto {
    employeeId: string
    workspaceId: string
}

export interface WorkspaceDto {
    id: string
    organizationId: string
    name: string
    createdAt: string
    updatedAt: string
}