import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'
import { getAccessToken } from '../auth/authStorage'
import { getCurrentUserWorkspaces } from '../api/workspaceApi'
import { getApiErrorStatus } from '../api/apiClient'
import { abortError, withCancellation } from '../api/events/requestScope'
import type { WorkspaceDto } from '../auth/types'

interface WorkspaceContextValue {
    workspaces: WorkspaceDto[]
    currentWorkspace: WorkspaceDto | null
    isLoading: boolean
    setCurrentWorkspaceId: (id: string) => void
    addWorkspace: (workspace: WorkspaceDto) => void
    updateWorkspace: (workspace: WorkspaceDto) => void
    removeWorkspace: (id: string) => void
    refreshWorkspaces: (signal?: AbortSignal) => Promise<void>
    invalidateWorkspaces: () => void
}
const WorkspaceContext = createContext<WorkspaceContextValue | undefined>(undefined)
const CURRENT_WORKSPACE_KEY = 'clientbus.currentWorkspaceId'

export function WorkspaceProvider({ children }: { children: ReactNode }) {
    const { isAuthenticated, currentEmployee, accessToken, sessionEpoch } = useAuth()
    const owner = useMemo(() => ({ controller: new AbortController(), active: true }),
        [accessToken, currentEmployee?.id, sessionEpoch])
    const currentOwner = useRef(owner); currentOwner.current = owner
    const [catalogue, setCatalogue] = useState<{ owner: typeof owner; items: WorkspaceDto[] }>({ owner, items: [] })
    const [selectedId, setSelected] = useState<string | null>(() => localStorage.getItem(CURRENT_WORKSPACE_KEY))
    const selection = useRef(selectedId); selection.current = selectedId
    const [isLoading, setLoading] = useState(false)
    const catalogueVersion = useRef(0)
    const flight = useRef<{ owner: typeof owner; version: number; controller: AbortController; promise: Promise<void> }>()
    const workspaces = catalogue.owner === owner ? catalogue.items : []
    const select = useCallback((id: string | null) => {
        selection.current = id; setSelected(id)
        if (id) localStorage.setItem(CURRENT_WORKSPACE_KEY, id)
        else localStorage.removeItem(CURRENT_WORKSPACE_KEY)
    }, [])
    const invalidateWorkspaces = useCallback(() => {
        catalogueVersion.current++
        flight.current?.controller.abort()
        flight.current = undefined
        setLoading(false)
    }, [])
    const refreshWorkspaces = useCallback((signal?: AbortSignal): Promise<void> => {
        if (!isAuthenticated || !currentEmployee || currentOwner.current !== owner || getAccessToken() !== accessToken) return Promise.resolve()
        const existing = flight.current
        const version = catalogueVersion.current
        if (existing?.owner === owner && existing.version === version) return withCancellation(() => existing.promise, signal ? [signal] : [])
        const controller = new AbortController()
        const current = () => owner.active && owner === currentOwner.current && version === catalogueVersion.current
            && !controller.signal.aborted && getAccessToken() === accessToken
        setLoading(true)
        const promise = withCancellation(async requestSignal => {
            const items = await getCurrentUserWorkspaces(requestSignal)
            if (!current() || requestSignal.aborted) throw abortError()
            setCatalogue({ owner, items })
            select(items.find(item => item.id === selection.current)?.id ?? items[0]?.id ?? null)
        }, [owner.controller.signal, controller.signal, ...(signal ? [signal] : [])]).catch(error => {
            if (current() && [401,403].includes(getApiErrorStatus(error) ?? 0)) {
                setCatalogue({ owner, items: [] }); select(null)
            }
            throw error
        }).finally(() => {
            if (flight.current?.promise === promise) {
                flight.current = undefined
                if (owner === currentOwner.current) setLoading(false)
            }
        })
        flight.current = { owner, version, controller, promise }; return promise
    }, [owner, isAuthenticated, currentEmployee?.id, accessToken, select])
    useEffect(() => {
        owner.active = true
        if (owner.controller.signal.aborted) owner.controller = new AbortController()
        if (!isAuthenticated) { setCatalogue({ owner, items: [] }); select(null) }
        else if (currentEmployee) void refreshWorkspaces().catch(() => {})
        return () => {
            owner.active = false; owner.controller.abort()
            if (flight.current?.owner === owner) flight.current = undefined
        }
    }, [owner, isAuthenticated, currentEmployee?.id, refreshWorkspaces, select])

    const value: WorkspaceContextValue = {
        workspaces, currentWorkspace: workspaces.find(item => item.id === selectedId) ?? null, isLoading,
        refreshWorkspaces, invalidateWorkspaces,
        setCurrentWorkspaceId: id => { if (workspaces.some(item => item.id === id)) select(id) },
        addWorkspace: workspace => setCatalogue(current => current.owner === owner
            ? { owner, items: [...current.items.filter(item => item.id !== workspace.id), workspace] } : current),
        updateWorkspace: workspace => setCatalogue(current => current.owner === owner
            ? { owner, items: current.items.map(item => item.id === workspace.id ? workspace : item) } : current),
        removeWorkspace: id => {
            const remaining = workspaces.filter(item => item.id !== id)
            setCatalogue({ owner, items: remaining })
            if (selection.current === id) select(remaining[0]?.id ?? null)
        },
    }
    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}
export function useWorkspace(): WorkspaceContextValue {
    const context = useContext(WorkspaceContext)
    if (!context) throw new Error('useWorkspace must be used inside WorkspaceProvider')
    return context
}
