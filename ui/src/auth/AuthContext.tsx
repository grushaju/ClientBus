import {
    createContext,
    useContext,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from 'react'

import {
    clearAccessToken,
    getAccessToken,
    setAccessToken,
} from './authStorage'

import {
    login as loginApi,
} from './authApi'

import {
    getCurrentEmployee,
} from '../api/employeeApi'

import {
    getCurrentEmployeeWorkspaces,
} from '../api/employeeWorkspaceApi'

import {
    getOrganization,
} from '../api/organizationApi'

import type {
    OrganizationDto,
} from '../api/types/organization'

import type {
    EmployeeDto,
    EmployeeWorkspaceDto,
} from './types'

interface AuthContextValue {
    accessToken: string | null
    currentEmployee: EmployeeDto | null
    currentOrganization: OrganizationDto | null
    employeeWorkspaces: EmployeeWorkspaceDto[]
    isAuthenticated: boolean
    isLoading: boolean
    isSuperAdmin: boolean
    isEmployee: boolean

    login: (
        username: string | undefined,
        email: string | undefined,
        password: string,
    ) => Promise<void>

    logout: () => void

    refreshCurrentUser: () => Promise<void>
}

const AuthContext =
    createContext<
        AuthContextValue | undefined
    >(undefined)

interface AuthProviderProps {
    children: ReactNode
}

export function AuthProvider({
                                 children,
                             }: AuthProviderProps) {
    const [
        accessToken,
        setAccessTokenState,
    ] = useState<string | null>(
        getAccessToken,
    )

    const [
        currentEmployee,
        setCurrentEmployee,
    ] = useState<EmployeeDto | null>(
        null,
    )

    const [
        currentOrganization,
        setCurrentOrganization,
    ] = useState<OrganizationDto | null>(
        null,
    )

    const [
        employeeWorkspaces,
        setWorkspaces,
    ] = useState<
        EmployeeWorkspaceDto[]
    >([])

    const [
        isLoading,
        setIsLoading,
    ] = useState(
        () => getAccessToken() !== null,
    )

    async function loadCurrentUser() {
        const employee =
            await getCurrentEmployee()

        const [
            employeeWorkspaces,
            organization,
        ] = await Promise.all([
            getCurrentEmployeeWorkspaces(),
            getOrganization(
                employee.organizationId,
            ),
        ])

        setCurrentEmployee(
            employee,
        )

        setWorkspaces(
            employeeWorkspaces,
        )

        setCurrentOrganization(
            organization,
        )
    }

    async function refreshCurrentUser() {
        await loadCurrentUser()
    }

    useEffect(() => {
        const token =
            getAccessToken()

        if (!token) {
            setIsLoading(false)
            return
        }

        loadCurrentUser()
            .catch(() => {
                clearAccessToken()

                setAccessTokenState(
                    null,
                )

                setCurrentEmployee(
                    null,
                )

                setCurrentOrganization(
                    null,
                )

                setWorkspaces([])
            })
            .finally(() => {
                setIsLoading(false)
            })
    }, [])

    async function login(
        username: string | undefined,
        email: string | undefined,
        password: string,
    ): Promise<void> {
        const response =
            await loginApi({
                username,
                email,
                password,
            })

        setAccessToken(
            response.accessToken,
        )

        setAccessTokenState(
            response.accessToken,
        )

        setIsLoading(true)

        try {
            await loadCurrentUser()
        } catch (error) {
            clearAccessToken()

            setAccessTokenState(
                null,
            )

            setCurrentEmployee(
                null,
            )

            setCurrentOrganization(
                null,
            )

            setWorkspaces([])

            throw error
        } finally {
            setIsLoading(false)
        }
    }

    function logout() {
        clearAccessToken()

        setAccessTokenState(
            null,
        )

        setCurrentEmployee(
            null,
        )

        setCurrentOrganization(
            null,
        )

        setWorkspaces([])
    }

    const isSuperAdmin =
        currentEmployee?.role ===
        'SUPER_ADMIN'

    const isEmployee =
        currentEmployee?.role ===
        'EMPLOYEE'

    const value = useMemo(
        () => ({
            accessToken,
            currentEmployee,
            currentOrganization,
            employeeWorkspaces,
            isAuthenticated:
                accessToken !== null,
            isLoading,
            isSuperAdmin,
            isEmployee,
            login,
            logout,
            refreshCurrentUser,
        }),
        [
            accessToken,
            currentEmployee,
            currentOrganization,
            employeeWorkspaces,
            isLoading,
            isSuperAdmin,
            isEmployee,
        ],
    )

    return (
        <AuthContext.Provider
            value={value}
        >
            {children}
        </AuthContext.Provider>
    )
}

export function useAuth():
    AuthContextValue {
    const context =
        useContext(AuthContext)

    if (!context) {
        throw new Error(
            'useAuth must be used inside AuthProvider',
        )
    }

    return context
}