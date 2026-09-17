import {
    createContext,
    useContext,
    useEffect,
    useMemo,
    useState,
    type ReactNode
} from 'react'

import { useAuth } from '../auth/AuthContext'
import {
    getCurrentUserWorkspaces
} from '../api/workspaceApi'

import type { WorkspaceDto } from '../auth/types'

interface WorkspaceContextValue {
    workspaces: WorkspaceDto[]
    currentWorkspace: WorkspaceDto | null
    isLoading: boolean
    setCurrentWorkspaceId: (
        workspaceId: string
    ) => void
}

const WorkspaceContext =
    createContext<
        WorkspaceContextValue | undefined
    >(undefined)

interface WorkspaceProviderProps {
    children: ReactNode
}

const CURRENT_WORKSPACE_KEY =
    'clientbus.currentWorkspaceId'

export function WorkspaceProvider({
                                      children
                                  }: WorkspaceProviderProps) {
    const {
        isAuthenticated,
        currentEmployee
    } = useAuth()

    const [workspaces, setWorkspaces] =
        useState<WorkspaceDto[]>([])

    const [
        currentWorkspaceId,
        setCurrentWorkspaceId
    ] = useState<string | null>(
        () =>
            localStorage.getItem(
                CURRENT_WORKSPACE_KEY
            )
    )

    const [isLoading, setIsLoading] =
        useState(false)

    useEffect(() => {
        if (
            !isAuthenticated ||
            !currentEmployee
        ) {
            setWorkspaces([])
            setCurrentWorkspaceId(null)
            localStorage.removeItem(
                CURRENT_WORKSPACE_KEY
            )
            return
        }

        let cancelled = false

        setIsLoading(true)

        getCurrentUserWorkspaces()
            .then((items) => {
                if (cancelled) {
                    return
                }

                setWorkspaces(items)

                const storedId =
                    localStorage.getItem(
                        CURRENT_WORKSPACE_KEY
                    )

                const storedWorkspace =
                    items.find(
                        workspace =>
                            workspace.id ===
                            storedId
                    )

                const selectedWorkspace =
                    storedWorkspace ??
                    items[0] ??
                    null

                setCurrentWorkspaceId(
                    selectedWorkspace?.id ??
                    null
                )

                if (selectedWorkspace) {
                    localStorage.setItem(
                        CURRENT_WORKSPACE_KEY,
                        selectedWorkspace.id
                    )
                } else {
                    localStorage.removeItem(
                        CURRENT_WORKSPACE_KEY
                    )
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setWorkspaces([])
                    setCurrentWorkspaceId(null)
                }
            })
            .finally(() => {
                if (!cancelled) {
                    setIsLoading(false)
                }
            })

        return () => {
            cancelled = true
        }
    }, [
        isAuthenticated,
        currentEmployee?.id
    ])

    function selectWorkspace(
        workspaceId: string
    ): void {
        const workspace =
            workspaces.find(
                item =>
                    item.id === workspaceId
            )

        if (!workspace) {
            return
        }

        setCurrentWorkspaceId(
            workspace.id
        )

        localStorage.setItem(
            CURRENT_WORKSPACE_KEY,
            workspace.id
        )
    }

    const currentWorkspace =
        workspaces.find(
            workspace =>
                workspace.id ===
                currentWorkspaceId
        ) ?? null

    const value = useMemo(
        () => ({
            workspaces,
            currentWorkspace,
            isLoading,
            setCurrentWorkspaceId:
            selectWorkspace
        }),
        [
            workspaces,
            currentWorkspace,
            isLoading
        ]
    )

    return (
        <WorkspaceContext.Provider
            value={value}
        >
            {children}
        </WorkspaceContext.Provider>
    )
}

export function useWorkspace():
    WorkspaceContextValue {
    const context =
        useContext(WorkspaceContext)

    if (!context) {
        throw new Error(
            'useWorkspace must be used inside WorkspaceProvider'
        )
    }

    return context
}