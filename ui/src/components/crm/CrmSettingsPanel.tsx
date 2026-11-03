import { useAuth } from '../../auth/AuthContext'
import CrmConnections from './CrmConnections'

export default function CrmSettingsPanel() {
    const { isSuperAdmin } = useAuth()
    return isSuperAdmin ? <CrmConnections /> : null
}
