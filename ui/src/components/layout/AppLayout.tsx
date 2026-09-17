import type { ReactNode } from 'react'

import { useAuth } from '../../auth/AuthContext'
import {
    useWorkspace
} from '../../workspace/WorkspaceContext'

import Sidebar from './Sidebar'

interface AppLayoutProps {
    children: ReactNode
}

function AppLayout({ children }: AppLayoutProps) {
    const {
        currentEmployee,
        isSuperAdmin,
        isEmployee
    } = useAuth()

    const {
        workspaces,
        currentWorkspace,
        setCurrentWorkspaceId
    } = useWorkspace()

    const fullName = [
        currentEmployee?.firstName,
        currentEmployee?.lastName
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

    return (
        <div className="app-layout">
            <Sidebar />

            <div className="app-content">
                <header className="app-header">
                    <div className="app-header-left">
                        <strong>
                            ClientBus
                        </strong>

                        <select
                            value={
                                currentWorkspace?.id ??
                                ''
                            }
                            onChange={(event) =>
                                setCurrentWorkspaceId(
                                    event.target.value
                                )
                            }
                            disabled={
                                workspaces.length === 0
                            }
                        >
                            {workspaces.length === 0 ? (
                                <option value="">
                                    Нет доступных Workspace
                                </option>
                            ) : (
                                workspaces.map(
                                    workspace => (
                                        <option
                                            key={
                                                workspace.id
                                            }
                                            value={
                                                workspace.id
                                            }
                                        >
                                            {
                                                workspace.name
                                            }
                                        </option>
                                    )
                                )
                            )}
                        </select>
                    </div>

                    <div className="app-user">
                        <span className="app-user-name">
                            {displayName}
                        </span>

                        {roleLabel && (
                            <span className="app-user-role">
                                {roleLabel}
                            </span>
                        )}
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