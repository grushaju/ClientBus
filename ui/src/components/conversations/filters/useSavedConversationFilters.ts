import { useEffect, useRef, useState } from 'react'
import { createSavedConversationFilter, getSavedConversationFilters, updateSavedConversationFilter, deleteSavedConversationFilter } from '../../../api/savedConversationFilterApi'
import type { SavedConversationFilterDto, SavedFilterType } from '../../../api/types/savedConversationFilter'
import type { ConversationSearchRequest } from '../../../api/types/conversation'
import { useUiResource } from '../../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../../../api/events/uiScope'
import type { InboxAccess } from '../list/inboxSelection'

export function useSavedConversationFilters(workspaceId: string, access: InboxAccess, visible = true) {
    const [systemFilters, setSystemFilters] = useState<SavedConversationFilterDto[]>([])
    const [privateFilters, setPrivateFilters] = useState<SavedConversationFilterDto[]>([])
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)
    const [version, setVersion] = useState(0)
    const deletedIds = useRef(new Set<string>())
    const { isEmployee, isSuperAdmin, employeeId } = access
    const matches = (event: import('../../../api/types/uiEvent').UiEventEnvelope) => affectsWorkspace(event, workspaceId)
        && (isAccessEvent(event) || event.type === 'filters.invalidated')
    const journal = useRef(new Map<SavedFilterType, Map<string, SavedConversationFilterDto>>())
    const errors = useRef(new Map<SavedFilterType, string>())
    const pending = useRef(new Set<SavedFilterType>())
    const catalogue = (type: SavedFilterType, enabled: boolean) => ({
        key: 'filters:' + workspaceId + ':' + type + ':' + employeeId, enabled,
        read: async (signal: AbortSignal) => {
            journal.current.set(type, new Map())
            return getSavedConversationFilters(workspaceId, type, signal)
        },
        apply: (items: SavedConversationFilterDto[]) => {
            const merged = new Map(items.map(filter => [filter.id, filter]))
            for (const filter of journal.current.get(type)?.values() ?? []) merged.set(filter.id, filter)
            for (const id of deletedIds.current) merged.delete(id)
            const update = type === 'SYSTEM' ? setSystemFilters : setPrivateFilters
            update([...merged.values()])
            journal.current.delete(type); errors.current.delete(type)
            setError([...errors.current.values()].join(' ') || null)
        },
        fail: (err: unknown) => {
            journal.current.delete(type)
            errors.current.set(type, err instanceof Error ? err.message : 'Не удалось загрузить сохранённые фильтры.')
            setError([...errors.current.values()].join(' '))
        },
        reset: () => {
            journal.current.delete(type)
            const update = type === 'SYSTEM' ? setSystemFilters : setPrivateFilters
            update([])
        },
        pending: (value: boolean) => {
            if (value) pending.current.add(type)
            else pending.current.delete(type)
            setLoading(pending.current.size > 0)
        },
    })
    const system = catalogue('SYSTEM', isEmployee || isSuperAdmin)
    const personal = catalogue('PRIVATE', isEmployee && !!employeeId)
    const sharedResource = useUiResource(system.key, system.enabled, matches, system.read, system.apply, system.fail, system.reset, system.pending, visible)
    const privateResource = useUiResource(personal.key, personal.enabled, matches, personal.read, personal.apply, personal.fail, personal.reset, personal.pending, visible)
    const previousVersion = useRef(version)
    useEffect(() => {
        if (previousVersion.current !== version) { sharedResource.reload(); privateResource.reload() }
        previousVersion.current = version
    }, [version])
    const resourceFor = (type: SavedFilterType) => type === 'SYSTEM' ? sharedResource : privateResource
    const publish = (filter: SavedConversationFilterDto) => {
        if (!resourceFor(filter.filterType).isCurrent() || deletedIds.current.has(filter.id)) return
        journal.current.get(filter.filterType)?.set(filter.id, filter)
        const update = filter.filterType === 'SYSTEM' ? setSystemFilters : setPrivateFilters
        update(current => [...current.filter(item => item.id !== filter.id), filter])
    }

    return {
        systemFilters, privateFilters, error, loading,
        reload: () => setVersion(current => current + 1),
        update: async (filter: SavedConversationFilterDto, name: string, definition: ConversationSearchRequest) => {
            if (filter.filterType === 'SYSTEM' && !isSuperAdmin) throw new Error('Системный фильтр можно сохранить только как личную копию.')
            const isCurrent = resourceFor(filter.filterType).capture()
            const updated = await updateSavedConversationFilter(workspaceId, filter.id, {
                name, filterType: filter.filterType, filterDefinition: definition,
            })
            if (!isCurrent() || deletedIds.current.has(filter.id)) return null
            publish(updated)
            return updated
        },
        remove: async (filter: SavedConversationFilterDto) => {
            if (filter.filterType === 'SYSTEM' && !isSuperAdmin) throw new Error('Недостаточно прав для удаления системного фильтра.')
            const isCurrent = resourceFor(filter.filterType).capture()
            await deleteSavedConversationFilter(workspaceId, filter.id)
            if (isCurrent()) {
                deletedIds.current.add(filter.id)
                journal.current.get(filter.filterType)?.delete(filter.id)
                const update = filter.filterType === 'SYSTEM' ? setSystemFilters : setPrivateFilters
                update(current => current.filter(item => item.id !== filter.id))
                setVersion(current => current + 1)
            }
        },
        save: async (name: string, definition: ConversationSearchRequest) => {
            if (!isEmployee && !isSuperAdmin) throw new Error('Недостаточно прав для создания фильтра.')
            const isCurrent = resourceFor(isSuperAdmin ? 'SYSTEM' : 'PRIVATE').capture()
            const created = await createSavedConversationFilter(workspaceId, {
                name, filterType: isSuperAdmin ? 'SYSTEM' : 'PRIVATE', filterDefinition: definition,
            })
            if (isCurrent()) publish(created)
            return created
        },
    }
}

export type SavedConversationFiltersModel = ReturnType<typeof useSavedConversationFilters>
