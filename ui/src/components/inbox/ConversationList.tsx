import { useEffect, useState } from 'react'
import {
    useNavigate,
    useParams,
    useSearchParams,
} from 'react-router-dom'

import {
    createGroup,
    getGroups,
    searchInbox,
} from '../../api/conversationApi'

import { getClientAccountsByIds } from '../../api/clientAccountApi'
import { getWorkspaceChannels } from '../../api/channelApi'

import type {
    ChannelDto,
} from '../../api/types/channel'

import type {
    ChannelSummary,
    ConversationDto,
    ConversationGroupDto,
    ConversationListItem,
} from '../../api/types/conversation'

import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'

import ConversationListItemComponent from './ConversationListItem'
import NewConversationDialog from './NewConversationDialog'

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
        employeeWorkspaces,
        isSuperAdmin,
        isEmployee,
    } = useAuth()

    const { currentWorkspace } =
        useWorkspace()

    const isWorkspaceSupervisor =
        isEmployee &&
        !!currentWorkspace &&
        employeeWorkspaces.some(
            membership =>
                membership.workspaceId === currentWorkspace.id &&
                membership.isSupervisor,
        )

    const canViewAll =
        isSuperAdmin || isWorkspaceSupervisor

    const requestedTab = searchParams.get('tab')

    const initialTab: Tab =
        isSuperAdmin
            ? requestedTab === 'unassigned'
                ? 'unassigned'
                : 'all'
            : canViewAll && requestedTab === 'all'
                ? 'all'
                : requestedTab === 'unassigned'
                    ? 'unassigned'
                    : 'mine'

    const [tab, setTab] = useState<Tab>(initialTab)

    const [groups, setGroups] =
        useState<ConversationGroupDto[]>([])

    const [selectedGroupId, setSelectedGroupId] =
        useState<string | null>(searchParams.get('groupId'))

    const [items, setItems] =
        useState<ConversationListItem[]>(
            [],
        )

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    const [
        channels,
        setChannels,
    ] = useState<ChannelDto[]>([])

    const [
        showNewConversation,
        setShowNewConversation,
    ] = useState(false)

    const [showCreateGroup, setShowCreateGroup] =
        useState(false)

    const [groupName, setGroupName] =
        useState('')

    const [creatingGroup, setCreatingGroup] =
        useState(false)

    const [groupError, setGroupError] =
        useState<string | null>(null)

    useEffect(() => {
        if (
            !currentWorkspace ||
            !isEmployee ||
            !currentEmployee
        ) {
            setGroups([])
            return
        }

        let active = true

        void getGroups(currentWorkspace.id)
            .then(result => {
                if (active) {
                    setGroups(result)
                }
            })
            .catch(() => {
                console.error(
                    'Не удалось загрузить группы диалогов:',
                    error,
                )
                if (active) {
                    setGroups([])
                }
            })

        return () => {
            active = false
        }
    }, [
        currentWorkspace?.id,
        currentEmployee?.id,
        isEmployee,
    ])

    useEffect(() => {
        if (
            isSuperAdmin &&
            tab !== 'all' &&
            tab !== 'unassigned'
        ) {
            setTab('all')
            return
        }

        if (
            isEmployee &&
            !canViewAll &&
            tab === 'all'
        ) {
            setTab('mine')
        }
    }, [
        canViewAll,
        isEmployee,
        isSuperAdmin,
        tab,
    ])

    useEffect(() => {
        const urlTab = searchParams.get('tab')

        if (
            urlTab === 'group' &&
            isEmployee &&
            !isSuperAdmin
        ) {
            const groupId = searchParams.get('groupId')

            if (groupId) {
                setSelectedGroupId(groupId)
                setTab('group')
                return
            }
        }

        if (
            urlTab === 'unassigned' &&
            (isEmployee || isSuperAdmin)
        ) {
            setTab('unassigned')
            return
        }

        if (
            urlTab === 'mine' &&
            isEmployee &&
            !isSuperAdmin
        ) {
            setTab('mine')
            return
        }

        if (
            urlTab === 'all' &&
            canViewAll
        ) {
            setTab('all')
        }
    }, [
        canViewAll,
        isEmployee,
        isSuperAdmin,
        searchParams,
    ])

    useEffect(() => {
        if (!currentWorkspace) {
            setItems([])
            setChannels([])
            return
        }

        if (
            isEmployee &&
            !currentEmployee
        ) {
            setItems([])
            setChannels([])
            return
        }

        if (tab === 'group' && !selectedGroupId) {
            setItems([])
            setLoading(false)
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

                const conversations: ConversationDto[] = []

                const view =
                    tab === 'group'
                        ? 'GROUP'
                        : tab === 'all'
                            ? 'ALL'
                            : tab === 'unassigned'
                                ? 'UNASSIGNED'
                                : 'MINE'

                let page = 0
                let hasNext = true

                while (hasNext) {
                    const result = await searchInbox(
                        workspaceId,
                        {
                            view,
                            ...(tab === 'group' && selectedGroupId
                                ? { groupId: selectedGroupId }
                                : {}),
                            page,
                            size: 10,
                        },
                    )

                    conversations.push(
                        ...result.content,
                    )

                    hasNext = result.hasNext
                    page = result.page + 1
                }


                const workspaceChannels =
                    await getWorkspaceChannels(
                        workspaceId,
                    )

                setChannels(
                    workspaceChannels,
                )

                const channelByAccountId =
                    new Map<
                        string,
                        ChannelSummary
                    >()

                for (
                    const channel of
                    workspaceChannels
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
                        tab === 'all' ||
                        (
                            tab === 'mine' &&
                            updated.assignedEmployeeId ===
                            currentEmployee?.id
                        ) ||
                        (
                            tab === 'unassigned' &&
                            updated.assignedEmployeeId === null
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
        setSelectedGroupId(null)
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

    const handleGroupChange = (
        groupId: string,
    ) => {
        setSelectedGroupId(groupId)
        setTab('group')

        const conversationPath =
            conversationId
                ? `/inbox/${conversationId}`
                : '/inbox'

        navigate(
            `${conversationPath}?tab=group&groupId=${groupId}`,
            {
                replace: true,
            },
        )
    }

    const handleCreateGroup = async () => {
        const name = groupName.trim()

        if (!name) {
            setGroupError('Введите название группы.')
            return
        }

        if (name.length > 100) {
            setGroupError(
                'Название группы не должно превышать 100 символов.',
            )
            return
        }

        setCreatingGroup(true)
        setGroupError(null)

        try {
            const created = await createGroup(
                currentWorkspace!.id,
                name,
            )

            setGroups(previous => [
                ...previous,
                created,
            ].sort((a, b) =>
                a.name.localeCompare(b.name, 'ru'),
            ))

            setGroupName('')
            setShowCreateGroup(false)
        } catch (err) {
            setGroupError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать группу.',
            )
        } finally {
            setCreatingGroup(false)
        }
    }

    const handleConversationCreated = (
        createdConversationId: string,
    ) => {
        setShowNewConversation(false)

        navigate(
            `/inbox/${createdConversationId}?tab=${tab}`,
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
        <>
            <div className="conversation-list">
                <div className="conversation-list-header">
                    <div className="conversation-list-header-top">
                        <div>
                            <h2>Диалоги</h2>

                            <span className="conversation-list-workspace">
                                {currentWorkspace.name}
                            </span>
                        </div>

                        {isEmployee && (
                            <button
                                type="button"
                                className="ui-button ui-button-primary"
                                onClick={() => setShowNewConversation(true)}
                            >
                                + Новый диалог
                            </button>
                        )}
                    </div>

                    {(isEmployee || isSuperAdmin) && (
                        <div className="conversation-list-tabs">
                            {isEmployee && (
                                <button
                                    type="button"
                                    className={tab === 'mine' ? 'active' : ''}
                                    onClick={() => handleTabChange('mine')}
                                >
                                    Мои диалоги
                                </button>
                            )}

                            <button
                                type="button"
                                className={tab === 'unassigned' ? 'active' : ''}
                                onClick={() => handleTabChange('unassigned')}
                            >
                                Свободные
                            </button>

                            {canViewAll && (
                                <button
                                    type="button"
                                    className={tab === 'all' ? 'active' : ''}
                                    onClick={() => handleTabChange('all')}
                                >
                                    Все
                                </button>
                            )}
                        </div>
                    )}

                    {isEmployee && (
                        <div className="conversation-list-groups">
                            {groups.length > 0 && (
                                <div className="conversation-list-tabs">
                                    {groups.map(group => (
                                        <button
                                            key={group.id}
                                            type="button"
                                            className={
                                                tab === 'group' &&
                                                selectedGroupId === group.id
                                                    ? 'active'
                                                    : ''
                                            }
                                            onClick={() => handleGroupChange(group.id)}
                                        >
                                            {group.name}
                                        </button>
                                    ))}
                                </div>
                            )}

                            <div className="conversation-list-group-actions">
                                <button
                                    type="button"
                                    className="ui-button ui-button-secondary"
                                    onClick={() => {
                                        setGroupError(null)
                                        setShowCreateGroup(value => !value)
                                    }}
                                >
                                    {showCreateGroup ? 'Отмена' : '+ Группа'}
                                </button>
                            </div>

                            {showCreateGroup && (
                                <form
                                    className="conversation-list-group-form"
                                    onSubmit={event => {
                                        event.preventDefault()
                                        void handleCreateGroup()
                                    }}
                                >
                                    <input
                                        className="ui-input"
                                        type="text"
                                        value={groupName}
                                        onChange={event => {
                                            setGroupName(event.target.value)
                                            setGroupError(null)
                                        }}
                                        placeholder="Название группы"
                                        maxLength={100}
                                        autoFocus
                                        disabled={creatingGroup}
                                    />

                                    <button
                                        type="submit"
                                        className="ui-button ui-button-primary"
                                        disabled={creatingGroup || !groupName.trim()}
                                    >
                                        {creatingGroup ? 'Создание…' : 'Создать'}
                                    </button>

                                    {groupError && (
                                        <div className="ui-form-error">
                                            {groupError}
                                        </div>
                                    )}
                                </form>
                            )}

                            {!showCreateGroup && groupError && (
                                <div className="ui-form-error">
                                    {groupError}
                                </div>
                            )}
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

            {showNewConversation &&
                (
                    <NewConversationDialog
                        workspaceId={
                            currentWorkspace.id
                        }
                        channels={
                            channels
                        }
                        onClose={() =>
                            setShowNewConversation(
                                false,
                            )
                        }
                        onCreated={
                            handleConversationCreated
                        }
                    />
                )}
        </>
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