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
const EMPTY_WORKSPACES: WorkspaceDto[] = []

export function WorkspaceProvider({ children }: { children: ReactNode }) {
    const { isAuthenticated, currentEmployee, accessToken, sessionEpoch } = useAuth()
    const owner = useMemo(() => ({ controller: new AbortController(), active: true }),
        [accessToken, currentEmployee?.id, sessionEpoch])
    const currentOwner = useRef(owner); currentOwner.current = owner
    const [catalogue, setCatalogue] = useState<{ owner: typeof owner; items: WorkspaceDto[] }>({ owner, items: [] })
    // Синхронная зеркальная копия каталога: несколько мутаций в одном тике
    // (например, два удаления подряд) видят результат друг друга, а не
    // устаревшее замыкание рендера.
    const itemsRef = useRef(catalogue)
    const [selectedId, setSelected] = useState<string | null>(() => localStorage.getItem(CURRENT_WORKSPACE_KEY))
    const selection = useRef(selectedId); selection.current = selectedId
    const [isLoading, setLoading] = useState(false)
    const catalogueVersion = useRef(0)
    const flight = useRef<{ owner: typeof owner; version: number; controller: AbortController; promise: Promise<void> }>()
    const workspaces = useMemo(() => catalogue.owner === owner ? catalogue.items : EMPTY_WORKSPACES, [catalogue, owner])
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
            itemsRef.current = { owner, items }
            setCatalogue({ owner, items })
            select(items.find(item => item.id === selection.current)?.id ?? items[0]?.id ?? null)
        }, [owner.controller.signal, controller.signal, ...(signal ? [signal] : [])]).catch(error => {
            if (current() && [401,403].includes(getApiErrorStatus(error) ?? 0)) {
                itemsRef.current = { owner, items: [] }
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
        if (!isAuthenticated) { itemsRef.current = { owner, items: [] }; setCatalogue({ owner, items: [] }); select(null) }
        else if (currentEmployee) void refreshWorkspaces().catch(() => {})
        return () => {
            owner.active = false; owner.controller.abort()
            if (flight.current?.owner === owner) flight.current = undefined
        }
    }, [owner, isAuthenticated, currentEmployee?.id, refreshWorkspaces, select])

    const addWorkspace = useCallback((workspace: WorkspaceDto) => {
        if (itemsRef.current.owner !== owner) return
        const items = [...itemsRef.current.items.filter(item => item.id !== workspace.id), workspace]
        itemsRef.current = { owner, items }
        setCatalogue({ owner, items })
    }, [owner])
    const updateWorkspace = useCallback((workspace: WorkspaceDto) => {
        if (itemsRef.current.owner !== owner) return
        const items = itemsRef.current.items.map(item => item.id === workspace.id ? workspace : item)
        itemsRef.current = { owner, items }
        setCatalogue({ owner, items })
    }, [owner])
    const removeWorkspace = useCallback((id: string) => {
        if (itemsRef.current.owner !== owner) return
        // itemsRef даёт актуальный каталог даже при нескольких удалениях в одном тике.
        const remaining = itemsRef.current.items.filter(item => item.id !== id)
        itemsRef.current = { owner, items: remaining }
        setCatalogue({ owner, items: remaining })
        if (selection.current === id) select(remaining[0]?.id ?? null)
    }, [owner, select])
    const setCurrentWorkspaceId = useCallback((id: string) => {
        if (itemsRef.current.owner === owner && itemsRef.current.items.some(item => item.id === id)) select(id)
    }, [owner, select])
    const currentWorkspace = useMemo(() => workspaces.find(item => item.id === selectedId) ?? null, [workspaces, selectedId])
    const value = useMemo<WorkspaceContextValue>(() => ({
        workspaces, currentWorkspace, isLoading,
        refreshWorkspaces, invalidateWorkspaces,
        setCurrentWorkspaceId, addWorkspace, updateWorkspace, removeWorkspace,
    }), [workspaces, currentWorkspace, isLoading, refreshWorkspaces, invalidateWorkspaces,
        setCurrentWorkspaceId, addWorkspace, updateWorkspace, removeWorkspace])
    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}
export function useWorkspace(): WorkspaceContextValue {
    const context = useContext(WorkspaceContext)
    if (!context) throw new Error('useWorkspace must be used inside WorkspaceProvider')
    return context
}
