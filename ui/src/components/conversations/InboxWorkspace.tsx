import { useCallback, useEffect, useId, useRef, useState } from 'react'
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
import { INBOX_DRAWER_QUERY, INBOX_MOBILE_QUERY, useMediaQuery } from './inboxViewport'

export default function InboxWorkspace() {
    const { currentWorkspace } = useWorkspace()
    const { conversationId } = useParams<{ conversationId?: string }>()
    const [showListOnMobile, setShowListOnMobile] = useState(false)
    const isMobile = useMediaQuery(INBOX_MOBILE_QUERY)
    useEffect(() => { setShowListOnMobile(false) }, [conversationId])
    const { currentEmployee, employeeWorkspaces, isEmployee, isSuperAdmin } = useAuth()
    const access = getInboxAccess(currentWorkspace?.id ?? '', currentEmployee?.id ?? null, isEmployee, isSuperAdmin, employeeWorkspaces)
    // Reset the list scope, but keep the conversation mounted: the existing
    // conversation view resolves its workspace from the conversation route.
    const scopeKey = `${currentWorkspace?.id}:${access.employeeId}:${isEmployee}:${isSuperAdmin}:${access.canViewAll}`
    return <InboxLayout
        showListOnMobile={showListOnMobile} hasConversation={!!conversationId}
        onShowList={() => setShowListOnMobile(true)}
        list={currentWorkspace
            ? <InboxWorkspaceContent key={scopeKey} workspace={currentWorkspace} access={access}
                visible={!isMobile || showListOnMobile || !conversationId}
                onOpenConversation={() => setShowListOnMobile(false)} />
            : <div className="conversation-list-state">Выберите Workspace</div>}
        content={conversationId ? <ConversationView visible={!isMobile || !showListOnMobile} /> : (
            <div className="inbox-empty"><h2>Inbox</h2><p>Выберите диалог из списка</p></div>
        )} />
}

function InboxWorkspaceContent({ workspace, access, onOpenConversation, visible }: {
    workspace: WorkspaceDto; access: InboxAccess; onOpenConversation: () => void; visible: boolean
}) {
    const route = useInboxSelection(access)
    const filters = useConversationFilters(route.selection)
    const groups = useConversationGroups(workspace.id, access.isEmployee && !!access.employeeId)
    const savedFilters = useSavedConversationFilters(workspace.id, access)
    const request = buildSearchRequest(route.selection, filters.applied)
    const conversations = useInboxConversations(workspace.id, request, access.isSuperAdmin || (access.isEmployee && !!access.employeeId))
    const [showNewConversation, setShowNewConversation] = useState(false)
    const [navigationOpen, setNavigationOpen] = useState(false)
    const isDrawer = useMediaQuery(INBOX_DRAWER_QUERY)
    const drawerOpen = isDrawer && navigationOpen && visible
    const [groupActionError, setGroupActionError] = useState<string | null>(null)
    const [showFilterConditions, setShowFilterConditions] = useState(false)
    const navigationId = useId()
    const navigationButton = useRef<HTMLButtonElement>(null)
    const searchInput = useRef<HTMLInputElement>(null)
    const focusAfterClose = useRef<'navigation' | 'search'>('navigation')
    useEffect(() => { if (!isDrawer || !visible) setNavigationOpen(false) }, [isDrawer, visible])
    const latest = useRef({ selection: route.selection, request: JSON.stringify(request) })
    latest.current = { selection: route.selection, request: JSON.stringify(request) }
    const mounted = useRef(false)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    useFirstConversation(route.selection, route.conversationId, conversations.items,
        conversations.loading || conversations.refreshing, conversations.error)

    const closeNavigation = useCallback(() => {
        focusAfterClose.current = 'navigation'
        setNavigationOpen(false)
    }, [])
    const returnNavigationFocus = useCallback(() => focusAfterClose.current === 'search'
        ? searchInput.current : navigationButton.current, [])
    const configureFilters = () => {
        setShowFilterConditions(true)
        focusAfterClose.current = 'search'
        setNavigationOpen(false)
        if (!drawerOpen) searchInput.current?.focus()
    }
    const select = (selection: InboxSelection) => {
        filters.clearSelection()
        route.select(selection)
        closeNavigation()
    }
    const selectFilter = (filter: SavedConversationFilterDto) => {
        const selection = normalizeSelection(selectionFromDefinition(filter.filterDefinition), access)
        if (filters.select(filter, selection)) {
            route.select(selection)
            closeNavigation()
        } else configureFilters()
    }
    const changeMembership = async (conversationId: string, groupId: string, add: boolean) => {
        await (add ? groups.addConversation : groups.removeConversation)(conversationId, groupId)
        if (mounted.current) conversations.reload()
    }
    const selectedFilter = [...savedFilters.privateFilters, ...savedFilters.systemFilters]
        .find(filter => filter.id === filters.selectedId)
    const selectedGroupId = route.selection.view === 'GROUP' ? route.selection.groupId : null
    const title = selectedFilter?.name ?? (route.selection.view === 'GROUP'
        ? groups.groups.find(group => group.id === selectedGroupId)?.name ?? 'Группа'
        : { MINE: 'Мои диалоги', UNASSIGNED: 'Свободные', ALL: 'Все диалоги' }[route.selection.view])
    const groupNotice = groupActionError && <div className="conversation-list-action-error" role="alert">
        <span>{groupActionError}</span>
        <button type="button" className="ui-button ui-button-secondary ui-button-sm" aria-label="Закрыть ошибку группы"
            onClick={() => setGroupActionError(null)}>Скрыть</button>
    </div>

    return (<>
        {drawerOpen && <button type="button" className="inbox-navigation-backdrop" data-inbox-backdrop="" aria-hidden="true" tabIndex={-1}
            onClick={closeNavigation} />}
        <InboxNavigation id={navigationId} open={drawerOpen} access={access}
                returnFocus={returnNavigationFocus} notice={drawerOpen ? groupNotice : null} onGroupActionError={setGroupActionError}
                selection={route.selection} groups={groups} onSelect={select}
                onClose={closeNavigation}
                onConfigureFilters={configureFilters}
                onGroupDeleted={id => {
                    if (latest.current.selection.view === 'GROUP' && latest.current.selection.groupId === id) select({ view: 'MINE' })
                }}
                savedFilters={(access.isEmployee || access.isSuperAdmin) && <SavedConversationFilters
                    model={savedFilters} selectedId={filters.selectedId} onSelect={selectFilter} isSuperAdmin={access.isSuperAdmin}
                    onSave={async name => {
                        const snapshot = latest.current.request
                        const revision = filters.revision
                        const created = await savedFilters.save(name, buildSavedDefinition(route.selection, filters.applied))
                        if (mounted.current && latest.current.request === snapshot) filters.markSaved(created.id, revision)
                    }} />} />
        <aside className="inbox-sidebar" aria-label="Список диалогов">
            <ConversationList
                toolbar={<ConversationListToolbar title={title} workspaceName={workspace.name} isEmployee={access.isEmployee}
                    onNewConversation={() => setShowNewConversation(true)} navigationId={navigationId}
                    navigationButtonRef={navigationButton}
                    navigationOpen={drawerOpen} onToggleNavigation={() => {
                        focusAfterClose.current = 'navigation'
                        setNavigationOpen(value => !value)
                    }} />}
                notice={!drawerOpen ? groupNotice : null}
                filters={<ConversationFilters model={filters} channels={conversations.channels}
                    searchInputRef={searchInput}
                    open={showFilterConditions} onToggle={() => setShowFilterConditions(value => !value)}
                    onApply={() => { if (filters.apply()) conversations.reload(); else setShowFilterConditions(true) }}
                    onReset={() => { filters.reset(); conversations.reload() }} />}
                content={<ConversationListContent
                    onOpenConversation={onOpenConversation}
                    loading={conversations.loading} refreshing={conversations.refreshing}
                    error={conversations.error} items={conversations.items} onRetry={conversations.reload}
                    conversationId={route.conversationId} tab={selectionTab(route.selection)}
                    selectedGroupId={route.selection.view === 'GROUP' ? route.selection.groupId : null}
                    groups={groups.groups} workspaceId={workspace.id} access={access}
                    handleAddConversationToGroup={(id, groupId) => changeMembership(id, groupId, true)}
                    handleRemoveConversationFromGroup={(id, groupId) => changeMembership(id, groupId, false)} />}
            />
        </aside>
        {showNewConversation && <NewConversationDialog workspaceId={workspace.id} channels={conversations.channels}
            onClose={() => setShowNewConversation(false)} onCreated={id => {
                if (!mounted.current) return
                setShowNewConversation(false)
                conversations.reload()
                route.openConversation(id)
            }} />}
    </>)
}
