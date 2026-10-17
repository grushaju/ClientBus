import { useEffect, useRef, useState } from 'react'
import { createSavedConversationFilter, getSavedConversationFilters } from '../../../api/savedConversationFilterApi'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { ConversationSearchRequest } from '../../../api/types/conversation'
import type { InboxAccess } from '../list/inboxSelection'

export function useSavedConversationFilters(workspaceId: string, access: InboxAccess) {
    const [systemFilters, setSystemFilters] = useState<SavedConversationFilterDto[]>([])
    const [privateFilters, setPrivateFilters] = useState<SavedConversationFilterDto[]>([])
    const [error, setError] = useState<string | null>(null)
    const mounted = useRef(false)
    const loadingChanges = useRef<Map<string, SavedConversationFilterDto> | null>(null)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    const { isEmployee, isSuperAdmin, employeeId } = access
    useEffect(() => {
        if (!isEmployee && !isSuperAdmin) return
        let active = true
        const changes = new Map<string, SavedConversationFilterDto>()
        loadingChanges.current = changes
        void Promise.all([
            getSavedConversationFilters(workspaceId, 'SYSTEM'),
            isEmployee && employeeId ? getSavedConversationFilters(workspaceId, 'PRIVATE') : Promise.resolve([]),
        ]).then(([system, personal]) => {
            if (!active) return
            const merge = (items: SavedConversationFilterDto[], type: 'SYSTEM' | 'PRIVATE') => {
                const merged = new Map(items.map(filter => [filter.id, filter]))
                for (const filter of changes.values()) {
                    if (filter.filterType === type) merged.set(filter.id, filter)
                }
                return [...merged.values()]
            }
            setSystemFilters(merge(system, 'SYSTEM'))
            setPrivateFilters(merge(personal, 'PRIVATE'))
        }).catch(err => {
            if (active) setError(err instanceof Error ? err.message : 'Не удалось загрузить сохранённые фильтры.')
        }).finally(() => {
            if (loadingChanges.current === changes) loadingChanges.current = null
        })
        return () => {
            active = false
            if (loadingChanges.current === changes) loadingChanges.current = null
        }
    }, [workspaceId, isEmployee, isSuperAdmin, employeeId])

    return {
        systemFilters, privateFilters, error,
        save: async (name: string, definition: ConversationSearchRequest) => {
            if (!isEmployee && !isSuperAdmin) throw new Error('Недостаточно прав для создания фильтра.')
            const created = await createSavedConversationFilter(workspaceId, {
                name, filterType: isSuperAdmin ? 'SYSTEM' : 'PRIVATE', filterDefinition: definition,
            })
            if (mounted.current) {
                loadingChanges.current?.set(created.id, created)
                const update = created.filterType === 'SYSTEM' ? setSystemFilters : setPrivateFilters
                update(current => [...current.filter(filter => filter.id !== created.id), created])
            }
            return created
        },
    }
}

export type SavedConversationFiltersModel = ReturnType<typeof useSavedConversationFilters>
