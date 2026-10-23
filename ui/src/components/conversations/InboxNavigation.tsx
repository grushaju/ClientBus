import { useRef, type ReactNode, type RefObject } from 'react'
import ConversationGroupsSection from './groups/ConversationGroupsSection'
import type { ConversationGroupsModel } from './groups/useConversationGroups'
import type { InboxAccess } from './list/inboxSelection'
import { useNavigationDrawerFocus } from './useNavigationDrawerFocus'

interface Props {
    id: string; open: boolean; expanded: boolean; access: InboxAccess; groups: ConversationGroupsModel
    onGroupDeleted: (id: string) => void; onClose: () => void
    savedFilters: ReactNode; conditions: ReactNode; notice: ReactNode
    onGroupActionError: (message: string | null) => void
    returnFocus: () => HTMLElement | null
    toggleRef: RefObject<HTMLButtonElement>; onToggle: () => void
}
export default function InboxNavigation({ id, open, expanded, access, groups, onGroupDeleted, onClose, savedFilters, conditions, notice, onGroupActionError, returnFocus, toggleRef, onToggle }: Props) {
    const navigation = useRef<HTMLDivElement>(null)
    useNavigationDrawerFocus(navigation, open, onClose, returnFocus)
    return <div ref={navigation} className="inbox-navigation" data-open={open} data-expanded={expanded} tabIndex={-1}
        role={open ? 'dialog' : undefined} aria-modal={open ? true : undefined} aria-label="Фильтры и группы"
        onKeyDown={event => {
            if (!open && expanded && event.key === 'Escape' && !event.nativeEvent.isComposing && !event.defaultPrevented
                && !(event.target as HTMLElement).closest('[data-inbox-escape-layer="true"]')) {
                event.preventDefault()
                onToggle()
                toggleRef.current?.focus()
            }
        }}>
        <div id={id} className="inbox-navigation-content" hidden={!expanded}>
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
        <div className="inbox-navigation-edge">
            <button ref={toggleRef} type="button" className="inbox-navigation-toggle" aria-label="Фильтры и группы"
                aria-expanded={expanded} aria-controls={id} onClick={onToggle}
                title={expanded ? 'Свернуть панель фильтров и групп' : 'Раскрыть панель фильтров и групп'}>
                <span className="inbox-navigation-arrow" aria-hidden="true">{expanded ? '◂' : '▸'}</span>
                <span className="inbox-navigation-edge-label" aria-hidden="true">Фильтры и группы</span>
            </button>
        </div>
    </div>
}
