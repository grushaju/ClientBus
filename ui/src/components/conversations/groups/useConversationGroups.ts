import { useEffect, useRef, useState } from 'react'
import {
    addConversationToGroup, createGroup, deleteGroup, getGroups,
    removeConversationFromGroup, renameGroup,
} from '../../../api/conversationApi'
import type { ConversationGroupDto } from '../../../api/types/conversation'

export function useConversationGroups(workspaceId: string, enabled: boolean) {
    const [groups, setGroups] = useState<ConversationGroupDto[]>([])
    const [error, setError] = useState<string | null>(null)
    const mounted = useRef(false)
    const loadingChanges = useRef<Map<string, ConversationGroupDto | null> | null>(null)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])

    useEffect(() => {
        if (!enabled) return
        let active = true
        const changes = new Map<string, ConversationGroupDto | null>()
        loadingChanges.current = changes
        void getGroups(workspaceId).then(items => {
            if (!active) return
            // The GET may contain a snapshot from before a successful write.
            const merged = new Map(items.map(group => [group.id, group]))
            for (const [id, group] of changes) {
                if (group) merged.set(id, group)
                else merged.delete(id)
            }
            setGroups([...merged.values()].sort((a, b) => a.name.localeCompare(b.name, 'ru')))
        }).catch(err => {
            if (active) setError(err instanceof Error ? err.message : 'Не удалось загрузить группы диалогов.')
        }).finally(() => {
            if (loadingChanges.current === changes) loadingChanges.current = null
        })
        return () => {
            active = false
            if (loadingChanges.current === changes) loadingChanges.current = null
        }
    }, [workspaceId, enabled])

    const upsert = (group: ConversationGroupDto) => {
        if (!mounted.current) return
        loadingChanges.current?.set(group.id, group)
        setGroups(current => [...current.filter(item => item.id !== group.id), group]
            .sort((a, b) => a.name.localeCompare(b.name, 'ru')))
    }

    return {
        groups, error,
        create: async (name: string, icon: string) => {
            const group = await createGroup(workspaceId, name, icon)
            upsert(group)
            return group
        },
        rename: async (id: string, name: string, icon: string) => {
            const group = await renameGroup(workspaceId, id, name, icon)
            upsert(group)
            return group
        },
        remove: async (id: string) => {
            await deleteGroup(workspaceId, id)
            if (mounted.current) {
                loadingChanges.current?.set(id, null)
                setGroups(current => current.filter(group => group.id !== id))
            }
        },
        addConversation: (conversationId: string, groupId: string) =>
            addConversationToGroup(workspaceId, groupId, conversationId),
        removeConversation: (conversationId: string, groupId: string) =>
            removeConversationFromGroup(workspaceId, groupId, conversationId),
    }
}

export type ConversationGroupsModel = ReturnType<typeof useConversationGroups>
