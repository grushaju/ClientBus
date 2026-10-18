import { useEffect, useRef, useState } from 'react'
import { createSavedConversationFilter, getSavedConversationFilters, updateSavedConversationFilter, deleteSavedConversationFilter } from '../../../api/savedConversationFilterApi'
import type { SavedConversationFilterDto, SavedFilterType } from '../../../api/types/savedConversationFilter'
import type { ConversationSearchRequest } from '../../../api/types/conversation'
import type { InboxAccess } from '../list/inboxSelection'

export function useSavedConversationFilters(workspaceId: string, access: InboxAccess) {
    const [systemFilters, setSystemFilters] = useState<SavedConversationFilterDto[]>([])
    const [privateFilters, setPrivateFilters] = useState<SavedConversationFilterDto[]>([])
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)
    const [version, setVersion] = useState(0)
    const mounted = useRef(false)
    const loadingChanges = useRef<Map<string, SavedConversationFilterDto> | null>(null)
    const deletedIds = useRef(new Set<string>())
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    const { isEmployee, isSuperAdmin, employeeId } = access
    const publish = (filter: SavedConversationFilterDto) => {
        if (!mounted.current || deletedIds.current.has(filter.id)) return
        loadingChanges.current?.set(filter.id, filter)
        const update = filter.filterType === 'SYSTEM' ? setSystemFilters : setPrivateFilters
        update(current => [...current.filter(item => item.id !== filter.id), filter])
    }
    useEffect(() => {
        if (!isEmployee && !isSuperAdmin) return
        let active = true
        const changes = new Map<string, SavedConversationFilterDto>()
        loadingChanges.current = changes
        setError(null)
        setLoading(true)
        // Publish each catalogue independently: a failed shared catalogue must
        // not hide personal filters, or delay them until another GET finishes.
        const load = async (type: SavedFilterType) => {
            try {
                const items = await getSavedConversationFilters(workspaceId, type)
                if (!active) return
                const merged = new Map(items.map(filter => [filter.id, filter]))
                for (const filter of changes.values()) {
                    if (filter.filterType === type) merged.set(filter.id, filter)
                }
                for (const id of deletedIds.current) merged.delete(id)
                const update = type === 'SYSTEM' ? setSystemFilters : setPrivateFilters
                update([...merged.values()])
            } catch (err) {
                if (!active) return
                const message = err instanceof Error ? err.message : 'Не удалось загрузить сохранённые фильтры.'
                setError(previous => previous ? `${previous} ${message}` : message)
            }
        }
        void Promise.all([
            load('SYSTEM'),
            isEmployee && employeeId ? load('PRIVATE') : Promise.resolve(),
        ]).finally(() => {
            if (active) setLoading(false)
            if (loadingChanges.current === changes) loadingChanges.current = null
        })
        return () => {
            active = false
            if (loadingChanges.current === changes) loadingChanges.current = null
        }
    }, [workspaceId, isEmployee, isSuperAdmin, employeeId, version])

    return {
        systemFilters, privateFilters, error, loading,
        reload: () => setVersion(current => current + 1),
        update: async (filter: SavedConversationFilterDto, name: string, definition: ConversationSearchRequest) => {
            if (filter.filterType === 'SYSTEM' && !isSuperAdmin) throw new Error('Системный фильтр можно сохранить только как личную копию.')
            const updated = await updateSavedConversationFilter(workspaceId, filter.id, {
                name, filterType: filter.filterType, filterDefinition: definition,
            })
            if (deletedIds.current.has(filter.id)) return null
            publish(updated)
            return updated
        },
        remove: async (filter: SavedConversationFilterDto) => {
            if (filter.filterType === 'SYSTEM' && !isSuperAdmin) throw new Error('Недостаточно прав для удаления системного фильтра.')
            await deleteSavedConversationFilter(workspaceId, filter.id)
            if (mounted.current) {
                deletedIds.current.add(filter.id)
                loadingChanges.current?.delete(filter.id)
                const update = filter.filterType === 'SYSTEM' ? setSystemFilters : setPrivateFilters
                update(current => current.filter(item => item.id !== filter.id))
                setVersion(current => current + 1)
            }
        },
        save: async (name: string, definition: ConversationSearchRequest) => {
            if (!isEmployee && !isSuperAdmin) throw new Error('Недостаточно прав для создания фильтра.')
            const created = await createSavedConversationFilter(workspaceId, {
                name, filterType: isSuperAdmin ? 'SYSTEM' : 'PRIVATE', filterDefinition: definition,
            })
            publish(created)
            return created
        },
    }
}

export type SavedConversationFiltersModel = ReturnType<typeof useSavedConversationFilters>
