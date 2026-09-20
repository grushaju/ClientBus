import { useEffect, useState } from 'react'
import {
    useNavigate,
    useParams,
    useSearchParams,
} from 'react-router-dom'

import {
    getUnassignedConversations,
    getWorkspaceConversations,
    getWorkspaceEmployeeConversations,
} from '../../api/conversationApi'

import { getClientAccountsByIds } from '../../api/clientAccountApi'
import { getWorkspaceChannels } from '../../api/channelApi'

import type {
    ChannelSummary,
    ConversationDto,
    ConversationListItem,
} from '../../api/types/conversation'

import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'

import ConversationListItemComponent from './ConversationListItem'
import type {
    ConversationListTab,
} from './ConversationListItem'

type Tab = ConversationListTab

function ConversationList() {
    const navigate = useNavigate()

    const { conversationId } = useParams<{
        conversationId?: string
    }>()

    const [searchParams] =
        useSearchParams()

    const {
        currentEmployee,
        isSuperAdmin,
        isEmployee,
    } = useAuth()

    const { currentWorkspace } =
        useWorkspace()

    const initialTab: Tab =
        searchParams.get('tab') ===
        'unassigned'
            ? 'unassigned'
            : 'mine'

    const [tab, setTab] =
        useState<Tab>(
            isSuperAdmin
                ? 'all'
                : initialTab,
        )

    const [items, setItems] =
        useState<ConversationListItem[]>(
            [],
        )

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (isSuperAdmin) {
            setTab('all')
            return
        }

        if (
            isEmployee &&
            tab === 'all'
        ) {
            setTab('mine')
        }
    }, [
        isEmployee,
        isSuperAdmin,
        tab,
    ])

    useEffect(() => {
        if (isSuperAdmin) {
            return
        }

        const urlTab =
            searchParams.get('tab')

        if (
            urlTab === 'mine' ||
            urlTab === 'unassigned'
        ) {
            setTab(urlTab)
        }
    }, [
        isSuperAdmin,
        searchParams,
    ])

    useEffect(() => {
        if (!currentWorkspace) {
            setItems([])
            return
        }

        if (
            isEmployee &&
            !currentEmployee
        ) {
            setItems([])
            return
        }

        const workspaceId =
            currentWorkspace.id

        const employeeId =
            currentEmployee?.id

        const load = async () => {
            setLoading(true)
            setError(null)

            try {
                let conversations:
                    ConversationDto[]

                if (isSuperAdmin) {
                    conversations =
                        await getWorkspaceConversations(
                            workspaceId,
                        )
                } else if (
                    tab === 'unassigned'
                ) {
                    conversations =
                        await getUnassignedConversations(
                            workspaceId,
                        )
                } else {
                    conversations =
                        await getWorkspaceEmployeeConversations(
                            workspaceId,
                            employeeId!,
                        )
                }

                const channels =
                    await getWorkspaceChannels(
                        workspaceId,
                    )

                const channelByAccountId =
                    new Map<
                        string,
                        ChannelSummary
                    >()

                for (
                    const channel of
                    channels
                ) {
                    if (
                        channel.account
                    ) {
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

                const clientAccountById =
                    new Map(
                        clientAccounts.map(
                            account => [
                                account.id,
                                account,
                            ],
                        ),
                    )

                const enrichedItems:
                    ConversationListItem[] =
                    conversations.map(
                        conversation => ({
                            conversation,
                            clientAccount:
                                clientAccountById.get(
                                    conversation.clientAccountId,
                                ) ?? null,
                            channel:
                                channelByAccountId.get(
                                    conversation.channelAccountId,
                                ) ?? null,
                        }),
                    )

                setItems(
                    sortConversationItems(
                        enrichedItems,
                    ),
                )
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
        const handleConversationUpdated = (
            event: Event,
        ) => {
            const customEvent =
                event as CustomEvent<ConversationDto>

            const updated =
                customEvent.detail

            if (!updated?.id) {
                return
            }

            setItems(
                currentItems => {
                    const existing =
                        currentItems.find(
                            item =>
                                item.conversation.id ===
                                updated.id,
                        )

                    if (!existing) {
                        return currentItems
                    }

                    const shouldRemain =
                        isSuperAdmin ||
                        (
                            tab === 'mine' &&
                            updated.assignedEmployeeId ===
                                currentEmployee?.id
                        ) ||
                        (
                            tab === 'unassigned' &&
                            updated.assignedEmployeeId ===
                                null
                        )

                    if (!shouldRemain) {
                        return currentItems.filter(
                            item =>
                                item.conversation.id !==
                                updated.id,
                        )
                    }

                    const updatedItems =
                        currentItems.map(
                            item =>
                                item.conversation.id ===
                                updated.id
                                    ? {
                                          ...item,
                                          conversation:
                                              updated,
                                      }
                                    : item,
                        )

                    return sortConversationItems(
                        updatedItems,
                    )
                },
            )
        }

        window.addEventListener(
            'clientbus:conversation-updated',
            handleConversationUpdated,
        )

        return () => {
            window.removeEventListener(
                'clientbus:conversation-updated',
                handleConversationUpdated,
            )
        }
    }, [
        currentEmployee?.id,
        isSuperAdmin,
        tab,
    ])

    useEffect(() => {
        if (
            loading ||
            error
        ) {
            return
        }

        if (conversationId) {
            return
        }

        if (
            items.length === 0
        ) {
            return
        }

        navigate(
            `/inbox/${items[0].conversation.id}?tab=${tab}`,
            {
                replace: true,
            },
        )
    }, [
        conversationId,
        error,
        items,
        loading,
        navigate,
        tab,
    ])

    const handleTabChange = (
        nextTab: Tab,
    ) => {
        setTab(nextTab)

        const conversationPath =
            conversationId
                ? `/inbox/${conversationId}`
                : '/inbox'

        navigate(
            `${conversationPath}?tab=${nextTab}`,
            {
                replace: true,
            },
        )
    }

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
                                tab ===
                                'mine'
                                    ? 'active'
                                    : ''
                            }
                            onClick={() =>
                                handleTabChange(
                                    'mine',
                                )
                            }
                        >
                            Мои диалоги
                        </button>

                        <button
                            type="button"
                            className={
                                tab ===
                                'unassigned'
                                    ? 'active'
                                    : ''
                            }
                            onClick={() =>
                                handleTabChange(
                                    'unassigned',
                                )
                            }
                        >
                            Свободные
                        </button>
                    </div>
                )}
            </div>

            {loading && (
                <div className="conversation-list-state">
                    Загрузка…
                </div>
            )}

            {!loading &&
                error && (
                    <div className="conversation-list-state conversation-list-error">
                        {error}
                    </div>
                )}

            {!loading &&
                !error &&
                items.length === 0 && (
                    <div className="conversation-list-state">
                        Диалогов нет
                    </div>
                )}

            {!loading &&
                !error &&
                items.length > 0 && (
                    <div className="conversation-list-items">
                        {items.map(
                            item => (
                                <ConversationListItemComponent
                                    key={
                                        item
                                            .conversation
                                            .id
                                    }
                                    item={
                                        item
                                    }
                                    active={
                                        item
                                            .conversation
                                            .id ===
                                        conversationId
                                    }
                                    tab={
                                        tab
                                    }
                                />
                            ),
                        )}
                    </div>
                )}
        </div>
    )
}

function sortConversationItems(
    items: ConversationListItem[],
): ConversationListItem[] {
    return [...items].sort(
        (a, b) => {
            const aTime =
                a.conversation
                    .lastMessageAt
                    ? new Date(
                          a.conversation
                              .lastMessageAt,
                      ).getTime()
                    : 0

            const bTime =
                b.conversation
                    .lastMessageAt
                    ? new Date(
                          b.conversation
                              .lastMessageAt,
                      ).getTime()
                    : 0

            return bTime - aTime
        },
    )
}

export default ConversationList
