
import { Link } from 'react-router-dom'

import ConversationGroupMenu from './groups/ConversationGroupMenu'
import { conversationUrl, selectionFromTab } from './list/inboxSelection'
import type { ConversationListTab } from './list/inboxSelection'
export type { ConversationListTab } from './list/inboxSelection'

import type {
    ConversationGroupDto,
    ConversationListItem,
} from '../../api/types/conversation'

import PlatformIcon from '../common/platform/PlatformIcon'
import PlatformName from '../common/platform/PlatformName'
import ClientAccountAvatar from '../clients/ClientAccountAvatar'

interface ConversationListItemProps {
    item: ConversationListItem
    active: boolean
    onOpenConversation?: () => void
    tab: ConversationListTab
    groupId?: string | null
    groups: ConversationGroupDto[]
    workspaceId: string
    canManageGroups: boolean
    onAddToGroup: (
        conversationId: string,
        groupId: string,
    ) => Promise<void>
    onRemoveFromGroup: (
        conversationId: string,
        groupId: string,
    ) => Promise<void>
}

function ConversationListItemComponent({
                                           item,
                                           active,
                                           onOpenConversation,
                                           tab,
                                           groupId,
                                           groups,
                                           workspaceId,
                                           canManageGroups,
                                           onAddToGroup,
                                           onRemoveFromGroup,
                                       }: ConversationListItemProps) {
    const { conversation, clientAccount, channel } = item

    const clientName =
        clientAccount?.displayName ||
        clientAccount?.username ||
        clientAccount?.phone ||
        clientAccount?.externalId ||
        'Неизвестный клиент'

    const platform =
        channel?.type ??
        clientAccount?.channelType ??
        'UNKNOWN'

    const channelName = channel?.name || 'Без названия'

    const timestamp =
        conversation.lastMessageAt ?? conversation.updatedAt

    const href = conversationUrl(conversation.id, selectionFromTab(tab, groupId))

    return (
        <div className="conversation-list-item-row">
            <Link
                to={href}
                onClick={event => {
                    if (event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
                        onOpenConversation?.()
                    }
                }}
                className={`conversation-list-item ${active ? 'active' : ''}`}
            >
                <div className="conversation-list-item-main">
                    <div className="conversation-list-item-top">
                        <strong>{clientName}</strong>
                        <time>{formatConversationTime(timestamp)}</time>
                    </div>

                    <div className="conversation-list-item-platform">
                        <ClientAccountAvatar
                            accountId={clientAccount?.id ?? ''}
                            avatarUrl={clientAccount?.avatarUrl ?? null}
                            displayName={clientName}
                            size={28}
                        />
                        <PlatformIcon type={platform} size={16} />
                        <strong>{channelName}</strong>
                        <PlatformName type={platform} />
                    </div>

                    {clientAccount && (
                        <div className="conversation-list-client-link">
                            <span className={`ui-status ${clientAccount.clientId ? 'conversation-client-linked' : 'conversation-client-unlinked'}`}>
                                {clientAccount.clientId ? 'Клиент есть' : 'Без клиента'}
                            </span>
                        </div>
                    )}

                    <div className="conversation-list-item-bottom">
                        <span>
                            {conversation.lastMessagePreview ?? 'Нет сообщений'}
                        </span>
                        {conversation.unreadCount > 0 && (
                            <span className="unread-badge">
                                {conversation.unreadCount}
                            </span>
                        )}
                    </div>
                </div>
            </Link>

            {canManageGroups && (
                <ConversationGroupMenu key={`${workspaceId}:${conversation.id}`} workspaceId={workspaceId} conversationId={conversation.id}
                    groups={groups} onAddToGroup={onAddToGroup} onRemoveFromGroup={onRemoveFromGroup} />
            )}
        </div>
    )
}

function formatConversationTime(value: string): string {
    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return ''
    }

    const now = new Date()

    const sameDay =
        date.getFullYear() === now.getFullYear() &&
        date.getMonth() === now.getMonth() &&
        date.getDate() === now.getDate()

    if (sameDay) {
        return date.toLocaleTimeString('ru-RU', {
            hour: '2-digit',
            minute: '2-digit',
        })
    }

    return date.toLocaleDateString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
    })
}

export default ConversationListItemComponent
