import ConversationViewTabs from './list/ConversationViewTabs'
import ConversationGroupsSection from './groups/ConversationGroupsSection'
import type { ConversationGroupsModel } from './groups/useConversationGroups'
import { selectionFromTab, selectionTab, type InboxAccess, type InboxSelection } from './list/inboxSelection'

interface Props {
    access: InboxAccess
    selection: InboxSelection
    groups: ConversationGroupsModel
    onSelect: (selection: InboxSelection) => void
    onGroupDeleted: (id: string) => void
}

export default function InboxNavigation({ access, selection, groups, onSelect, onGroupDeleted }: Props) {
    return (<>
        <ConversationViewTabs access={access} tab={selectionTab(selection)} onSelect={tab => onSelect(selectionFromTab(tab))} />
        {access.isEmployee && <ConversationGroupsSection
            model={groups} selection={selection}
            onSelect={groupId => onSelect({ view: 'GROUP', groupId })}
            onDeleted={onGroupDeleted} />}
    </>)
}
