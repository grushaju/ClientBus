import { useUiResource } from '../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent } from '../api/events/uiScope'
import {
    useRef,
    useState,
} from 'react'

import {
    Link,
    useNavigate,
    useParams,
} from 'react-router-dom'

import {
    getWorkspace,
    deleteWorkspace,
} from '../api/workspaceApi'

import {
    getWorkspaceEmployees,
} from '../api/employeeApi'

import {
    removeEmployeeWorkspace,
    getWorkspaceMemberships,
} from '../api/employeeWorkspaceApi'

import {
    getWorkspaceChannels,
} from '../api/channelApi'

import {
    getWorkspaceConversations,
} from '../api/conversationApi'

import WorkspaceAvatar from '../components/workspaces/WorkspaceAvatar'

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

import EmployeeAvatar
    from '../components/employees/EmployeeAvatar'

import {
    ChannelAccountAvatar,
} from '../components/channels/ChannelAccountAvatar'
import PlatformIcon from "../components/common/platform/PlatformIcon";

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

function WorkspaceDetailsPageContent() {
    const hasLoaded = useRef(false)
    const {
        workspaceId,
    } = useParams()

    const navigate = useNavigate()

    const {
        currentEmployee,
    } = useAuth()

    const {
        removeWorkspace,
        setCurrentWorkspaceId,
    } = useWorkspace()

    const [workspace, setWorkspace] =
        useState<WorkspaceDto | null>(null)

    const [employees, setEmployees] =
        useState<EmployeeDto[]>([])
    const [supervisorIds, setSupervisorIds] = useState<string[]>([])

    const [channels, setChannels] =
        useState<ChannelDto[]>([])

    const [
        conversationCount,
        setConversationCount,
    ] = useState(0)

    const [
        unreadCount,
        setUnreadCount,
    ] = useState(0)

    const [
        lastActivity,
        setLastActivity,
    ] = useState<string | null>(null)

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

    const editSnapshot = useRef(workspace)
    if (!editOpen) editSnapshot.current = workspace
    const resource = useUiResource('workspace-details:' + workspaceId, !!workspaceId,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) ||
            ['workspace.invalidated', 'employees.invalidated', 'channels.invalidated', 'inbox.invalidated', 'messages.invalidated'].includes(event.type)),
        async signal => {
            const [workspace, employees, channels, conversations, memberships] = await Promise.all([
                getWorkspace(workspaceId!, signal), getWorkspaceEmployees(workspaceId!, signal),
                getWorkspaceChannels(workspaceId!, signal), getWorkspaceConversations(workspaceId!, signal),
                getWorkspaceMemberships(workspaceId!, signal),
            ])
            const visibleEmployees = employees.filter(employee => employee.role !== 'SUPER_ADMIN')
            const supervisors = memberships.filter(item => item.isSupervisor).map(item => item.employeeId)
            return { workspace, employees: visibleEmployees, supervisors, channels, conversations }
        }, value => {
            hasLoaded.current = true
            setWorkspace(value.workspace); setEmployees(value.employees); setSupervisorIds(value.supervisors); setChannels(value.channels)
            setConversationCount(value.conversations.length)
            setUnreadCount(value.conversations.reduce((total, item) => total + item.unreadCount, 0))
            setLastActivity(value.conversations.reduce<string | null>((latest, item) => item.lastMessageAt &&
                (!latest || new Date(item.lastMessageAt) > new Date(latest)) ? item.lastMessageAt : latest, null))
            setError(null)
        }, error => setError(error instanceof Error ? error.message : 'Не удалось загрузить рабочее пространство'),
        () => { setWorkspace(null); setEmployees([]); setChannels([]); setEditOpen(false); setDeleteOpen(false); setEmployeesOpen(false) }, value => { if (!value || !hasLoaded.current) setLoading(value) })
    const loadData = resource.reload

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
            {
                replace: true,
            },
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
                    Загрузка рабочего
                    пространства…
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
                        'Рабочее пространство не найдено.'}
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

                    <div className="workspace-details-title-row">
                        <WorkspaceAvatar
                            workspaceId={workspace.id}
                            avatarUrl={workspace.avatarUrl}
                            name={workspace.name}
                            size={64}
                        />

                        <div>
                            <h1>{workspace.name}</h1>
                            <p>Рабочее пространство</p>
                        </div>
                    </div>
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
                    <span>
                        Сотрудники
                    </span>

                    <strong>
                        {employees.length}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>
                        Каналы
                    </span>

                    <strong>
                        {channels.length}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>
                        Диалоги
                    </span>

                    <strong>
                        {conversationCount}
                    </strong>
                </div>

                <div className="workspace-detail-stat">
                    <span>
                        Непрочитано
                    </span>

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
                            доступно это рабочее
                            пространство.
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
                        В рабочем пространстве пока
                        нет сотрудников.
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
                                    <div className="workspace-employee-identity">
                                        <EmployeeAvatar
                                            employeeId={employee.id}
                                            username={getEmployeeName(employee)}
                                            avatarUrl={employee.avatarUrl ?? null}
                                            size={40}
                                        />

                                        <div className="workspace-employee-info">
                                            <div className="workspace-employee-name">
                                            <strong>
                                                {getEmployeeName(employee)}
                                            </strong>
                                            {supervisorIds.includes(employee.id) && <span className="workspace-employee-supervisor">Супервизор</span>}
                                            </div>

                                            <span>{employee.email}</span>
                                        </div>
                                    </div>

                                    <button
                                        type="button"
                                        className="workspace-button workspace-button-danger"
                                        onClick={() => handleRemoveEmployee(employee)}
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
                            этому рабочему
                            пространству.
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
                                    <div className="workspace-channel-identity">
                                        <PlatformIcon
                                            type={channel.type}
                                            size={18}
                                        />

                                        <ChannelAccountAvatar
                                            channelId={channel.id}
                                            avatarUrl={channel.account?.avatarUrl ?? null}
                                            displayName={
                                                channel.account?.displayName || channel.name
                                            }
                                        />

                                        <div className="workspace-channel-info">
                                            <strong>{channel.name}</strong>

                                            <span>
                                                {getChannelTypeLabel(channel.type)}
                                            </span>
                                        </div>
                                    </div>

                                    <span
                                        className={
                                            channel.status === 'CONNECTED'
                                                ? 'workspace-channel-status workspace-channel-status-connected'
                                                : 'workspace-channel-status workspace-channel-status-disabled'
                                        }
                                    >
                                        {getChannelStatusLabel(channel.status)}
                                    </span>
                                </div>
                            ),
                        )}
                    </div>
                )}
            </section>

            <EditWorkspaceDialog
                open={editOpen}
                workspace={editSnapshot.current ?? workspace}
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
                onConfirm={
                    handleDelete
                }
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

function WorkspaceDetailsPage() {
    const route = useParams()
    const { currentEmployee, sessionEpoch } = useAuth()
    const { currentWorkspace } = useWorkspace()
    return <WorkspaceDetailsPageContent key={[sessionEpoch, currentEmployee?.id, currentEmployee?.role, currentWorkspace?.id, JSON.stringify(route)].join(':')} />
}

export default WorkspaceDetailsPage
