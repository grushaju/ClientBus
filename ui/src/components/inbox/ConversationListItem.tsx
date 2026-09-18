import { Link } from 'react-router-dom'

import type {
    ConversationListItem
} from '../../api/types/conversation'

interface ConversationListItemProps {
    item: ConversationListItem
    active?: boolean
}

function ConversationListItemComponent({
    item,
    active = false
}: ConversationListItemProps) {
    const {
        conversation,
        clientAccount,
        channel
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

    const timestamp =
        conversation.lastMessageAt ??
        conversation.updatedAt

    return (
        <Link
            to={`/inbox/${conversation.id}`}
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
                            timestamp
                        )}
                    </time>
                </div>

                <div className="conversation-list-item-platform">
                    {platform}
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
                            {
                                conversation.unreadCount
                            }
                        </span>
                    )}
                </div>
            </div>
        </Link>
    )
}

function formatConversationTime(
    value: string
): string {
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
        return date.toLocaleTimeString(
            'ru-RU',
            {
                hour: '2-digit',
                minute: '2-digit'
            }
        )
    }

    return date.toLocaleDateString(
        'ru-RU',
        {
            day: '2-digit',
            month: '2-digit'
        }
    )
}

export default ConversationListItemComponent
