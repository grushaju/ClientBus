import {
    useEffect,
    useState,
} from 'react'

import {
    Link,
    useNavigate,
    useParams,
} from 'react-router-dom'

import {
    getAllWorkspaces,
    deleteWorkspace,
} from '../api/workspaceApi'

import {
    getWorkspaceEmployees,
} from '../api/employeeApi'

import {
    removeEmployeeWorkspace,
} from '../api/employeeWorkspaceApi'

import {
    getWorkspaceChannels,
} from '../api/channelApi'

import {
    getWorkspaceConversations,
} from '../api/conversationApi'

import type {
    EmployeeDto,
    WorkspaceDto,
} from '../auth/types'

import type {
    ChannelDto,
} from '../api/types/channel'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    useWorkspace,
} from '../workspace/WorkspaceContext'

import {
    EditWorkspaceDialog,
} from '../components/workspaces/EditWorkspaceDialog'

import {
    DeleteWorkspaceDialog,
} from '../components/workspaces/DeleteWorkspaceDialog'

import {
    WorkspaceEmployeesDialog,
} from '../components/workspaces/WorkspaceEmployeesDialog'

import {
    getChannelStatusLabel,
    getChannelTypeLabel,
} from '../components/channels/channelLabels'

function getEmployeeName(
    employee: EmployeeDto,
): string {
    return (
        [
            employee.firstName,
            employee.lastName,
        ]
            .filter(Boolean)
            .join(' ') ||
        employee.username ||
        employee.email
    )
}

function formatDate(
    value: string | null,
): string {
    if (!value) {
        return 'Нет данных'
    }

    return new Intl.DateTimeFormat(
        'ru-RU',
        {
            dateStyle: 'medium',
            timeStyle: 'short',
        },
    ).format(new Date(value))
}

function WorkspaceDetailsPage() {
    const {
        workspaceId,
    } = useParams()

    const navigate = useNavigate()

    const {
        currentEmployee,
    } = useAuth()

    const {
        workspaces,
        removeWorkspace,
        setCurrentWorkspaceId,
    } = useWorkspace()

    const [workspace, setWorkspace] =
        useState<WorkspaceDto | null>(null)

    const [employees, setEmployees] =
        useState<EmployeeDto[]>([])

    const [channels, setChannels] =
        useState<ChannelDto[]>([])

    const [conversationCount, setConversationCount] =
        useState(0)

    const [unreadCount, setUnreadCount] =
        useState(0)

    const [lastActivity, setLastActivity] =
        useState<string | null>(null)

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState<string | null>(null)

    const [editOpen, setEditOpen] =
        useState(false)

    const [deleteOpen, setDeleteOpen] =
        useState(false)

    const [employeesOpen, setEmployeesOpen] =
        useState(false)

    async function loadData() {
        if (!workspaceId) {
            return
        }

        setLoading(true)
        setError(null)

        try {
            const allWorkspaces =
                await getAllWorkspaces()

            const current =
                allWorkspaces.find(
                    item =>
                        item.id ===
                        workspaceId,
                )

            if (!current) {
                setWorkspace(null)
                setError(
                    'Workspace не найден.',
                )
                return
            }

            setWorkspace(current)

            const [
                workspaceEmployees,
                workspaceChannels,
                conversations,
            ] = await Promise.all([
                getWorkspaceEmployees(
                    workspaceId,
                ),
                getWorkspaceChannels(
                    workspaceId,
                ),
                getWorkspaceConversations(
                    workspaceId,
                ),
            ])

            setEmployees(
                workspaceEmployees.filter(
                    employee =>
                        employee.role !==
                        'SUPER_ADMIN',
                ),
            )

            setChannels(
                workspaceChannels,
            )

            setConversationCount(
                conversations.length,
            )

            setUnreadCount(
                conversations.reduce(
                    (total, conversation) =>
                        total +
                        conversation.unreadCount,
                    0,
                ),
            )

            const latestActivity =
                conversations.reduce<
                    string | null
                >(
                    (latest, conversation) => {
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

            setLastActivity(
                latestActivity,
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить Workspace',
            )
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadData()
    }, [workspaceId])

    function handleWorkspaceUpdated(
        updated: WorkspaceDto,
    ) {
        setWorkspace(updated)
    }

    async function handleDelete() {
        if (!workspace) {
            return
        }

        await deleteWorkspace(
            workspace.id,
        )

        removeWorkspace(
            workspace.id,
        )

        navigate(
            '/workspaces',
            { replace: true },
        )
    }

    async function handleRemoveEmployee(
        employee: EmployeeDto,
    ) {
        if (!workspace) {
            return
        }

        try {
            await removeEmployeeWorkspace(
                employee.id,
                workspace.id,
            )

            setEmployees(current =>
                current.filter(
                    item =>
                        item.id !==
                        employee.id,
                ),
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить сотрудника',
            )
        }
    }

    function openChannels() {
        if (!workspace) {
            return
        }

        setCurrentWorkspaceId(
            workspace.id,
        )

        navigate('/channels')
    }

    if (loading) {
        return (
            <div className="workspace-details-page">
                <div className="workspace-loading">
                    Загрузка Workspace…
                </div>
            </div>
        )
    }

    if (!workspace) {
        return (
            <div className="workspace-details-page">
                <Link
                    to="/workspaces"
                    className="workspace-back-link"
                >
                    ← Рабочие пространства
                </Link>

                <div className="workspace-form-error">
                    {error ||
                        'Workspace не найден.'}
                </div>
            </div>
        )
    }

    return (
        <div className="workspace-details-page">
            <div className="workspace-details-header">
                <div>
                    <Link
                        to="/workspaces"
                        className="workspace-back-link"
                    >
                        ← Рабочие пространства
                    </Link>

                    <h1>
                        {workspace.name}
                    </h1>

                    <p>
                        Рабочее пространство
                    </p>
                </div>

                <div className="workspace-details-actions">
                    <button
                        type="button"
                        className="workspace-button workspace-button-secondary"
                        onClick={() =>
                            setEditOpen(true)
                        }
                    >
                        Изменить
                    </button>

                    <button
                        type="button"
                        className="workspace-button workspace-button-danger"
                        onClick={() =>
                            setDeleteOpen(true)
                        }
                    >
                        Удалить
                    </button>
                </div>
            </div>

            {error && (
                <div className="workspace-form-error">
                    {error}
                </div>
            )}

            <div className="workspace-detail-stats">
                <div className="workspace-detail-stat">
                    <span>Сотрудники</span>
                    <strong>
                        {employees.length}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>Каналы</span>
                    <strong>
                        {channels.length}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>Диалоги</span>
                    <strong>
                        {conversationCount}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>Непрочитано</span>
                    <strong>
                        {unreadCount}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>
                        Последняя активность
                    </span>
                    <strong>
                        {formatDate(
                            lastActivity,
                        )}
                    </strong>
                </div>
            </div>

            <section className="workspace-detail-section">
                <div className="workspace-detail-section-header">
                    <div>
                        <h2>
                            Сотрудники
                        </h2>

                        <p>
                            Сотрудники, которым
                            доступен этот Workspace.
                        </p>
                    </div>

                    <button
                        type="button"
                        className="workspace-button workspace-button-primary"
                        onClick={() =>
                            setEmployeesOpen(
                                true,
                            )
                        }
                    >
                        Добавить сотрудников
                    </button>
                </div>

                {employees.length === 0 ? (
                    <div className="workspace-section-empty">
                        В Workspace пока нет
                        сотрудников.
                    </div>
                ) : (
                    <div className="workspace-employee-list">
                        {employees.map(
                            employee => (
                                <div
                                    key={
                                        employee.id
                                    }
                                    className="workspace-employee-row"
                                >
                                    <div>
                                        <strong>
                                            {getEmployeeName(
                                                employee,
                                            )}
                                        </strong>

                                        <span>
                                            {
                                                employee.email
                                            }
                                        </span>
                                    </div>

                                    <button
                                        type="button"
                                        className="workspace-button workspace-button-danger"
                                        onClick={() =>
                                            handleRemoveEmployee(
                                                employee,
                                            )
                                        }
                                    >
                                        Удалить
                                    </button>
                                </div>
                            ),
                        )}
                    </div>
                )}
            </section>

            <section className="workspace-detail-section">
                <div className="workspace-detail-section-header">
                    <div>
                        <h2>
                            Каналы
                        </h2>

                        <p>
                            Каналы, подключённые к
                            этому Workspace.
                        </p>
                    </div>

                    <button
                        type="button"
                        className="workspace-button workspace-button-secondary"
                        onClick={openChannels}
                    >
                        Управлять каналами
                    </button>
                </div>

                {channels.length === 0 ? (
                    <div className="workspace-section-empty">
                        Каналов пока нет.
                    </div>
                ) : (
                    <div className="workspace-channel-list">
                        {channels.map(
                            channel => (
                                <div
                                    key={
                                        channel.id
                                    }
                                    className="workspace-channel-row"
                                >
                                    <div>
                                        <strong>
                                            {
                                                channel.name
                                            }
                                        </strong>

                                        <span>
                                            {
                                                getChannelTypeLabel(
                                                    channel.type,
                                                )
                                            }
                                        </span>
                                    </div>

                                    <span className="workspace-channel-status">
                                        {
                                            getChannelStatusLabel(
                                                channel.status,
                                            )
                                        }
                                    </span>
                                </div>
                            ),
                        )}
                    </div>
                )}
            </section>

            <EditWorkspaceDialog
                open={editOpen}
                workspace={workspace}
                onClose={() =>
                    setEditOpen(false)
                }
                onUpdated={
                    handleWorkspaceUpdated
                }
            />

            <DeleteWorkspaceDialog
                open={deleteOpen}
                workspace={workspace}
                onClose={() =>
                    setDeleteOpen(false)
                }
                onConfirm={handleDelete}
            />

            <WorkspaceEmployeesDialog
                open={employeesOpen}
                workspaceId={
                    workspace.id
                }
                organizationId={
                    currentEmployee!
                        .organizationId
                }
                onClose={() =>
                    setEmployeesOpen(false)
                }
                onUpdated={loadData}
            />
        </div>
    )
}

export default WorkspaceDetailsPage