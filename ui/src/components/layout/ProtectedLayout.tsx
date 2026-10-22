import {
    Navigate,
    Outlet,
    useLocation
} from 'react-router-dom'

import { useAuth } from '../../auth/AuthContext'
import AppLayout from './AppLayout'
import { UiEventsProvider } from '../../api/events/UiEventsProvider'

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
        <UiEventsProvider><AppLayout>
            <Outlet />
        </AppLayout></UiEventsProvider>
    )
}

export default ProtectedLayout
