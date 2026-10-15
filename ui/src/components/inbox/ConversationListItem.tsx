import { Link } from 'react-router-dom'

import type {
    ConversationListItem,
} from '../../api/types/conversation'

import PlatformIcon from '../common/platform/PlatformIcon'
import PlatformName from '../common/platform/PlatformName'
import ClientAccountAvatar from '../clients/ClientAccountAvatar'

export type ConversationListTab =
    | 'mine'
    | 'unassigned'
    | 'all'
    | 'group'

interface ConversationListItemProps {
    item: ConversationListItem
    active: boolean
    tab: ConversationListTab
    groupId?: string | null
}

function ConversationListItemComponent({
                                           item,
                                           active,
                                           tab,
                                           groupId,
                                       }: ConversationListItemProps) {
    const {
        conversation,
        clientAccount,
        channel,
    } = item

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

    const channelName =
        channel?.name ||
        'Без названия'

    const timestamp =
        conversation.lastMessageAt ??
        conversation.updatedAt

    return (
        <Link
            to={tab === 'group' && groupId
                ? `/inbox/${conversation.id}?tab=group&groupId=${groupId}`
                : `/inbox/${conversation.id}?tab=${tab}`}
            className={
                `conversation-list-item ${
                    active ? 'active' : ''
                }`
            }
        >
            <div className="conversation-list-item-main">
                <div className="conversation-list-item-top">
                    <strong>
                        {clientName}
                    </strong>

                    <time>
                        {formatConversationTime(
                            timestamp,
                        )}
                    </time>
                </div>

                <div className="conversation-list-item-platform">
                    <ClientAccountAvatar
                        accountId={clientAccount?.id ?? ''}
                        avatarUrl={clientAccount?.avatarUrl ?? null}
                        displayName={clientName}
                        size={28}
                    />

                    <PlatformIcon
                        type={platform}
                        size={16}
                    />

                    <strong>
                        {channelName}
                    </strong>

                    <PlatformName type={platform} />
                </div>

                <div className="conversation-list-item-bottom">
                    <span>
                        {
                            conversation.lastMessagePreview ??
                            'Нет сообщений'
                        }
                    </span>

                    {conversation.unreadCount > 0 && (
                        <span className="unread-badge">
                            {conversation.unreadCount}
                        </span>
                    )}
                </div>
            </div>
        </Link>
    )
}

function formatConversationTime(
    value: string,
): string {
    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return ''
    }

    const now = new Date()

    const sameDay =
        date.getFullYear() ===
            now.getFullYear() &&
        date.getMonth() ===
            now.getMonth() &&
        date.getDate() ===
            now.getDate()

    if (sameDay) {
        return date.toLocaleTimeString(
            'ru-RU',
            {
                hour: '2-digit',
                minute: '2-digit',
            },
        )
    }

    return date.toLocaleDateString(
        'ru-RU',
        {
            day: '2-digit',
            month: '2-digit',
        },
    )
}

export default ConversationListItemComponent
