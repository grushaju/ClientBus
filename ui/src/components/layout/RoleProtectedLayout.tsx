import {
    Navigate,
    Outlet,
    useLocation
} from 'react-router-dom'

import { useAuth } from '../../auth/AuthContext'
import type { UserRole } from '../../auth/userRole'

interface RoleProtectedLayoutProps {
    allowedRoles: UserRole[]
}

function RoleProtectedLayout({
                                 allowedRoles
                             }: RoleProtectedLayoutProps) {
    const {
        currentEmployee,
        isLoading
    } = useAuth()

    const location = useLocation()

    if (isLoading) {
        return (
            <div className="app-loading">
                Загрузка...
            </div>
        )
    }

    const role = currentEmployee?.role

    if (!role || !allowedRoles.includes(role)) {
        return (
            <Navigate
                to="/inbox"
                replace
                state={{
                    from: location
                }}
            />
        )
    }

    return <Outlet />
}

export default RoleProtectedLayout