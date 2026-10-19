import { useState } from 'react'
import { getConversationGroupIds } from '../../../api/conversationApi'
import { getApiErrorStatus } from '../../../api/apiClient'
import type { ConversationGroupDto } from '../../../api/types/conversation'
import GroupIcon from './GroupIcon'

interface Props {
    workspaceId: string
    conversationId: string
    groups: ConversationGroupDto[]
    onAddToGroup: (conversationId: string, groupId: string) => Promise<void>
    onRemoveFromGroup: (conversationId: string, groupId: string) => Promise<void>
}

export default function ConversationGroupMenu({ workspaceId, conversationId, groups, onAddToGroup, onRemoveFromGroup }: Props) {
    const [showGroupMenu, setShowGroupMenu] = useState(false)
    const [loadingMembership, setLoadingMembership] = useState(false)
    const [busyGroupId, setBusyGroupId] = useState<string | null>(null)
    const [selectedGroupIds, setSelectedGroupIds] = useState<string[]>([])
    const [actionError, setActionError] = useState<string | null>(null)

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
                conversationId,
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
                await onAddToGroup(conversationId, targetGroupId)

                setSelectedGroupIds(current =>
                    current.includes(targetGroupId)
                        ? current
                        : [...current, targetGroupId],
                )
            } else {
                await onRemoveFromGroup(conversationId, targetGroupId)

                setSelectedGroupIds(current =>
                    current.filter(id => id !== targetGroupId),
                )
            }
        } catch (err) {
            if ([403, 404, 409].includes(getApiErrorStatus(err) ?? 0)) {
                setShowGroupMenu(false)
                setSelectedGroupIds([])
            }
            setActionError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось изменить состав группы.',
            )
        } finally {
            setBusyGroupId(null)
        }
    }

    return (
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
                            <GroupIcon className="conversation-list-item-group-icon" icon={group.icon} />
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
    )
}
