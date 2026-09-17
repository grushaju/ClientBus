import {
    Navigate,
    Outlet,
    useLocation
} from 'react-router-dom'

import { useAuth } from '../../auth/AuthContext'
import AppLayout from './AppLayout'

function ProtectedLayout() {
    const {
        isAuthenticated,
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

    if (!isAuthenticated) {
        return (
            <Navigate
                to="/login"
                replace
                state={{
                    from: location
                }}
            />
        )
    }

    return (
        <AppLayout>
            <Outlet />
        </AppLayout>
    )
}

export default ProtectedLayout