import { useRef, type ReactNode } from 'react'
import ConversationGroupsSection from './groups/ConversationGroupsSection'
import type { ConversationGroupsModel } from './groups/useConversationGroups'
import type { InboxAccess } from './list/inboxSelection'
import { useNavigationDrawerFocus } from './useNavigationDrawerFocus'

interface Props {
    id: string; open: boolean; access: InboxAccess; groups: ConversationGroupsModel
    onGroupDeleted: (id: string) => void; onClose: () => void
    savedFilters: ReactNode; conditions: ReactNode; notice: ReactNode
    onGroupActionError: (message: string | null) => void
    returnFocus: () => HTMLElement | null
}
export default function InboxNavigation({ id, open, access, groups, onGroupDeleted, onClose, savedFilters, conditions, notice, onGroupActionError, returnFocus }: Props) {
    const navigation = useRef<HTMLDivElement>(null)
    useNavigationDrawerFocus(navigation, open, onClose, returnFocus)
    return <div ref={navigation} id={id} className="inbox-navigation" data-open={open} tabIndex={-1}
        role={open ? 'dialog' : undefined} aria-modal={open ? true : undefined} aria-label={open ? 'Меню Inbox' : 'Фильтры Inbox'}>
        <div className="inbox-workbench-top">
            <div className="inbox-navigation-header">
                <button type="button" className="ui-button ui-button-secondary inbox-navigation-close" onClick={onClose}>Закрыть меню</button>
            </div>
            {notice}
            {access.isEmployee && <ConversationGroupsSection model={groups} onActionError={onGroupActionError} onDeleted={onGroupDeleted} />}
            {savedFilters}
        </div>
        {conditions}
    </div>
}
