import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { clearAccessToken, getAccessToken, setAccessToken } from './authStorage'
import { login as loginApi } from './authApi'
import { getCurrentEmployee } from '../api/employeeApi'
import { getCurrentEmployeeWorkspaces } from '../api/employeeWorkspaceApi'
import { getOrganization } from '../api/organizationApi'
import { getApiErrorStatus } from '../api/apiClient'
import { abortError, withCancellation } from '../api/events/requestScope'
import type { OrganizationDto } from '../api/types/organization'
import type { EmployeeDto, EmployeeWorkspaceDto } from './types'
import { clearNotificationAlertDedup, notificationAccount } from '../components/notifications/notificationStorage'

interface AuthContextValue {
    accessToken: string | null
    currentEmployee: EmployeeDto | null
    currentOrganization: OrganizationDto | null
    employeeWorkspaces: EmployeeWorkspaceDto[]
    sessionEpoch: number
    isAuthenticated: boolean
    isLoading: boolean
    isSuperAdmin: boolean
    isEmployee: boolean
    login: (username: string | undefined, email: string | undefined, password: string) => Promise<void>
    logout: () => void
    refreshCurrentUser: (signal?: AbortSignal) => Promise<void>
    invalidateIdentity: () => void
    synchronizeSession: () => void
}
const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [accessToken, setToken] = useState(getAccessToken)
    const [currentEmployee, setEmployee] = useState<EmployeeDto | null>(null)
    const [currentOrganization, setOrganization] = useState<OrganizationDto | null>(null)
    const [employeeWorkspaces, setWorkspaces] = useState<EmployeeWorkspaceDto[]>([])
    const [sessionEpoch, setEpoch] = useState(0)
    const [isLoading, setLoading] = useState(() => getAccessToken() !== null)
    const session = useRef({ epoch: 0, token: accessToken, controller: new AbortController() })
    const identityVersion = useRef(0)
    const flight = useRef<{ owner: typeof session.current; version: number; controller: AbortController; promise: Promise<void> }>()
    const loginVersion = useRef(0)
    const notificationOwner = useRef<string | null>(null)
    if (currentEmployee) notificationOwner.current = notificationAccount(currentEmployee.organizationId, currentEmployee.id)

    const replaceSession = useCallback((token: string | null) => {
        if (notificationOwner.current) clearNotificationAlertDedup(notificationOwner.current)
        notificationOwner.current = null
        loginVersion.current++
        session.current.controller.abort()
        session.current = { epoch: session.current.epoch + 1, token, controller: new AbortController() }
        flight.current = undefined
        setEpoch(session.current.epoch)
        setToken(token); setEmployee(null); setOrganization(null); setWorkspaces([])
        setLoading(token !== null)
    }, [])
    const logout = useCallback(() => {
        if (getAccessToken() === session.current.token) clearAccessToken()
        replaceSession(getAccessToken())
    }, [replaceSession])
    const synchronizeSession = useCallback(() => {
        const token = getAccessToken()
        if (token !== session.current.token) replaceSession(token)
    }, [replaceSession])

    const invalidateIdentity = useCallback(() => {
        identityVersion.current++
        flight.current?.controller.abort()
        flight.current = undefined
    }, [])

    const refreshCurrentUser = useCallback((signal?: AbortSignal): Promise<void> => {
        const owner = session.current
        const version = identityVersion.current
        const controller = new AbortController()
        const current = () => owner === session.current && version === identityVersion.current
            && !owner.controller.signal.aborted && !controller.signal.aborted && owner.token === getAccessToken()
        if (!owner.token || !current()) return Promise.reject(abortError())
        const existing = flight.current
        if (existing?.owner === owner && existing.version === version) return withCancellation(() => existing.promise, signal ? [signal] : [])
        const promise = withCancellation(async requestSignal => {
            const employee = await getCurrentEmployee(requestSignal)
            if (!current() || requestSignal.aborted) throw abortError()
            const [memberships, organization] = await Promise.all([
                getCurrentEmployeeWorkspaces(requestSignal), getOrganization(employee.organizationId, requestSignal),
            ])
            if (!current() || requestSignal.aborted) throw abortError()
            if (employee.enabled === false) { logout(); throw abortError() }
            setEmployee(employee); setWorkspaces(memberships); setOrganization(organization)
        }, [owner.controller.signal, controller.signal, ...(signal ? [signal] : [])]).catch(error => {
            if (current() && [401, 403].includes(getApiErrorStatus(error) ?? 0)) logout()
            throw error
        }).finally(() => { if (flight.current?.promise === promise) flight.current = undefined })
        flight.current = { owner, version, controller, promise }
        return promise
    }, [logout])

    useEffect(() => {
        if (session.current.controller.signal.aborted) session.current = { ...session.current, controller: new AbortController() }
        const owner = session.current
        if (!owner.token) { setLoading(false); return }
        void refreshCurrentUser().catch(error => {
            if (owner === session.current && !owner.controller.signal.aborted && error?.name !== 'AbortError') logout()
        }).finally(() => { if (owner === session.current) setLoading(false) })
        return () => { owner.controller.abort(); if (flight.current?.owner === owner) flight.current = undefined }
    }, [sessionEpoch, refreshCurrentUser, logout])

    useEffect(() => {
        const changed = (event: StorageEvent) => {
            if (event.key === null || event.key === 'clientbus.accessToken') synchronizeSession()
        }
        window.addEventListener('storage', changed)
        const expired = () => {
            if (getAccessToken() === session.current.token) logout()
            else synchronizeSession()
        }
        window.addEventListener('clientbus:session-expired', expired)
        return () => {
            window.removeEventListener('storage', changed)
            window.removeEventListener('clientbus:session-expired', expired)
        }
    }, [synchronizeSession, logout])

    const login = useCallback(async (username: string | undefined, email: string | undefined, password: string) => {
        const version = ++loginVersion.current
        const response = await loginApi({ username, email, password })
        if (version !== loginVersion.current) throw abortError()
        setAccessToken(response.accessToken)
        replaceSession(response.accessToken)
        const owner = session.current
        try { await refreshCurrentUser() }
        catch (error) { if (owner === session.current) logout(); throw error }
        finally { if (owner === session.current) setLoading(false) }
    }, [replaceSession, refreshCurrentUser, logout])

    const value = useMemo(() => ({ accessToken, currentEmployee, currentOrganization, employeeWorkspaces,
        sessionEpoch, isAuthenticated: accessToken !== null, isLoading,
        isSuperAdmin: currentEmployee?.role === 'SUPER_ADMIN', isEmployee: currentEmployee?.role === 'EMPLOYEE',
        login, logout, refreshCurrentUser, invalidateIdentity, synchronizeSession,
    }), [accessToken, currentEmployee, currentOrganization, employeeWorkspaces, sessionEpoch, isLoading,
        login, logout, refreshCurrentUser, invalidateIdentity, synchronizeSession])
    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
export function useAuth(): AuthContextValue {
    const context = useContext(AuthContext)
    if (!context) throw new Error('useAuth must be used inside AuthProvider')
    return context
}
