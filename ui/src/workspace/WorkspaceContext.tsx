import {
    createContext,
    useContext,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from 'react'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    getCurrentUserWorkspaces,
} from '../api/workspaceApi'

import type {
    WorkspaceDto,
} from '../auth/types'

interface WorkspaceContextValue {
    workspaces: WorkspaceDto[]
    currentWorkspace: WorkspaceDto | null
    isLoading: boolean
    setCurrentWorkspaceId: (
        workspaceId: string,
    ) => void
    addWorkspace: (
        workspace: WorkspaceDto,
    ) => void
    updateWorkspace: (
        workspace: WorkspaceDto,
    ) => void
    removeWorkspace: (
        workspaceId: string,
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
                                      children,
                                  }: WorkspaceProviderProps) {
    const {
        isAuthenticated,
        currentEmployee,
    } = useAuth()

    const [
        workspaces,
        setWorkspaces,
    ] = useState<WorkspaceDto[]>([])

    const [
        currentWorkspaceId,
        setCurrentWorkspaceId,
    ] = useState<string | null>(
        () =>
            localStorage.getItem(
                CURRENT_WORKSPACE_KEY,
            ),
    )

    const [
        isLoading,
        setIsLoading,
    ] = useState(false)

    useEffect(() => {
        if (
            !isAuthenticated ||
            !currentEmployee
        ) {
            setWorkspaces([])
            setCurrentWorkspaceId(null)

            localStorage.removeItem(
                CURRENT_WORKSPACE_KEY,
            )

            return
        }

        let cancelled = false

        setIsLoading(true)

        getCurrentUserWorkspaces()
            .then(items => {
                if (cancelled) {
                    return
                }

                setWorkspaces(items)

                const storedId =
                    localStorage.getItem(
                        CURRENT_WORKSPACE_KEY,
                    )

                const selected =
                    items.find(
                        workspace =>
                            workspace.id ===
                            storedId,
                    ) ??
                    items[0] ??
                    null

                setCurrentWorkspaceId(
                    selected?.id ?? null,
                )

                if (selected) {
                    localStorage.setItem(
                        CURRENT_WORKSPACE_KEY,
                        selected.id,
                    )
                } else {
                    localStorage.removeItem(
                        CURRENT_WORKSPACE_KEY,
                    )
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setWorkspaces([])
                    setCurrentWorkspaceId(
                        null,
                    )
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
        currentEmployee?.id,
    ])

    function selectWorkspace(
        workspaceId: string,
    ) {
        const workspace =
            workspaces.find(
                item =>
                    item.id ===
                    workspaceId,
            )

        if (!workspace) {
            return
        }

        setCurrentWorkspaceId(
            workspace.id,
        )

        localStorage.setItem(
            CURRENT_WORKSPACE_KEY,
            workspace.id,
        )
    }

    function addWorkspace(
        workspace: WorkspaceDto,
    ) {
        setWorkspaces(current => [
            ...current,
            workspace,
        ])
    }

    function updateWorkspace(
        workspace: WorkspaceDto,
    ) {
        setWorkspaces(current =>
            current.map(item =>
                item.id === workspace.id
                    ? workspace
                    : item,
            ),
        )
    }

    function removeWorkspace(
        workspaceId: string,
    ) {
        setWorkspaces(current => {
            const remaining =
                current.filter(
                    item =>
                        item.id !==
                        workspaceId,
                )

            setCurrentWorkspaceId(
                currentId => {
                    if (
                        currentId !==
                        workspaceId
                    ) {
                        return currentId
                    }

                    const next =
                        remaining[0] ??
                        null

                    if (next) {
                        localStorage.setItem(
                            CURRENT_WORKSPACE_KEY,
                            next.id,
                        )

                        return next.id
                    }

                    localStorage.removeItem(
                        CURRENT_WORKSPACE_KEY,
                    )

                    return null
                },
            )

            return remaining
        })
    }

    const currentWorkspace =
        workspaces.find(
            workspace =>
                workspace.id ===
                currentWorkspaceId,
        ) ?? null

    const value = useMemo(
        () => ({
            workspaces,
            currentWorkspace,
            isLoading,
            setCurrentWorkspaceId:
            selectWorkspace,
            addWorkspace,
            updateWorkspace,
            removeWorkspace,
        }),
        [
            workspaces,
            currentWorkspace,
            isLoading,
        ],
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
        useContext(
            WorkspaceContext,
        )

    if (!context) {
        throw new Error(
            'useWorkspace must be used inside WorkspaceProvider',
        )
    }

    return context
}