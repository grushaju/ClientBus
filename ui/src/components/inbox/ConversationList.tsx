import { useEffect, useState } from 'react'
import {
    useNavigate,
    useParams,
    useSearchParams,
} from 'react-router-dom'

import {
    addConversationToGroup,
    createGroup,
    renameGroup,
    deleteGroup,
    getGroups,
    removeConversationFromGroup,
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

const GROUP_ICON_OPTIONS = [
    { value: 'folder', label: 'Папка' },
    { value: 'star', label: 'Избранное' },
    { value: 'heart', label: 'Важное' },
    { value: 'tag', label: 'Метка' },
    { value: 'briefcase', label: 'Работа' },
    { value: 'clock', label: 'Позже' },
    { value: 'flag', label: 'Приоритет' },
    { value: 'users', label: 'Команда' },
    { value: 'message-circle', label: 'Сообщения' },
    { value: 'bookmark', label: 'Закладка' },
] as const

function GroupIconSelect({
    value,
    onChange,
    disabled = false,
}: {
    value: string
    onChange: (value: string) => void
    disabled?: boolean
}) {
    return (
        <label className="conversation-list-group-icon-field">
            <span>Значок</span>
            <select
                className="ui-input"
                value={value}
                onChange={event => onChange(event.target.value)}
                disabled={disabled}
            >
                {GROUP_ICON_OPTIONS.map(option => (
                    <option
                        key={option.value}
                        value={option.value}
                    >
                        {option.label}
                    </option>
                ))}
            </select>
        </label>
    )
}

function ConversationList() {
    const navigate = useNavigate()

    const { conversationId } = useParams<{
        conversationId?: string
    }>()

    const [searchParams] = useSearchParams()

    const {
        currentEmployee,
        employeeWorkspaces,
        isSuperAdmin,
        isEmployee,
    } = useAuth()

    const { currentWorkspace } = useWorkspace()

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
        useState<ConversationListItem[]>([])

    const [loading, setLoading] = useState(false)

    const [error, setError] = useState<string | null>(null)

    const [groupMembershipVersion, setGroupMembershipVersion] =
        useState(0)

    const [channels, setChannels] =
        useState<ChannelDto[]>([])

    const [showNewConversation, setShowNewConversation] =
        useState(false)

    const [showCreateGroup, setShowCreateGroup] =
        useState(false)

    const [showRenameGroup, setShowRenameGroup] =
        useState(false)

    const [renamingGroup, setRenamingGroup] =
        useState(false)

    const [deletingGroupId, setDeletingGroupId] =
        useState<string | null>(null)

    const [groupName, setGroupName] = useState('')

    const [groupIcon, setGroupIcon] = useState('folder')

    const [creatingGroup, setCreatingGroup] = useState(false)

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
            .catch(err => {
                console.error(
                    'Не удалось загрузить группы диалогов:',
                    err,
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

        const workspaceId = currentWorkspace.id

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

                    conversations.push(...result.content)

                    hasNext = result.hasNext
                    page = result.page + 1
                }

                const workspaceChannels =
                    await getWorkspaceChannels(workspaceId)

                setChannels(workspaceChannels)

                const channelByAccountId =
                    new Map<string, ChannelSummary>()

                for (const channel of workspaceChannels) {
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
                    await getClientAccountsByIds(clientAccountIds)

                const clientAccountById = new Map(
                    clientAccounts.map(
                        account => [account.id, account],
                    ),
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

                setItems(sortConversationItems(enrichedItems))
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
        selectedGroupId,
        groupMembershipVersion,
    ])

    useEffect(() => {
        const handleConversationUpdated = (event: Event) => {
            const customEvent =
                event as CustomEvent<ConversationDto>

            const updated = customEvent.detail

            if (!updated?.id) {
                return
            }

            setItems(currentItems => {
                const existing = currentItems.find(
                    item =>
                        item.conversation.id === updated.id,
                )

                if (!existing) {
                    return currentItems
                }

                const shouldRemain =
                    tab === 'group'
                        ? true
                        : tab === 'all' ||
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
                            item.conversation.id !== updated.id,
                    )
                }

                const updatedItems = currentItems.map(item =>
                    item.conversation.id === updated.id
                        ? {
                            ...item,
                            conversation: updated,
                        }
                        : item,
                )

                return sortConversationItems(updatedItems)
            })
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
        tab,
    ])

    useEffect(() => {
        if (loading || error || conversationId) {
            return
        }

        if (items.length === 0) {
            return
        }

        const firstConversationUrl =
            tab === 'group' && selectedGroupId
                ? `/inbox/${items[0].conversation.id}?tab=group&groupId=${selectedGroupId}`
                : `/inbox/${items[0].conversation.id}?tab=${tab}`

        navigate(firstConversationUrl, { replace: true })
    }, [
        conversationId,
        error,
        items,
        loading,
        navigate,
        tab,
        selectedGroupId,
    ])

    const handleTabChange = (nextTab: Tab) => {
        setSelectedGroupId(null)
        setTab(nextTab)

        const conversationPath =
            conversationId
                ? `/inbox/${conversationId}`
                : '/inbox'

        navigate(
            `${conversationPath}?tab=${nextTab}`,
            { replace: true },
        )
    }

    const handleGroupChange = (groupId: string) => {
        setSelectedGroupId(groupId)
        setTab('group')

        const conversationPath =
            conversationId
                ? `/inbox/${conversationId}`
                : '/inbox'

        navigate(
            `${conversationPath}?tab=group&groupId=${groupId}`,
            { replace: true },
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

        if (!currentWorkspace) {
            setGroupError('Не выбран Workspace.')
            return
        }

        setCreatingGroup(true)
        setGroupError(null)

        try {
            const created = await createGroup(
                currentWorkspace.id,
                name,
                groupIcon,
            )

            setGroups(previous =>
                [...previous, created].sort((a, b) =>
                    a.name.localeCompare(b.name, 'ru'),
                ),
            )

            setGroupName('')
            setGroupIcon('folder')
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

    const handleRenameGroup = async () => {
        const group = groups.find(
            item => item.id === selectedGroupId,
        )

        const name = groupName.trim()

        if (!group) {
            setGroupError('Выберите группу.')
            return
        }

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

        if (!currentWorkspace) {
            setGroupError('Не выбран Workspace.')
            return
        }

        setRenamingGroup(true)
        setGroupError(null)

        try {
            const updated = await renameGroup(
                currentWorkspace.id,
                group.id,
                name,
                groupIcon,
            )

            setGroups(previous =>
                previous
                    .map(item =>
                        item.id === updated.id ? updated : item,
                    )
                    .sort((a, b) =>
                        a.name.localeCompare(b.name, 'ru'),
                    ),
            )

            setGroupName('')
            setGroupIcon('folder')
            setGroupError(null)
            setShowRenameGroup(false)
        } catch (err) {
            setGroupError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось переименовать группу.',
            )
        } finally {
            setRenamingGroup(false)
        }
    }

    const handleDeleteGroup = async () => {
        const group = groups.find(
            item => item.id === selectedGroupId,
        )

        if (!group || deletingGroupId || !currentWorkspace) {
            return
        }

        if (!window.confirm(
            `Удалить группу «${group.name}»? Диалоги останутся без изменений.`,
        )) {
            return
        }

        setDeletingGroupId(group.id)
        setGroupError(null)

        try {
            await deleteGroup(
                currentWorkspace.id,
                group.id,
            )

            setGroups(previous =>
                previous.filter(item => item.id !== group.id),
            )

            setSelectedGroupId(null)
            setShowRenameGroup(false)
            setShowCreateGroup(false)
            setGroupName('')
            setGroupIcon('folder')
            setGroupError(null)
            setTab('mine')

            const conversationPath = conversationId
                ? `/inbox/${conversationId}`
                : '/inbox'

            navigate(`${conversationPath}?tab=mine`, {
                replace: true,
            })
        } catch (err) {
            setGroupError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить группу.',
            )
        } finally {
            setDeletingGroupId(null)
        }
    }

    const handleAddConversationToGroup = async (
        conversationId: string,
        groupId: string,
    ) => {
        if (!currentWorkspace) {
            throw new Error('Workspace не выбран.')
        }

        await addConversationToGroup(
            currentWorkspace.id,
            groupId,
            conversationId,
        )

        setGroupMembershipVersion(version => version + 1)
    }

    const handleRemoveConversationFromGroup = async (
        conversationId: string,
        groupId: string,
    ) => {
        if (!currentWorkspace) {
            throw new Error('Workspace не выбран.')
        }

        await removeConversationFromGroup(
            currentWorkspace.id,
            groupId,
            conversationId,
        )

        setGroupMembershipVersion(version => version + 1)
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
                                onClick={() =>
                                    setShowNewConversation(true)
                                }
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
                                    className={
                                        tab === 'mine' ? 'active' : ''
                                    }
                                    onClick={() =>
                                        handleTabChange('mine')
                                    }
                                >
                                    Мои диалоги
                                </button>
                            )}

                            <button
                                type="button"
                                className={
                                    tab === 'unassigned'
                                        ? 'active'
                                        : ''
                                }
                                onClick={() =>
                                    handleTabChange('unassigned')
                                }
                            >
                                Свободные
                            </button>

                            {canViewAll && (
                                <button
                                    type="button"
                                    className={
                                        tab === 'all' ? 'active' : ''
                                    }
                                    onClick={() =>
                                        handleTabChange('all')
                                    }
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
                                            onClick={() =>
                                                handleGroupChange(group.id)
                                            }
                                        >
                                            <GroupIcon
                                                icon={group.icon || 'folder'}
                                            />
                                            <span>{group.name}</span>
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
                                        setShowRenameGroup(false)
                                        setGroupName('')
                                        setGroupIcon('folder')
                                        setShowCreateGroup(value => !value)
                                    }}
                                >
                                    {showCreateGroup
                                        ? 'Отмена'
                                        : '+ Группа'}
                                </button>

                                {selectedGroupId &&
                                    groups.some(
                                        group =>
                                            group.id === selectedGroupId,
                                    ) && (
                                        <>
                                            <button
                                                type="button"
                                                className="ui-button ui-button-secondary"
                                                disabled={
                                                    deletingGroupId !== null ||
                                                    renamingGroup
                                                }
                                                onClick={() => {
                                                    const group = groups.find(
                                                        item =>
                                                            item.id ===
                                                            selectedGroupId,
                                                    )

                                                    if (!group) return

                                                    setGroupName(group.name)
                                                    setGroupIcon(
                                                        group.icon || 'folder',
                                                    )
                                                    setGroupError(null)
                                                    setShowCreateGroup(false)
                                                    setShowRenameGroup(
                                                        value => !value,
                                                    )
                                                }}
                                            >
                                                Переименовать
                                            </button>

                                            <button
                                                type="button"
                                                className="ui-button ui-button-secondary"
                                                disabled={
                                                    deletingGroupId !== null ||
                                                    renamingGroup
                                                }
                                                onClick={() =>
                                                    void handleDeleteGroup()
                                                }
                                            >
                                                {deletingGroupId ===
                                                selectedGroupId
                                                    ? 'Удаление…'
                                                    : 'Удалить'}
                                            </button>
                                        </>
                                    )}
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
                                            setGroupName(
                                                event.target.value,
                                            )
                                            setGroupError(null)
                                        }}
                                        placeholder="Название группы"
                                        maxLength={100}
                                        autoFocus
                                        disabled={creatingGroup}
                                    />

                                    <GroupIconSelect
                                        value={groupIcon}
                                        onChange={setGroupIcon}
                                        disabled={creatingGroup}
                                    />

                                    <button
                                        type="submit"
                                        className="ui-button ui-button-primary"
                                        disabled={
                                            creatingGroup ||
                                            !groupName.trim()
                                        }
                                    >
                                        {creatingGroup
                                            ? 'Создание…'
                                            : 'Создать'}
                                    </button>

                                    {groupError && (
                                        <div className="ui-form-error">
                                            {groupError}
                                        </div>
                                    )}
                                </form>
                            )}

                            {showRenameGroup &&
                                selectedGroupId && (
                                    <form
                                        className="conversation-list-group-form"
                                        onSubmit={event => {
                                            event.preventDefault()
                                            void handleRenameGroup()
                                        }}
                                    >
                                        <input
                                            className="ui-input"
                                            type="text"
                                            value={groupName}
                                            onChange={event => {
                                                setGroupName(
                                                    event.target.value,
                                                )
                                                setGroupError(null)
                                            }}
                                            placeholder="Новое название группы"
                                            maxLength={100}
                                            autoFocus
                                            disabled={renamingGroup}
                                        />

                                        <GroupIconSelect
                                            value={groupIcon}
                                            onChange={setGroupIcon}
                                            disabled={renamingGroup}
                                        />

                                        <button
                                            type="submit"
                                            className="ui-button ui-button-primary"
                                            disabled={
                                                renamingGroup ||
                                                !groupName.trim() ||
                                                deletingGroupId !== null
                                            }
                                        >
                                            {renamingGroup
                                                ? 'Сохранение…'
                                                : 'Сохранить'}
                                        </button>

                                        <button
                                            type="button"
                                            className="ui-button ui-button-secondary"
                                            disabled={renamingGroup}
                                            onClick={() => {
                                                setShowRenameGroup(false)
                                                setGroupName('')
                                                setGroupIcon('folder')
                                                setGroupError(null)
                                            }}
                                        >
                                            Отмена
                                        </button>

                                        {groupError && (
                                            <div className="ui-form-error">
                                                {groupError}
                                            </div>
                                        )}
                                    </form>
                                )}

                            {!showCreateGroup &&
                                !showRenameGroup &&
                                groupError && (
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
                                    item.conversation.id === conversationId
                                }
                                tab={tab}
                                groupId={selectedGroupId}
                                groups={groups}
                                workspaceId={currentWorkspace.id}
                                canManageGroups={
                                    isEmployee &&
                                    !!currentEmployee &&
                                    item.conversation.assignedEmployeeId ===
                                        currentEmployee.id
                                }
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
            </div>

            {showNewConversation && (
                <NewConversationDialog
                    workspaceId={currentWorkspace.id}
                    channels={channels}
                    onClose={() =>
                        setShowNewConversation(false)
                    }
                    onCreated={handleConversationCreated}
                />
            )}
        </>
    )
}

function sortConversationItems(
    items: ConversationListItem[],
): ConversationListItem[] {
    return [...items].sort((a, b) => {
        const aTime = a.conversation.lastMessageAt
            ? new Date(
                a.conversation.lastMessageAt,
            ).getTime()
            : 0

        const bTime = b.conversation.lastMessageAt
            ? new Date(
                b.conversation.lastMessageAt,
            ).getTime()
            : 0

        return bTime - aTime
    })
}

const groupIconPaths: Record<string, string> = {
    folder: 'M3 6h7l2 2h9v10H3z',
    star: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3l-5.6 2.9 1.1-6.2L3 9.6l6.2-.9z',
    heart: 'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8z',
    tag: 'M20.6 13.4 13.4 20.6 3 10.2V3h7.2z M7.5 7.5h.01',
    briefcase: 'M3 7h18v14H3z M8 7V4h8v3 M3 12h18 M10 12v2h4v-2',
    clock: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 6v6l4 2',
    flag: 'M5 21V4m0 0c6-4 8 4 14 0v10c-6 4-8-4-14 0',
    users: 'M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M20 21v-2a4 4 0 0 0-3-3.9 M16 3.1a4 4 0 0 1 0 7.8',
    'message-circle': 'M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8z',
    bookmark: 'M6 3h12v18l-6-4-6 4z',
}

function GroupIcon({ icon }: { icon: string }) {
    const filename = GROUP_ICON_OPTIONS.some(
        option => option.value === icon,
    )
        ? icon
        : 'folder'

    return (
        <img
            className="conversation-group-icon"
            src={`/assets/group/${filename}.svg`}
            alt=""
            aria-hidden="true"
        />
    )
}

export default ConversationList
