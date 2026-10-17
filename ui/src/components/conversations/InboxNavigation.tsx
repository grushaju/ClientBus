import { useRef, type ReactNode } from 'react'
import ConversationViewTabs from './list/ConversationViewTabs'
import ConversationGroupsSection from './groups/ConversationGroupsSection'
import type { ConversationGroupsModel } from './groups/useConversationGroups'
import { selectionFromTab, selectionTab, type InboxAccess, type InboxSelection } from './list/inboxSelection'
import { useNavigationDrawerFocus } from './useNavigationDrawerFocus'

interface Props {
    id: string
    open: boolean
    access: InboxAccess
    selection: InboxSelection
    groups: ConversationGroupsModel
    onSelect: (selection: InboxSelection) => void
    onGroupDeleted: (id: string) => void
    onClose: () => void
    onConfigureFilters: () => void
    savedFilters: ReactNode
    notice: ReactNode
    onGroupActionError: (message: string | null) => void
    returnFocus: () => HTMLElement | null
}

export default function InboxNavigation({ id, open, access, selection, groups, onSelect, onGroupDeleted, onClose, onConfigureFilters, savedFilters, notice, onGroupActionError, returnFocus }: Props) {
    const navigation = useRef<HTMLDivElement>(null)
    useNavigationDrawerFocus(navigation, open, onClose, returnFocus)
    return (<div ref={navigation} id={id} className="inbox-navigation" data-open={open} tabIndex={-1}
        role={open ? 'dialog' : undefined} aria-modal={open ? true : undefined} aria-label={open ? 'Меню Inbox' : undefined}>
        <div className="inbox-navigation-header">
            <h2>Диалоги</h2>
            <button type="button" className="ui-button ui-button-secondary inbox-navigation-close" onClick={onClose}>
                Закрыть меню
            </button>
        </div>
        {notice}
        <nav className="inbox-navigation-links" aria-label="Навигация Inbox">
        <ConversationViewTabs access={access} tab={selectionTab(selection)} onSelect={tab => onSelect(selectionFromTab(tab))} />
        {access.isEmployee && <ConversationGroupsSection
            model={groups} selection={selection}
            onSelect={groupId => onSelect({ view: 'GROUP', groupId })}
            onActionError={onGroupActionError}
            onDeleted={onGroupDeleted} />}
        {savedFilters}
        <button type="button" className="ui-button ui-button-secondary inbox-navigation-settings" onClick={onConfigureFilters}>
            Настроить фильтры
        </button>
        </nav>
    </div>)
}
