import type {
    ReactNode,
} from 'react'

import { useState } from 'react'

import {
    useAuth,
} from '../../auth/AuthContext'

import {
    useWorkspace,
} from '../../workspace/WorkspaceContext'

import Sidebar from './Sidebar'

import EmployeeAvatar from '../employees/EmployeeAvatar'

import WorkspaceAvatar from '../workspaces/WorkspaceAvatar'

interface AppLayoutProps {
    children: ReactNode
}

function AppLayout({
                       children,
                   }: AppLayoutProps) {
    const {
        currentEmployee,
        currentOrganization,
        isSuperAdmin,
        isEmployee,
    } = useAuth()

    const {
        workspaces,
        currentWorkspace,
        setCurrentWorkspaceId,
    } = useWorkspace()

    const fullName = [
        currentEmployee?.firstName,
        currentEmployee?.lastName,
    ]
        .filter(Boolean)
        .join(' ')

    const displayName =
        fullName ||
        currentEmployee?.username ||
        currentEmployee?.email ||
        'Пользователь'

    const roleLabel =
        isSuperAdmin
            ? 'Администратор'
            : isEmployee
                ? 'Сотрудник'
                : ''

    const [workspaceMenuOpen, setWorkspaceMenuOpen]
        = useState(false)

    return (
        <div className="app-layout">
            <Sidebar />

            <div className="app-content">
                <header className="app-header">
                    <div className="app-header-left">
                        <strong>
                            ClientBus
                        </strong>

                        <span className="app-organization-name">
                            {
                                currentOrganization?.name
                            }
                        </span>

                        <div className="workspace-switcher">
                            <button
                                type="button"
                                className="workspace-switcher-trigger"
                                onClick={() => setWorkspaceMenuOpen(open => !open)}
                                disabled={workspaces.length === 0}
                                aria-expanded={workspaceMenuOpen}
                            >
                                {currentWorkspace && (
                                    <WorkspaceAvatar
                                        workspaceId={currentWorkspace.id}
                                        avatarUrl={currentWorkspace.avatarUrl}
                                        name={currentWorkspace.name}
                                        size={28}
                                    />
                                )}

                                <span>
                                    {currentWorkspace?.name ?? 'Нет доступных Workspace'}
                                </span>

                                <span className="workspace-switcher-chevron">▾</span>
                            </button>

                            {workspaceMenuOpen && workspaces.length > 0 && (
                                <div className="workspace-switcher-menu">
                                    {workspaces.map(workspace => (
                                        <button
                                            type="button"
                                            key={workspace.id}
                                            className={
                                                workspace.id === currentWorkspace?.id
                                                    ? 'workspace-switcher-option active'
                                                    : 'workspace-switcher-option'
                                            }
                                            onClick={() => {
                                                setCurrentWorkspaceId(workspace.id)
                                                setWorkspaceMenuOpen(false)
                                            }}
                                        >
                                            <WorkspaceAvatar
                                                workspaceId={workspace.id}
                                                avatarUrl={workspace.avatarUrl}
                                                name={workspace.name}
                                                size={32}
                                            />

                                            <span>{workspace.name}</span>
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>

                    <div className="app-user">
                        {currentEmployee && (
                            <EmployeeAvatar
                                employeeId={currentEmployee.id}
                                isCurrentEmployee
                                avatarUrl={currentEmployee.avatarUrl ?? null}
                                firstName={currentEmployee.firstName}
                                lastName={currentEmployee.lastName}
                                username={currentEmployee.username}
                                size={36}
                            />
                        )}

                        <div className="app-user-info">
                            <span className="app-user-name">
                                {displayName}
                            </span>

                            {roleLabel && (
                                <span className="app-user-role">
                                    {roleLabel}
                                </span>
                            )}
                        </div>
                    </div>
                </header>

                <main className="app-main">
                    {children}
                </main>
            </div>
        </div>
    )
}

export default AppLayout