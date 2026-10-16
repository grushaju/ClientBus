
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
    ConversationSearchRequest,
} from '../../api/types/conversation'

import {
    createSavedConversationFilter,
    getSavedConversationFilters,
} from '../../api/savedConversationFilterApi'

import type {
    SavedFilterType,
    SavedConversationFilterDto,
} from '../../api/types/savedConversationFilter'

import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'

import ConversationListItemComponent from './ConversationListItem'
import NewConversationDialog from './NewConversationDialog'

import type {
    ConversationListTab,
} from './ConversationListItem'

type Tab = ConversationListTab

const GROUP_ICON_OPTIONS = [
    { value: 'attach.svg', label: 'Вложение' },
    { value: 'ban.svg', label: 'Запрет' },
    { value: 'book.svg', label: 'Книга' },
    { value: 'bookmark.svg', label: 'Закладка' },
    { value: 'cross.svg', label: 'Крестик' },
    { value: 'dollar.svg', label: 'Деньги' },
    { value: 'fire.svg', label: 'Огонь' },
    { value: 'heart.svg', label: 'Сердце' },
    { value: 'info.svg', label: 'Информация' },
    { value: 'like.svg', label: 'Нравится' },
    { value: 'lock.svg', label: 'Защита' },
    { value: 'mail.svg', label: 'Почта' },
    { value: 'mute.svg', label: 'Без звука' },
    { value: 'notifications.svg', label: 'Уведомления' },
    { value: 'pin.svg', label: 'Закрепить' },
    { value: 'restore.svg', label: 'Восстановить' },
    { value: 'speaker.svg', label: 'Динамик' },
    { value: 'speech.svg', label: 'Речь' },
    { value: 'tag.svg', label: 'Метка' },
    { value: 'target.svg', label: 'Цель' },
    { value: 'tick.svg', label: 'Готово' },
    { value: 'trash.svg', label: 'Корзина' },
    { value: 'user.svg', label: 'Пользователь' },
    { value: 'users.svg', label: 'Группа' },
    { value: 'warning.svg', label: 'Предупреждение' },
] as const

const DEFAULT_GROUP_ICON = 'users.svg'

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
        <div className="conversation-list-group-icon-field">
            <span className="conversation-list-group-icon-label">
                Значок
            </span>

            <div
                className="conversation-list-group-icon-picker"
                role="group"
                aria-label="Значок группы"
            >
                {GROUP_ICON_OPTIONS.map(option => (
                    <button
                        key={option.value}
                        type="button"
                        className={
                            value === option.value
                                ? 'conversation-list-group-icon-option selected'
                                : 'conversation-list-group-icon-option'
                        }
                        title={option.label}
                        aria-label={option.label}
                        aria-pressed={value === option.value}
                        disabled={disabled}
                        onClick={() => onChange(option.value)}
                    >
                        <img
                            src={`/assets/group/${option.value}`}
                            alt=""
                            aria-hidden="true"
                        />
                    </button>
                ))}
            </div>
        </div>
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

    const [systemFilters, setSystemFilters] =
        useState<SavedConversationFilterDto[]>([])

    const [privateFilters, setPrivateFilters] =
        useState<SavedConversationFilterDto[]>([])

    const [selectedSavedFilter, setSelectedSavedFilter] =
        useState<SavedConversationFilterDto | null>(null)

    const [showSaveFilter, setShowSaveFilter] = useState(false)
    const [newFilterName, setNewFilterName] = useState('')

    const [savingFilter, setSavingFilter] = useState(false)
    const [saveFilterError, setSaveFilterError] =
        useState<string | null>(null)

    const [savedFiltersError, setSavedFiltersError] =
        useState<string | null>(null)

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

    const [groupIcon, setGroupIcon] = useState(DEFAULT_GROUP_ICON)

    const [creatingGroup, setCreatingGroup] = useState(false)

    const [groupError, setGroupError] =
        useState<string | null>(null)

    // SuperAdmin загружает только SYSTEM-фильтры.
    // Employee загружает SYSTEM и свои PRIVATE-фильтры.
    useEffect(() => {
        if (!currentWorkspace || (!isEmployee && !isSuperAdmin)) {
            setSystemFilters([])
            setPrivateFilters([])
            setSelectedSavedFilter(null)
            return
        }

        let active = true

        const loadFilters = async () => {
            setSavedFiltersError(null)

            try {
                const systemPromise = getSavedConversationFilters(
                    currentWorkspace.id,
                    'SYSTEM',
                )

                const privatePromise =
                    isEmployee && currentEmployee
                        ? getSavedConversationFilters(
                            currentWorkspace.id,
                            'PRIVATE',
                        )
                        : Promise.resolve([] as SavedConversationFilterDto[])

                const [system, personal] = await Promise.all([
                    systemPromise,
                    privatePromise,
                ])

                if (!active) return

                setSystemFilters(system)
                setPrivateFilters(personal)

                setSelectedSavedFilter(current =>
                    current
                        ? [...system, ...personal].find(
                        filter => filter.id === current.id,
                    ) ?? null
                        : null,
                )
            } catch (err) {
                if (!active) return

                setSavedFiltersError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить сохранённые фильтры.',
                )
            }
        }

        void loadFilters()

        return () => {
            active = false
        }
    }, [
        currentWorkspace?.id,
        currentEmployee?.id,
        isEmployee,
        isSuperAdmin,
    ])

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
                    selectedSavedFilter?.filterDefinition.view ??
                    (
                        tab === 'group'
                            ? 'GROUP'
                            : tab === 'all'
                                ? 'ALL'
                                : tab === 'unassigned'
                                    ? 'UNASSIGNED'
                                    : 'MINE'
                    )

                let page = 0
                let hasNext = true

                while (hasNext) {
                    const result = await searchInbox(
                        workspaceId,
                        {
                            ...(selectedSavedFilter
                                ? selectedSavedFilter.filterDefinition
                                : {}),
                            view,
                            ...(
                                selectedSavedFilter
                                    ? {}
                                    : tab === 'group' && selectedGroupId
                                        ? { groupId: selectedGroupId }
                                        : {}
                            ),
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
        selectedSavedFilter,
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

    const handleSavedFilterSelect = (
        filter: SavedConversationFilterDto,
    ) => {
        setSelectedSavedFilter(filter)

        const definition = filter.filterDefinition

        const nextTab: Tab =
            definition.view === 'GROUP'
                ? 'group'
                : definition.view === 'ALL'
                    ? 'all'
                    : definition.view === 'UNASSIGNED'
                        ? 'unassigned'
                        : 'mine'

        setTab(nextTab)
        setSelectedGroupId(
            definition.view === 'GROUP'
                ? definition.groupId ?? null
                : null,
        )
    }

    const handleSaveFilter = async () => {
        if (!currentWorkspace) {
            return
        }

        const name = newFilterName.trim()

        if (!name) {
            setSaveFilterError('Введите название фильтра.')
            return
        }

        if (name.length > 100) {
            setSaveFilterError(
                'Название фильтра не должно превышать 100 символов.',
            )
            return
        }

        if (!isEmployee && !isSuperAdmin) {
            setSaveFilterError(
                'Недостаточно прав для создания фильтра.',
            )
            return
        }

        const filterType: SavedFilterType =
            isSuperAdmin ? 'SYSTEM' : 'PRIVATE'

        const definition: ConversationSearchRequest = {
            ...(selectedSavedFilter?.filterDefinition ?? {}),
            view:
                selectedSavedFilter?.filterDefinition.view ??
                (
                    tab === 'group'
                        ? 'GROUP'
                        : tab === 'all'
                            ? 'ALL'
                            : tab === 'unassigned'
                                ? 'UNASSIGNED'
                                : 'MINE'
                ),
            ...(tab === 'group' && selectedGroupId
                ? { groupId: selectedGroupId }
                : {}),
        }

        // Пагинация не является частью сохранённого фильтра.
        delete definition.page
        delete definition.size

        setSavingFilter(true)
        setSaveFilterError(null)

        try {
            const created = await createSavedConversationFilter(
                currentWorkspace.id,
                {
                    name,
                    filterType,
                    filterDefinition: definition,
                },
            )

            if (created.filterType === 'SYSTEM') {
                setSystemFilters(current => [...current, created])
            } else {
                setPrivateFilters(current => [...current, created])
            }

            setSelectedSavedFilter(created)
            setNewFilterName('')
            setShowSaveFilter(false)
        } catch (err) {
            setSaveFilterError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось сохранить фильтр.',
            )
        } finally {
            setSavingFilter(false)
        }
    }

    const handleTabChange = (nextTab: Tab) => {
        setSelectedGroupId(null)
        setSelectedSavedFilter(null)
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
        setSelectedSavedFilter(null)
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
            setGroupIcon(DEFAULT_GROUP_ICON)
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
            setGroupIcon(DEFAULT_GROUP_ICON)
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
            setGroupIcon(DEFAULT_GROUP_ICON)
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
                                                icon={group.icon || DEFAULT_GROUP_ICON}
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
                                        setGroupIcon(DEFAULT_GROUP_ICON)
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
                                                        group.icon || DEFAULT_GROUP_ICON,
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
                                                setGroupIcon(DEFAULT_GROUP_ICON)
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

                    {(isEmployee || isSuperAdmin) && (
                        <div className="conversation-list-saved-filters">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={() => {
                                    setSaveFilterError(null)
                                    setNewFilterName('')
                                    setShowSaveFilter(value => !value)
                                }}
                            >
                                {showSaveFilter
                                    ? 'Отмена'
                                    : '+ Сохранить фильтр'}
                            </button>

                            {showSaveFilter && (
                                <form
                                    className="conversation-list-group-form"
                                    onSubmit={event => {
                                        event.preventDefault()
                                        void handleSaveFilter()
                                    }}
                                >
                                    <input
                                        className="ui-input"
                                        type="text"
                                        value={newFilterName}
                                        onChange={event => {
                                            setNewFilterName(event.target.value)
                                            setSaveFilterError(null)
                                        }}
                                        placeholder="Название фильтра"
                                        maxLength={100}
                                        autoFocus
                                        disabled={savingFilter}
                                    />

                                    <button
                                        type="submit"
                                        className="ui-button ui-button-primary"
                                        disabled={
                                            savingFilter ||
                                            !newFilterName.trim()
                                        }
                                    >
                                        {savingFilter
                                            ? 'Сохранение…'
                                            : 'Сохранить'}
                                    </button>

                                    {saveFilterError && (
                                        <div className="ui-form-error">
                                            {saveFilterError}
                                        </div>
                                    )}
                                </form>
                            )}

                            {savedFiltersError && (
                                <div className="conversation-list-state conversation-list-error">
                                    {savedFiltersError}
                                </div>
                            )}

                            {systemFilters.length > 0 && (
                                <div className="conversation-list-saved-filter-section">
                                    <span className="conversation-list-saved-filter-title">
                                        Общие фильтры
                                    </span>

                                    {systemFilters.map(filter => (
                                        <button
                                            key={filter.id}
                                            type="button"
                                            className={
                                                selectedSavedFilter?.id === filter.id
                                                    ? 'conversation-list-saved-filter active'
                                                    : 'conversation-list-saved-filter'
                                            }
                                            onClick={() =>
                                                handleSavedFilterSelect(filter)
                                            }
                                        >
                                            <img
                                                src="/assets/filter/system.svg"
                                                alt=""
                                                aria-hidden="true"
                                            />
                                            <span>{filter.name}</span>
                                        </button>
                                    ))}
                                </div>
                            )}

                            {isEmployee && privateFilters.length > 0 && (
                                <div className="conversation-list-saved-filter-section">
                                    <span className="conversation-list-saved-filter-title">
                                        Мои фильтры
                                    </span>

                                    {privateFilters.map(filter => (
                                        <button
                                            key={filter.id}
                                            type="button"
                                            className={
                                                selectedSavedFilter?.id === filter.id
                                                    ? 'conversation-list-saved-filter active'
                                                    : 'conversation-list-saved-filter'
                                            }
                                            onClick={() =>
                                                handleSavedFilterSelect(filter)
                                            }
                                        >
                                            <img
                                                src="/assets/filter/private.svg"
                                                alt=""
                                                aria-hidden="true"
                                            />
                                            <span>{filter.name}</span>
                                        </button>
                                    ))}
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

function GroupIcon({ icon }: { icon: string }) {
    const filename = GROUP_ICON_OPTIONS.some(
        option => option.value === icon,
    )
        ? icon
        : DEFAULT_GROUP_ICON

    return (
        <img
            className="conversation-group-icon"
            src={`/assets/group/${filename}`}
            alt=""
            aria-hidden="true"
        />
    )
}

export default ConversationList
