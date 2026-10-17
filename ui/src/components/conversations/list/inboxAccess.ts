import type { EmployeeWorkspaceDto } from '../../../auth/types'
import type { InboxAccess } from './inboxSelection'

export function getInboxAccess(
    workspaceId: string,
    employeeId: string | null,
    isEmployee: boolean,
    isSuperAdmin: boolean,
    memberships: EmployeeWorkspaceDto[],
): InboxAccess {
    return {
        employeeId,
        isEmployee,
        isSuperAdmin,
        canViewAll: isSuperAdmin || (isEmployee && memberships.some(
            membership => membership.workspaceId === workspaceId && membership.isSupervisor,
        )),
    }
}

export function canManageConversationGroups(access: InboxAccess, assignedEmployeeId: string | null): boolean {
    return access.isEmployee && !!access.employeeId && assignedEmployeeId === access.employeeId
}
