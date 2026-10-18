import type { RefObject } from 'react'
import type { ConversationGroupDto } from '../../../api/types/conversation'
import type { InboxAccess, InboxSelection } from './inboxSelection'
import { selectionFromTab, selectionTab } from './inboxSelection'
import ConversationViewTabs from './ConversationViewTabs'
import IconOptionPicker from '../IconOptionPicker'
import { resolveGroupIcon } from '../groups/groupIcons'

export default function ConversationListToolbar({ access, selection, groups, onSelect, filterName, filterIcon, onResetFilter, onNewConversation, navigationId, navigationOpen, navigationButtonRef, onToggleNavigation }: {
    access: InboxAccess; selection: InboxSelection; groups: ConversationGroupDto[]; onSelect: (selection: InboxSelection) => void
    filterName: string; filterIcon: string; onResetFilter: () => void; onNewConversation: () => void
    navigationId: string; navigationOpen: boolean; onToggleNavigation: () => void; navigationButtonRef: RefObject<HTMLButtonElement>
}) {
    return <div className="inbox-list-toolbar">
        <div className="conversation-list-header-top">
            <div><button ref={navigationButtonRef} type="button" className="ui-button ui-button-secondary inbox-navigation-toggle"
                aria-expanded={navigationOpen} aria-controls={navigationId} onClick={onToggleNavigation}>Меню Inbox</button>
                <h2>Диалоги</h2></div>
            {access.isEmployee && <button type="button" className="ui-button ui-button-primary" onClick={onNewConversation}>+ Новый диалог</button>}
        </div>
        <nav className="inbox-scope-controls" aria-label="Область диалогов">
            <ConversationViewTabs access={access} tab={selectionTab(selection)} onSelect={tab => onSelect(selectionFromTab(tab))} />
            {access.isEmployee && <IconOptionPicker label="Выбрать группу диалогов" placeholder="Группа…"
                value={selection.view === 'GROUP' ? selection.groupId : null}
                options={groups.map(group => ({ value: group.id, label: group.name, icon: `/assets/group/${resolveGroupIcon(group.icon)}` }))}
                onChange={groupId => onSelect({ view: 'GROUP', groupId })} />}
        </nav>
        <div className="conversation-list-filter-row">
        <div className="conversation-list-filter" aria-label="Выбранный фильтр">
            <img src={`/assets/filter/${filterIcon}`} alt="" aria-hidden="true" /><span title={filterName}>{filterName}</span>
        </div>
        <button type="button" className="ui-button ui-button-secondary conversation-clear-filter" title="Очистить фильтр" aria-label="Очистить фильтр" onClick={onResetFilter}>
            <img src="/assets/filter/clear.svg" alt="" aria-hidden="true" />
        </button>
        </div>
    </div>
}
