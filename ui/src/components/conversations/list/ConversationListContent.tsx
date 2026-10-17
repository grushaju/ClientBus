import ConversationListItemComponent from '../ConversationListItem'
import type { ConversationGroupDto, ConversationListItem } from '../../../api/types/conversation'
import { canManageConversationGroups } from './inboxAccess'
import type { ConversationListTab, InboxAccess } from './inboxSelection'

interface Props {
    loading: boolean
    refreshing: boolean
    error: string | null
    onRetry: () => void
    onOpenConversation: () => void
    items: ConversationListItem[]
    conversationId?: string
    tab: ConversationListTab
    selectedGroupId: string | null
    groups: ConversationGroupDto[]
    workspaceId: string
    access: InboxAccess
    handleAddConversationToGroup: (conversationId: string, groupId: string) => Promise<void>
    handleRemoveConversationFromGroup: (conversationId: string, groupId: string) => Promise<void>
}

export default function ConversationListContent({ loading, refreshing, error, onRetry, onOpenConversation, items, conversationId, tab, selectedGroupId, groups, workspaceId, access, handleAddConversationToGroup, handleRemoveConversationFromGroup }: Props) {
    return (<>
        {loading && (
            <div className="conversation-list-state">
                Загрузка…
            </div>
        )}

        {!loading && error && (
            <div className={items.length > 0
                ? 'conversation-list-refresh-error'
                : 'conversation-list-state conversation-list-error'} role="alert">
                <span>{error}</span>
                <button type="button" className="ui-button ui-button-secondary" onClick={onRetry}>
                    Повторить
                </button>
            </div>
        )}

        {!loading && !error && items.length === 0 && (
            <div className="conversation-list-state">
                Диалогов нет
            </div>
        )}

        {!loading && items.length > 0 && (
            <div className="conversation-list-items" aria-busy={refreshing}>
                {items.map(item => (
                    <ConversationListItemComponent
                        key={item.conversation.id}
                        item={item}
                        onOpenConversation={onOpenConversation}
                        active={
                            item.conversation.id === conversationId
                        }
                        tab={tab}
                        groupId={selectedGroupId}
                        groups={groups}
                        workspaceId={workspaceId}
                        canManageGroups={canManageConversationGroups(access, item.conversation.assignedEmployeeId)}
                        onAddToGroup={
                            handleAddConversationToGroup
                        }
                        onRemoveFromGroup={
                            handleRemoveConversationFromGroup
                        }
                    />
                ))}
            </div>
        )}
    </>)
}
