import type { ConversationView } from '../../../api/types/conversation'

export type ConversationListTab = 'mine' | 'unassigned' | 'all' | 'group'

export type InboxSelection =
    | { view: 'MINE' }
    | { view: 'UNASSIGNED' }
    | { view: 'ALL' }
    | { view: 'GROUP'; groupId: string }

export interface InboxAccess {
    isEmployee: boolean
    isSuperAdmin: boolean
    canViewAll: boolean
    employeeId: string | null
}

export function normalizeSelection(selection: InboxSelection, access: InboxAccess): InboxSelection {
    if (selection.view === 'UNASSIGNED') return selection
    if (access.isSuperAdmin) return { view: 'ALL' }
    if (selection.view === 'ALL' && !access.canViewAll) return { view: 'MINE' }
    if (selection.view === 'GROUP' && (!access.isEmployee || !selection.groupId)) {
        return { view: 'MINE' }
    }
    return selection
}

export function selectionFromTab(tab: string | null, groupId?: string | null): InboxSelection {
    if (tab === 'group' && groupId) return { view: 'GROUP', groupId }
    if (tab === 'all') return { view: 'ALL' }
    if (tab === 'unassigned') return { view: 'UNASSIGNED' }
    return { view: 'MINE' }
}

export function selectionFromDefinition(definition: { view?: ConversationView | null; groupId?: string | null }): InboxSelection {
    // The backend defaults an omitted/null view to MINE, including saved filters.
    return selectionFromTab(definition.view?.toLowerCase() ?? null, definition.groupId)
}

export function selectionTab(selection: InboxSelection): ConversationListTab {
    return selection.view.toLowerCase() as ConversationListTab
}

export function conversationUrl(conversationId: string | undefined, selection: InboxSelection): string {
    const params = new URLSearchParams({ tab: selectionTab(selection) })
    if (selection.view === 'GROUP') params.set('groupId', selection.groupId)
    return `/inbox${conversationId ? `/${encodeURIComponent(conversationId)}` : ''}?${params}`
}
