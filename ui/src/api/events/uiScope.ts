import { createContext, useContext } from 'react'
import type { UiEventEnvelope } from '../types/uiEvent'
import type { RequestBudget } from './requestBudget'

// Opaque identity: A -> B -> A creates three different owners. No token is stored in resource keys.
export const UiScopeContext = createContext<{ owner: object; workspaceId: string | null; accessVersion: number;
    historyAccount?: string;
    requestBudget?: RequestBudget; managedPolling?: boolean; isCurrent: () => boolean } | null>(null)
export const useUiScope = () => useContext(UiScopeContext)
export const isAccessEvent = (event: UiEventEnvelope) => event.type === 'access.invalidated' || event.type === 'identity.invalidated'
export function affectsWorkspace(event: UiEventEnvelope, workspaceId?: string | null): boolean {
    const target = event.workspaceId ?? (event.resource?.kind === 'workspace' ? event.resource.id : null)
    return !target || !workspaceId || target === workspaceId
}
export function affectsConversation(event: UiEventEnvelope, id?: string, workspaceId?: string | null): boolean {
    if (event.resource?.kind === 'conversation') return event.resource.id === id
    return affectsWorkspace(event, workspaceId)
}
