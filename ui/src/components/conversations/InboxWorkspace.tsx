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
import { selectionTab, type InboxAccess, type InboxSelection } from './list/inboxSelection'
import { useFirstConversation, useInboxSelection } from './list/useInboxSelection'
import { useInboxConversations } from './list/useInboxConversations'
import { useConversationGroups } from './groups/useConversationGroups'
import ConversationFilters from './filters/ConversationFilters'
import SavedConversationFilters from './filters/SavedConversationFilters'
import SaveConversationFilterForm from './filters/SaveConversationFilterForm'

import { useConversationFilters } from './filters/useConversationFilters'
import { useSavedConversationFilters } from './filters/useSavedConversationFilters'
import { buildSavedDefinition, buildSearchRequest, criteriaFromDefinition, criteriaToApply, sameFilterCriteria, validateCriteria } from './filters/conversationFilterModel'
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
    const groups = useConversationGroups(workspace.id, access.isEmployee && !!access.employeeId, visible)
    const savedFilters = useSavedConversationFilters(workspace.id, access, visible)
    const request = buildSearchRequest(route.selection, filters.applied)
    const conversations = useInboxConversations(workspace.id, request, access.isSuperAdmin || (access.isEmployee && !!access.employeeId), visible)
    const [showNewConversation, setShowNewConversation] = useState(false)
    const [navigationOpen, setNavigationOpen] = useState(false)
    const isDrawer = useMediaQuery(INBOX_DRAWER_QUERY)
    const drawerOpen = isDrawer && navigationOpen && visible
    const [groupActionError, setGroupActionError] = useState<string | null>(null)
    const [membershipError, setMembershipError] = useState<string | null>(null)
    const [filterEditor, setFilterEditor] = useState<'create' | 'update' | null>(null)
    const filterEditorSession = useRef(0)
    const [filterEditorRevision, setFilterEditorRevision] = useState(0)
    const changeFilterEditor = (editor: 'create' | 'update' | null) => {
        filterEditorSession.current++
        setFilterEditorRevision(filterEditorSession.current)
        setFilterEditor(editor)
    }
    const [filterActionError, setFilterActionError] = useState<string | null>(null)
    const [deletingFilter, setDeletingFilter] = useState(false)
    const navigationId = useId()
    const navigationButton = useRef<HTMLButtonElement>(null)
    const searchInput = useRef<HTMLInputElement>(null)
    const focusAfterClose = useRef<'navigation' | 'search'>('navigation')
    useEffect(() => { if (!isDrawer || !visible) setNavigationOpen(false) }, [isDrawer, visible])
    const latest = useRef({ selection: route.selection, request: JSON.stringify(request), selectedFilterId: filters.selectedId })
    latest.current = { selection: route.selection, request: JSON.stringify(request), selectedFilterId: filters.selectedId }
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
        setNavigationOpen(true)
    }
    const applyFilters = () => {
        if (filters.apply()) { conversations.reload(); closeNavigation() }
        else configureFilters()
    }
    const resetFilters = () => { filters.reset(); changeFilterEditor(null); conversations.reload() }
    const select = (selection: InboxSelection) => {
        changeFilterEditor(null)
        route.select(selection)
        closeNavigation()
    }
    const selectFilter = (filter: SavedConversationFilterDto) => {
        changeFilterEditor(null)
        if (filters.select(filter)) {
            closeNavigation()
        } else configureFilters()
    }
    const changeMembership = async (conversationId: string, groupId: string, add: boolean) => {
        setMembershipError(null)
        try {
            await (add ? groups.addConversation : groups.removeConversation)(conversationId, groupId)
        } catch (err) {
            // Keep the error visible even if reconciliation removes its row.
            if (mounted.current) setMembershipError(err instanceof Error ? err.message : 'Не удалось изменить состав группы.')
            throw err
        } finally {
            if (mounted.current) conversations.reload()
        }
    }
    const selectedFilter = [...savedFilters.privateFilters, ...savedFilters.systemFilters]
        .find(filter => filter.id === filters.selectedId)
    const canEditFilter = selectedFilter && (selectedFilter.filterType === 'PRIVATE' || access.isSuperAdmin)
    const deleteFilter = () => {
        if (!selectedFilter || !window.confirm(`Удалить фильтр «${selectedFilter.name}»?`)) return
        setDeletingFilter(true)
        setFilterActionError(null)
        const editorSession = filterEditorSession.current
        void savedFilters.remove(selectedFilter).then(() => {
            if (mounted.current) {
                filters.clearSelection(selectedFilter.id)
                if (latest.current.selectedFilterId === selectedFilter.id && filterEditorSession.current === editorSession) changeFilterEditor(null)
            }
        }).catch(err => {
            if (mounted.current) setFilterActionError(err instanceof Error ? err.message : 'Не удалось удалить фильтр.')
        }).finally(() => { if (mounted.current) setDeletingFilter(false) })
    }
    const saveFilter = async (name: string, update = false) => {
        const criteria = criteriaToApply(filters.draft, filters.applied)
        const error = validateCriteria(criteria)
        if (error) throw new Error(error)
        const revision = filters.revision
        const editorSession = filterEditorSession.current
        const definition = buildSavedDefinition(route.selection, criteria)
        const saved = update && selectedFilter
            ? await savedFilters.update(selectedFilter, name, definition)
            : await savedFilters.save(name, definition)
        if (mounted.current && saved && filterEditorSession.current === editorSession) {
            filters.markSaved(saved.id, revision, criteria)
            changeFilterEditor(null)
        }
    }
    const groupNotice = groupActionError && <div className="conversation-list-action-error" role="alert">
        <span>{groupActionError}</span>
        <button type="button" className="ui-button ui-button-secondary ui-button-sm" aria-label="Закрыть ошибку группы"
            onClick={() => setGroupActionError(null)}>Скрыть</button>
    </div>

    const filterEditorPanel = <>
        <SaveConversationFilterForm key={`${filterEditor}:${selectedFilter?.id ?? ''}:${filterEditorRevision}`}
            initialName={filterEditor === 'create' ? '' : selectedFilter?.name ?? ''}
            saveLabel={filterEditor !== 'create' && selectedFilter?.filterType === 'SYSTEM' && !access.isSuperAdmin ? 'Сохранить себе' : 'Сохранить'}
            disabled={deletingFilter}
            requiresChanges={filterEditor !== 'create' && !!canEditFilter}
            conditionsChanged={!!selectedFilter && !sameFilterCriteria(filters.draft, criteriaFromDefinition(selectedFilter.filterDefinition))}
            onDelete={filterEditor !== 'create' && canEditFilter ? deleteFilter : undefined}
            onCancel={() => { setFilterActionError(null); changeFilterEditor(null) }}
            onSave={name => saveFilter(name, filterEditor !== 'create' && !!canEditFilter)} />
        {filterActionError && <div className="ui-form-error" role="alert">{filterActionError}</div>}
    </>
    return (<>
        {drawerOpen && <button type="button" className="inbox-navigation-backdrop" data-inbox-backdrop="" aria-hidden="true" tabIndex={-1}
            onClick={closeNavigation} />}
        <InboxNavigation id={navigationId} open={drawerOpen} access={access}
                returnFocus={returnNavigationFocus} notice={groupNotice} onGroupActionError={setGroupActionError}
                groups={groups}
                onClose={closeNavigation}
                onGroupDeleted={id => {
                    if (latest.current.selection.view === 'GROUP' && latest.current.selection.groupId === id) select({ view: 'MINE' })
                }}
                savedFilters={(access.isEmployee || access.isSuperAdmin) && <SavedConversationFilters
                    model={savedFilters} selectedId={filters.selectedId} onSelect={selectFilter} isSuperAdmin={access.isSuperAdmin}
                    onCreate={() => { filters.reset(); setFilterActionError(null); changeFilterEditor('create'); conversations.reload() }}
                    />}
                conditions={<ConversationFilters area="conditions" model={filters} channels={conversations.channels} workspaceId={workspace.id}
                    filterName={filterEditor === 'create' ? 'Новый фильтр' : selectedFilter?.name} filterIcon={selectedFilter ? selectedFilter.filterType === 'SYSTEM' ? 'system.svg' : 'private.svg' : 'nofilter.svg'}
                    management={filterEditorPanel}
                    searchInputRef={searchInput}
                    onApply={applyFilters} onReset={resetFilters} />} />
        <aside className="inbox-sidebar" aria-label="Список диалогов">
            <ConversationList
                notice={membershipError && <div className="conversation-list-action-error" role="alert">
                    <span>{membershipError}</span>
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" aria-label="Закрыть ошибку состава группы"
                        onClick={() => setMembershipError(null)}>Скрыть</button>
                </div>}
                toolbar={<ConversationListToolbar access={access} selection={route.selection} groups={groups.groups} onSelect={select}
                    onResetFilter={resetFilters}
                    filterName={selectedFilter?.name ?? 'Без фильтра'}
                    filterIcon={selectedFilter ? selectedFilter.filterType === 'SYSTEM' ? 'system.svg' : 'private.svg' : 'nofilter.svg'}
                    onNewConversation={() => setShowNewConversation(true)} navigationId={navigationId}
                    navigationButtonRef={navigationButton}
                    navigationOpen={drawerOpen} onToggleNavigation={() => {
                        focusAfterClose.current = 'navigation'
                        setNavigationOpen(value => !value)
                    }} />}
                filters={<><ConversationFilters area="list" model={filters} channels={conversations.channels} workspaceId={workspace.id}
                    searchInputRef={searchInput}
                    onApply={applyFilters}
                    onReset={resetFilters} />
                </>}
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
