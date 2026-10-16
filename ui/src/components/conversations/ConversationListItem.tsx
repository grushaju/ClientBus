
import { useState } from 'react'
import { Link } from 'react-router-dom'

import {
    getConversationGroupIds,
} from '../../api/conversationApi'

import type {
    ConversationGroupDto,
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
                                           tab,
                                           groupId,
                                           groups,
                                           workspaceId,
                                           canManageGroups,
                                           onAddToGroup,
                                           onRemoveFromGroup,
                                       }: ConversationListItemProps) {
    const { conversation, clientAccount, channel } = item

    const [showGroupMenu, setShowGroupMenu] = useState(false)
    const [loadingMembership, setLoadingMembership] = useState(false)
    const [busyGroupId, setBusyGroupId] = useState<string | null>(null)
    const [selectedGroupIds, setSelectedGroupIds] = useState<string[]>([])
    const [actionError, setActionError] = useState<string | null>(null)

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

    const handleGroupMenuToggle = async () => {
        if (showGroupMenu) {
            setShowGroupMenu(false)
            return
        }

        setActionError(null)
        setLoadingMembership(true)

        try {
            const groupIds = await getConversationGroupIds(
                workspaceId,
                conversation.id,
            )

            setSelectedGroupIds(groupIds)
            setShowGroupMenu(true)
        } catch (err) {
            setActionError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить группы диалога.',
            )
        } finally {
            setLoadingMembership(false)
        }
    }

    const handleGroupToggle = async (
        targetGroupId: string,
        checked: boolean,
    ) => {
        setBusyGroupId(targetGroupId)
        setActionError(null)

        try {
            if (checked) {
                await onAddToGroup(conversation.id, targetGroupId)

                setSelectedGroupIds(current =>
                    current.includes(targetGroupId)
                        ? current
                        : [...current, targetGroupId],
                )
            } else {
                await onRemoveFromGroup(conversation.id, targetGroupId)

                setSelectedGroupIds(current =>
                    current.filter(id => id !== targetGroupId),
                )
            }
        } catch (err) {
            setActionError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось изменить состав группы.',
            )
        } finally {
            setBusyGroupId(null)
        }
    }

    const conversationUrl =
        tab === 'group' && groupId
            ? `/inbox/${conversation.id}?tab=group&groupId=${groupId}`
            : `/inbox/${conversation.id}?tab=${tab}`

    return (
        <div className="conversation-list-item-row">
            <Link
                to={conversationUrl}
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
                <div className="conversation-list-item-group-actions">
                    <button
                        type="button"
                        className="conversation-list-item-group-button"
                        aria-expanded={showGroupMenu}
                        disabled={loadingMembership || busyGroupId !== null}
                        onClick={() => void handleGroupMenuToggle()}
                    >
                        {loadingMembership ? 'Загрузка…' : 'В группу'}
                    </button>

                    {showGroupMenu && (
                        <div className="conversation-list-item-group-menu">
                            {groups.length === 0 && (
                                <span>Сначала создайте группу</span>
                            )}

                            {groups.map(group => (
                                <label
                                    key={group.id}
                                    className="conversation-list-item-group-option"
                                >
                                    <input
                                        type="checkbox"
                                        checked={selectedGroupIds.includes(group.id)}
                                        disabled={
                                            loadingMembership ||
                                            busyGroupId !== null
                                        }
                                        onChange={event =>
                                            void handleGroupToggle(
                                                group.id,
                                                event.target.checked,
                                            )
                                        }
                                    />
                                    <img
                                        className="conversation-list-item-group-icon"
                                        src={`/assets/group/${group.icon || 'users.svg'}`}
                                        alt=""
                                        aria-hidden="true"
                                    />
                                    <span>{group.name}</span>
                                </label>
                            ))}
                        </div>
                    )}

                    {actionError && (
                        <div className="conversation-list-item-group-error">
                            {actionError}
                        </div>
                    )}
                </div>
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
