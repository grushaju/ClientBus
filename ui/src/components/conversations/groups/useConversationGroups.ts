import { useRef, useState } from 'react'
import { useUiResource } from '../../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../../../api/events/uiScope'
import {
    addConversationToGroup, createGroup, deleteGroup, getGroups,
    removeConversationFromGroup, renameGroup,
} from '../../../api/conversationApi'
import type { ConversationGroupDto } from '../../../api/types/conversation'

export function useConversationGroups(workspaceId: string, enabled: boolean, visible = true) {
    const [groups, setGroups] = useState<ConversationGroupDto[]>([])
    const [error, setError] = useState<string | null>(null)
    const loadingChanges = useRef<Map<string, ConversationGroupDto | null> | null>(null)
    const resource = useUiResource('groups:' + workspaceId, enabled,
        event => affectsWorkspace(event, workspaceId) && ((isAccessEvent(event) && event.resource?.kind !== 'conversation') || event.type === 'groups.invalidated'),
        async signal => {
            loadingChanges.current = new Map()
            return getGroups(workspaceId, signal)
        }, items => {
            const merged = new Map(items.map(group => [group.id, group]))
            for (const [id, group] of loadingChanges.current ?? []) {
                if (group) merged.set(id, group)
                else merged.delete(id)
            }
            setGroups([...merged.values()].sort((a,b) => a.name.localeCompare(b.name, 'ru')))
            setError(null); loadingChanges.current = null
        }, err => {
            setError(err instanceof Error ? err.message : 'Не удалось загрузить группы диалогов.')
            loadingChanges.current = null
        }, () => { setGroups([]); loadingChanges.current = null }, undefined, visible)

    const upsert = (group: ConversationGroupDto) => {
        if (!resource.isCurrent()) return
        loadingChanges.current?.set(group.id, group)
        setGroups(current => [...current.filter(item => item.id !== group.id), group]
            .sort((a, b) => a.name.localeCompare(b.name, 'ru')))
    }

    return {
        groups, error,
        create: async (name: string, icon: string) => {
            const isCurrent = resource.capture()
            const group = await createGroup(workspaceId, name, icon)
            if (isCurrent()) upsert(group)
            return group
        },
        rename: async (id: string, name: string, icon: string) => {
            const isCurrent = resource.capture()
            const group = await renameGroup(workspaceId, id, name, icon)
            if (isCurrent()) upsert(group)
            return group
        },
        remove: async (id: string) => {
            const isCurrent = resource.capture()
            await deleteGroup(workspaceId, id)
            if (isCurrent()) {
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
