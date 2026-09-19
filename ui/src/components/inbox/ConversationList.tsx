import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
    getEmployeeConversations,
    getUnassignedConversations,
    getWorkspaceConversations,
} from '../../api/conversationApi'
import { getClientAccountsByIds } from '../../api/clientAccountApi'
import { getWorkspaceChannels } from '../../api/channelApi'
import type {
    ChannelSummary,
    ConversationListItem,
} from '../../api/types/conversation'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import ConversationListItemComponent from './ConversationListItem'

type Tab = 'mine' | 'unassigned' | 'all'

function ConversationList() {
    const navigate = useNavigate()
    const { conversationId } = useParams<{
        conversationId?: string
    }>()

    const { currentEmployee, isSuperAdmin, isEmployee } = useAuth()
    const { currentWorkspace } = useWorkspace()

    const [tab, setTab] = useState<Tab>(
        isSuperAdmin ? 'all' : 'mine',
    )
    const [items, setItems] = useState<ConversationListItem[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        if (isSuperAdmin) {
            setTab('all')
        } else if (isEmployee && tab === 'all') {
            setTab('mine')
        }
    }, [isEmployee, isSuperAdmin, tab])

    useEffect(() => {
        if (!currentWorkspace) {
            setItems([])
            return
        }

        if (isEmployee && !currentEmployee) {
            setItems([])
            return
        }

        const workspaceId = currentWorkspace.id
        const employeeId = currentEmployee?.id

        const load = async () => {
            setLoading(true)
            setError(null)

            try {
                let conversations

                if (isSuperAdmin) {
                    conversations =
                        await getWorkspaceConversations(workspaceId)
                } else if (tab === 'unassigned') {
                    conversations =
                        await getUnassignedConversations(workspaceId)
                } else {
                    conversations =
                        await getEmployeeConversations(employeeId!)
                }

                const channels =
                    await getWorkspaceChannels(workspaceId)

                const channelByAccountId = new Map<
                    string,
                    ChannelSummary
                >()

                for (const channel of channels) {
                    if (channel.account) {
                        channelByAccountId.set(
                            channel.account.id,
                            channel,
                        )
                    }
                }

                const clientAccountIds = [
                    ...new Set(
                        conversations.map(
                            conversation =>
                                conversation.clientAccountId,
                        ),
                    ),
                ]

                const clientAccounts =
                    await getClientAccountsByIds(
                        clientAccountIds,
                    )

                const clientAccountById = new Map(
                    clientAccounts.map(account => [
                        account.id,
                        account,
                    ]),
                )

                const enrichedItems: ConversationListItem[] =
                    conversations.map(conversation => ({
                        conversation,
                        clientAccount:
                            clientAccountById.get(
                                conversation.clientAccountId,
                            ) ?? null,
                        channel:
                            channelByAccountId.get(
                                conversation.channelAccountId,
                            ) ?? null,
                    }))

                enrichedItems.sort((a, b) => {
                    const aTime =
                        a.conversation.lastMessageAt ??
                        a.conversation.updatedAt

                    const bTime =
                        b.conversation.lastMessageAt ??
                        b.conversation.updatedAt

                    return (
                        new Date(bTime).getTime() -
                        new Date(aTime).getTime()
                    )
                })

                setItems(enrichedItems)
            } catch (err) {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить диалоги',
                )
                setItems([])
            } finally {
                setLoading(false)
            }
        }

        void load()
    }, [
        currentWorkspace?.id,
        currentEmployee?.id,
        isEmployee,
        isSuperAdmin,
        tab,
    ])

    useEffect(() => {
        if (loading || error) {
            return
        }

        if (conversationId) {
            return
        }

        if (items.length === 0) {
            return
        }

        navigate(`/inbox/${items[0].conversation.id}`, {
            replace: true,
        })
    }, [
        conversationId,
        error,
        items,
        loading,
        navigate,
    ])

    if (!currentWorkspace) {
        return (
            <div className="conversation-list-state">
                Выберите Workspace
            </div>
        )
    }

    return (
        <div className="conversation-list">
            <div className="conversation-list-header">
                <div>
                    <h2>Диалоги</h2>

                    <span className="conversation-list-workspace">
                        {currentWorkspace.name}
                    </span>
                </div>

                {isEmployee && (
                    <div className="conversation-list-tabs">
                        <button
                            type="button"
                            className={
                                tab === 'mine' ? 'active' : ''
                            }
                            onClick={() => setTab('mine')}
                        >
                            Мои
                        </button>

                        <button
                            type="button"
                            className={
                                tab === 'unassigned'
                                    ? 'active'
                                    : ''
                            }
                            onClick={() =>
                                setTab('unassigned')
                            }
                        >
                            Неназначенные
                        </button>
                    </div>
                )}
            </div>

            {loading && (
                <div className="conversation-list-state">
                    Загрузка…
                </div>
            )}

            {!loading && error && (
                <div className="conversation-list-state conversation-list-error">
                    {error}
                </div>
            )}

            {!loading && !error && items.length === 0 && (
                <div className="conversation-list-state">
                    Диалогов нет
                </div>
            )}

            {!loading && !error && items.length > 0 && (
                <div className="conversation-list-items">
                    {items.map(item => (
                        <ConversationListItemComponent
                            key={item.conversation.id}
                            item={item}
                            active={
                                item.conversation.id ===
                                conversationId
                            }
                        />
                    ))}
                </div>
            )}
        </div>
    )
}

export default ConversationList
