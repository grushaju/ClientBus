import {
    useEffect,
    useState,
} from 'react'

import {
    Link,
} from 'react-router-dom'

import {
    getAllWorkspaces,
    deleteWorkspace,
} from '../api/workspaceApi'

import {
    getWorkspaceEmployees,
} from '../api/employeeApi'

import {
    getWorkspaceChannels,
} from '../api/channelApi'

import {
    getWorkspaceConversations,
} from '../api/conversationApi'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    useWorkspace,
} from '../workspace/WorkspaceContext'

import type {
    WorkspaceDto,
} from '../auth/types'

import {
    CreateWorkspaceDialog,
} from '../components/workspaces/CreateWorkspaceDialog'

import {
    EditWorkspaceDialog,
} from '../components/workspaces/EditWorkspaceDialog'

import {
    DeleteWorkspaceDialog,
} from '../components/workspaces/DeleteWorkspaceDialog'

interface WorkspaceStats {
    employees: number
    channels: number
    conversations: number
    unread: number
    lastActivity: string | null
}

function formatDate(
    value: string | null,
): string {
    if (!value) {
        return 'Нет активности'
    }

    return new Intl.DateTimeFormat(
        'ru-RU',
        {
            dateStyle: 'medium',
            timeStyle: 'short',
        },
    ).format(new Date(value))
}

function WorkspacesPage() {
    const {
        addWorkspace,
        updateWorkspace,
        removeWorkspace,
    } = useWorkspace()

    const [workspaces, setWorkspaces] =
        useState<WorkspaceDto[]>([])

    const [statistics, setStatistics] =
        useState<
            Record<
                string,
                WorkspaceStats
            >
        >({})

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState<string | null>(null)

    const [createOpen, setCreateOpen] =
        useState(false)

    const [editWorkspace, setEditWorkspace] =
        useState<WorkspaceDto | null>(
            null,
        )

    const [deleteTarget, setDeleteTarget] =
        useState<WorkspaceDto | null>(
            null,
        )

    async function loadWorkspaces() {
        setLoading(true)
        setError(null)

        try {
            const result =
                await getAllWorkspaces()

            setWorkspaces(result)

            const statsEntries =
                await Promise.all(
                    result.map(
                        async workspace => {
                            const [
                                employees,
                                channels,
                                conversations,
                            ] =
                                await Promise.all([
                                    getWorkspaceEmployees(
                                        workspace.id,
                                    ),
                                    getWorkspaceChannels(
                                        workspace.id,
                                    ),
                                    getWorkspaceConversations(
                                        workspace.id,
                                    ),
                                ])

                            const visibleEmployees =
                                employees.filter(
                                    employee =>
                                        employee.role !==
                                        'SUPER_ADMIN',
                                )

                            const unread =
                                conversations.reduce(
                                    (
                                        total,
                                        conversation,
                                    ) =>
                                        total +
                                        conversation.unreadCount,
                                    0,
                                )

                            const lastActivity =
                                conversations.reduce<
                                    string | null
                                >(
                                    (
                                        latest,
                                        conversation,
                                    ) => {
                                        if (
                                            !conversation.lastMessageAt
                                        ) {
                                            return latest
                                        }

                                        if (
                                            !latest ||
                                            new Date(
                                                conversation.lastMessageAt,
                                            ).getTime() >
                                            new Date(
                                                latest,
                                            ).getTime()
                                        ) {
                                            return conversation.lastMessageAt
                                        }

                                        return latest
                                    },
                                    null,
                                )

                            return [
                                workspace.id,
                                {
                                    employees:
                                    visibleEmployees.length,
                                    channels:
                                    channels.length,
                                    conversations:
                                    conversations.length,
                                    unread,
                                    lastActivity,
                                },
                            ] as const
                        },
                    ),
                )

            setStatistics(
                Object.fromEntries(
                    statsEntries,
                ),
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить рабочие пространства',
            )
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadWorkspaces()
    }, [])

    function handleCreated(
        workspace: WorkspaceDto,
    ) {
        setWorkspaces(current => [
            ...current,
            workspace,
        ])

        addWorkspace(workspace)
    }

    function handleUpdated(
        workspace: WorkspaceDto,
    ) {
        setWorkspaces(current =>
            current.map(item =>
                item.id === workspace.id
                    ? workspace
                    : item,
            ),
        )

        updateWorkspace(workspace)
    }

    async function handleDelete() {
        if (!deleteTarget) {
            return
        }

        const workspace =
            deleteTarget

        try {
            await deleteWorkspace(
                workspace.id,
            )

            setWorkspaces(current =>
                current.filter(
                    item =>
                        item.id !==
                        workspace.id,
                ),
            )

            removeWorkspace(
                workspace.id,
            )

            setDeleteTarget(null)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить рабочее пространство',
            )
        }
    }

    return (
        <div className="workspaces-page">
            <div className="workspaces-header">
                <div>
                    <h1>
                        Рабочие пространства
                    </h1>

                    <p className="workspaces-subtitle">
                        Рабочие пространства организации
                        и их текущая активность.
                    </p>
                </div>

                <button
                    type="button"
                    className="ui-button ui-button-primary"
                    onClick={() =>
                        setCreateOpen(true)
                    }
                >
                    + Добавить
                </button>
            </div>

            {error && (
                <div className="workspaces-error">
                    {error}
                </div>
            )}

            {loading ? (
                <div className="workspaces-loading">
                    Загрузка…
                </div>
            ) : workspaces.length === 0 ? (
                <div className="workspaces-empty">
                    <strong>
                        Рабочих пространств пока нет
                    </strong>

                    <span>
                        Создайте первое рабочее
                        пространство, чтобы начать
                        работу.
                    </span>

                    <button
                        type="button"
                        className="ui-button ui-button-primary"
                        onClick={() =>
                            setCreateOpen(true)
                        }
                    >
                        + Добавить
                    </button>
                </div>
            ) : (
                <div className="workspaces-grid">
                    {workspaces.map(
                        workspace => {
                            const stats =
                                statistics[
                                    workspace.id
                                    ]

                            return (
                                <div
                                    key={
                                        workspace.id
                                    }
                                    className="workspace-card"
                                >
                                    <div className="workspace-card-header">
                                        <div>
                                            <h2>
                                                {
                                                    workspace.name
                                                }
                                            </h2>

                                            <span>
                                                Рабочее
                                                пространство
                                            </span>
                                        </div>

                                        <span className="workspace-card-status">
                                            ●
                                        </span>
                                    </div>

                                    <div className="workspace-stats">
                                        <div>
                                            <strong>
                                                {stats
                                                        ?.employees ??
                                                    '—'}
                                            </strong>

                                            <span>
                                                Сотрудников
                                            </span>
                                        </div>

                                        <div>
                                            <strong>
                                                {stats
                                                        ?.channels ??
                                                    '—'}
                                            </strong>

                                            <span>
                                                Каналов
                                            </span>
                                        </div>

                                        <div>
                                            <strong>
                                                {stats
                                                        ?.conversations ??
                                                    '—'}
                                            </strong>

                                            <span>
                                                Диалогов
                                            </span>
                                        </div>

                                        <div>
                                            <strong>
                                                {stats
                                                        ?.unread ??
                                                    '—'}
                                            </strong>

                                            <span>
                                                Непрочитано
                                            </span>
                                        </div>
                                    </div>

                                    <div className="workspace-card-last-activity">
                                        Последняя
                                        активность:{' '}
                                        {formatDate(
                                            stats
                                                ?.lastActivity ??
                                            null,
                                        )}
                                    </div>

                                    <div className="workspace-card-actions">
                                        <Link
                                            to={`/workspaces/${workspace.id}`}
                                            className="ui-button ui-button-secondary"
                                        >
                                            Подробнее
                                        </Link>

                                        <button
                                            type="button"
                                            className="ui-button ui-button-secondary"
                                            onClick={() =>
                                                setEditWorkspace(
                                                    workspace,
                                                )
                                            }
                                        >
                                            Изменить
                                        </button>

                                        <button
                                            type="button"
                                            className="ui-button ui-button-danger"
                                            onClick={() =>
                                                setDeleteTarget(
                                                    workspace,
                                                )
                                            }
                                        >
                                            Удалить
                                        </button>
                                    </div>
                                </div>
                            )
                        },
                    )}
                </div>
            )}

            <CreateWorkspaceDialog
                open={createOpen}
                onClose={() =>
                    setCreateOpen(false)
                }
                onCreated={
                    handleCreated
                }
            />

            {editWorkspace && (
                <EditWorkspaceDialog
                    open
                    workspace={
                        editWorkspace
                    }
                    onClose={() =>
                        setEditWorkspace(
                            null,
                        )
                    }
                    onUpdated={
                        handleUpdated
                    }
                />
            )}

            {deleteTarget && (
                <DeleteWorkspaceDialog
                    open
                    workspace={
                        deleteTarget
                    }
                    onClose={() =>
                        setDeleteTarget(
                            null,
                        )
                    }
                    onConfirm={
                        handleDelete
                    }
                />
            )}
        </div>
    )
}

export default WorkspacesPage