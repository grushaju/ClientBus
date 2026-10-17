import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import type { WorkspaceDto } from '../../auth/types'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import type { SavedConversationFilterDto } from '../../api/types/savedConversationFilter'
import ConversationList from './ConversationList'
import ConversationView from './ConversationView'
import InboxLayout from './InboxLayout'
import InboxNavigation from './InboxNavigation'
import NewConversationDialog from './NewConversationDialog'
import ConversationListToolbar from './list/ConversationListToolbar'
import ConversationListContent from './list/ConversationListContent'
import { getInboxAccess } from './list/inboxAccess'
import { normalizeSelection, selectionFromDefinition, selectionTab, type InboxAccess, type InboxSelection } from './list/inboxSelection'
import { useFirstConversation, useInboxSelection } from './list/useInboxSelection'
import { useInboxConversations } from './list/useInboxConversations'
import { useConversationGroups } from './groups/useConversationGroups'
import ConversationFilters from './filters/ConversationFilters'
import SavedConversationFilters from './filters/SavedConversationFilters'
import { useConversationFilters } from './filters/useConversationFilters'
import { useSavedConversationFilters } from './filters/useSavedConversationFilters'
import { buildSavedDefinition, buildSearchRequest } from './filters/conversationFilterModel'

export default function InboxWorkspace() {
    const { currentWorkspace } = useWorkspace()
    const { conversationId } = useParams<{ conversationId?: string }>()
    const { currentEmployee, employeeWorkspaces, isEmployee, isSuperAdmin } = useAuth()
    const access = getInboxAccess(currentWorkspace?.id ?? '', currentEmployee?.id ?? null, isEmployee, isSuperAdmin, employeeWorkspaces)
    // Reset the list scope, but keep the conversation mounted: the existing
    // conversation view resolves its workspace from the conversation route.
    const scopeKey = `${currentWorkspace?.id}:${access.employeeId}:${isEmployee}:${isSuperAdmin}:${access.canViewAll}`
    return <InboxLayout
        list={currentWorkspace
            ? <InboxWorkspaceContent key={scopeKey} workspace={currentWorkspace} access={access} />
            : <div className="conversation-list-state">Выберите Workspace</div>}
        content={conversationId ? <ConversationView /> : (
            <div className="inbox-empty"><h2>Inbox</h2><p>Выберите диалог из списка</p></div>
        )} />
}

function InboxWorkspaceContent({ workspace, access }: { workspace: WorkspaceDto; access: InboxAccess }) {
    const route = useInboxSelection(access)
    const filters = useConversationFilters(route.selection)
    const groups = useConversationGroups(workspace.id, access.isEmployee && !!access.employeeId)
    const savedFilters = useSavedConversationFilters(workspace.id, access)
    const request = buildSearchRequest(route.selection, filters.applied)
    const conversations = useInboxConversations(workspace.id, request, access.isSuperAdmin || (access.isEmployee && !!access.employeeId))
    const [showNewConversation, setShowNewConversation] = useState(false)
    const latest = useRef({ selection: route.selection, request: JSON.stringify(request) })
    latest.current = { selection: route.selection, request: JSON.stringify(request) }
    const mounted = useRef(false)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    useFirstConversation(route.selection, route.conversationId, conversations.items,
        conversations.loading || conversations.refreshing, conversations.error)

    const select = (selection: InboxSelection) => {
        filters.clearSelection()
        route.select(selection)
    }
    const selectFilter = (filter: SavedConversationFilterDto) => {
        const selection = normalizeSelection(selectionFromDefinition(filter.filterDefinition), access)
        if (filters.select(filter, selection)) route.select(selection)
    }
    const changeMembership = async (conversationId: string, groupId: string, add: boolean) => {
        await (add ? groups.addConversation : groups.removeConversation)(conversationId, groupId)
        if (mounted.current) conversations.reload()
    }

    return (<>
        <ConversationList
                toolbar={<ConversationListToolbar workspaceName={workspace.name} isEmployee={access.isEmployee}
                    onNewConversation={() => setShowNewConversation(true)} />}
                navigation={<InboxNavigation access={access} selection={route.selection} groups={groups} onSelect={select}
                    onGroupDeleted={id => {
                        if (latest.current.selection.view === 'GROUP' && latest.current.selection.groupId === id) select({ view: 'MINE' })
                    }} />}
                filters={<ConversationFilters model={filters} channels={conversations.channels}
                    onApply={() => { if (filters.apply()) conversations.reload() }}
                    onReset={() => { filters.reset(); conversations.reload() }} />}
                savedFilters={(access.isEmployee || access.isSuperAdmin) && <SavedConversationFilters
                    model={savedFilters} selectedId={filters.selectedId} onSelect={selectFilter}
                    onSave={async name => {
                        const snapshot = latest.current.request
                        const created = await savedFilters.save(name, buildSavedDefinition(route.selection, filters.applied))
                        if (mounted.current && latest.current.request === snapshot) filters.markSaved(created.id)
                    }} />}
                content={<ConversationListContent
                    loading={conversations.loading} refreshing={conversations.refreshing}
                    error={conversations.error} items={conversations.items} onRetry={conversations.reload}
                    conversationId={route.conversationId} tab={selectionTab(route.selection)}
                    selectedGroupId={route.selection.view === 'GROUP' ? route.selection.groupId : null}
                    groups={groups.groups} workspaceId={workspace.id} access={access}
                    handleAddConversationToGroup={(id, groupId) => changeMembership(id, groupId, true)}
                    handleRemoveConversationFromGroup={(id, groupId) => changeMembership(id, groupId, false)} />}
        />
        {showNewConversation && <NewConversationDialog workspaceId={workspace.id} channels={conversations.channels}
            onClose={() => setShowNewConversation(false)} onCreated={id => {
                if (!mounted.current) return
                setShowNewConversation(false)
                conversations.reload()
                route.openConversation(id)
            }} />}
    </>)
}
