import {
    useEffect,
    useState,
} from 'react'

import {
    assignEmployeeWorkspace,
    getEmployeeWorkspaces,
    removeEmployeeWorkspace,
    setEmployeeWorkspaceSupervisor,
} from '../../api/employeeWorkspaceApi'

import {
    setEmployeeEnabled,
    updateEmployee,
    updateEmployeeCredentials,
} from '../../api/employeeApi'

import type {
    EmployeeDto,
    EmployeeWorkspaceDto,
    WorkspaceDto,
} from '../../auth/types'

import EmployeeAvatar
    from './EmployeeAvatar'

interface EmployeeDetailsDialogProps {
    employee: EmployeeDto
    workspaceIds: string[]
    workspaces: WorkspaceDto[]
    onClose: () => void
    onUpdated: (
        employee: EmployeeDto,
        workspaceIds: string[],
    ) => void
}

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

export default function EmployeeDetailsDialog({
                                                  employee,
                                                  workspaceIds,
                                                  workspaces,
                                                  onClose,
                                                  onUpdated,
                                              }: EmployeeDetailsDialogProps) {
    const [
        firstName,
        setFirstName,
    ] = useState(
        employee.firstName ?? '',
    )

    const [
        lastName,
        setLastName,
    ] = useState(
        employee.lastName ?? '',
    )

    const [
        username,
        setUsername,
    ] = useState(
        employee.username ?? '',
    )

    const [
        email,
        setEmail,
    ] = useState(
        employee.email ?? '',
    )

    const [
        phone,
        setPhone,
    ] = useState(
        employee.phone ?? '',
    )

    const [
        selectedWorkspaceIds,
        setSelectedWorkspaceIds,
    ] = useState<string[]>(
        workspaceIds,
    )
    const [memberships, setMemberships] = useState<EmployeeWorkspaceDto[]>([])
    const [supervisorIds, setSupervisorIds] = useState<string[]>([])
    const [loadingMemberships, setLoadingMemberships] = useState(true)
    const [membershipError, setMembershipError] = useState<string | null>(null)
    const [membershipReload, setMembershipReload] = useState(0)

    useEffect(() => {
        let cancelled = false
        setLoadingMemberships(true)
        setMembershipError(null)
        void getEmployeeWorkspaces(employee.id).then(items => {
            if (cancelled) return
            setMemberships(items)
            setSelectedWorkspaceIds(items.map(item => item.workspaceId))
            setSupervisorIds(items.filter(item => item.isSupervisor).map(item => item.workspaceId))
        }).catch(err => {
            if (!cancelled) setMembershipError(err instanceof Error ? err.message : 'Не удалось загрузить роли в рабочих пространствах')
        }).finally(() => { if (!cancelled) setLoadingMemberships(false) })
        return () => { cancelled = true }
    }, [employee.id, membershipReload])

    const [
        enabled,
        setEnabled,
    ] = useState(
        employee.enabled,
    )

    const [
        saving,
        setSaving,
    ] = useState(false)

    const [
        error,
        setError,
    ] = useState<string | null>(null)

    useEffect(() => {
        setFirstName(
            employee.firstName ?? '',
        )

        setLastName(
            employee.lastName ?? '',
        )

        setUsername(
            employee.username ?? '',
        )

        setEmail(
            employee.email ?? '',
        )

        setPhone(
            employee.phone ?? '',
        )

        setSelectedWorkspaceIds(
            workspaceIds,
        )

        setEnabled(
            employee.enabled,
        )

        setError(null)
    }, [
        employee.id,
        employee.firstName,
        employee.lastName,
        employee.username,
        employee.email,
        employee.phone,
        employee.enabled,
        workspaceIds,
    ])

    function toggleWorkspace(
        workspaceId: string,
    ) {
        setSelectedWorkspaceIds(
            current =>
                current.includes(workspaceId)
                    ? current.filter(
                        id =>
                            id !==
                            workspaceId,
                    )
                    : [
                        ...current,
                        workspaceId,
                    ],
        )
    }

    async function handleSave() {
        if (saving || loadingMemberships || membershipError) return
        setSaving(true)
        setError(null)

        try {
            let updatedEmployee =
                employee

            const profileChanged =
                firstName !==
                (employee.firstName ?? '') ||
                lastName !==
                (employee.lastName ?? '') ||
                phone !==
                (employee.phone ?? '')

            if (profileChanged) {
                updatedEmployee =
                    await updateEmployee(
                        employee.id,
                        {
                            firstName:
                                firstName.trim() ||
                                null,
                            lastName:
                                lastName.trim() ||
                                null,
                            phone:
                                phone.trim() ||
                                null,
                        },
                    )
            }

            const credentialsChanged =
                username !==
                (employee.username ?? '') ||
                email !==
                (employee.email ?? '')

            if (credentialsChanged) {
                updatedEmployee =
                    await updateEmployeeCredentials(
                        employee.id,
                        {
                            username:
                                username.trim() ||
                                null,
                            email:
                                email.trim() ||
                                null,
                        },
                    )
            }

            if (
                enabled !==
                employee.enabled
            ) {
                updatedEmployee =
                    await setEmployeeEnabled(
                        employee.id,
                        enabled,
                    )
            }

            const originalIds =
                new Set(memberships.map(item => item.workspaceId))

            const nextIds =
                new Set(
                    selectedWorkspaceIds,
                )

            const workspaceChanges =
                workspaces.filter(
                    workspace =>
                        originalIds.has(
                            workspace.id,
                        ) !==
                        nextIds.has(
                            workspace.id,
                        ) || (nextIds.has(workspace.id) &&
                            !!memberships.find(item => item.workspaceId === workspace.id)?.isSupervisor !== supervisorIds.includes(workspace.id)),
                )

            for (const workspace of
                workspaceChanges) {
                if (
                    nextIds.has(
                        workspace.id,
                    )
                ) {
                    const isSupervisor = supervisorIds.includes(workspace.id)
                    const saved = originalIds.has(workspace.id)
                        ? await setEmployeeWorkspaceSupervisor(employee.id, workspace.id, isSupervisor)
                        : await assignEmployeeWorkspace(employee.id, workspace.id, isSupervisor)
                    setMemberships(current => [...current.filter(item => item.workspaceId !== workspace.id), saved])
                } else {
                    await removeEmployeeWorkspace(
                        employee.id,
                        workspace.id,
                    )
                    setMemberships(current => current.filter(item => item.workspaceId !== workspace.id))
                }
            }

            onUpdated(
                updatedEmployee,
                selectedWorkspaceIds,
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось сохранить изменения',
            )
        } finally {
            setSaving(false)
        }
    }

    return (
        <div
            className="employee-modal-overlay"
            onMouseDown={event => {
                if (
                    event.target ===
                    event.currentTarget
                ) {
                    onClose()
                }
            }}
        >
            <div className="employee-modal">
                <div className="employee-modal-header">
                    <div className="employee-modal-identity">
                        <EmployeeAvatar
                            employeeId={employee.id}
                            avatarUrl={employee.avatarUrl ?? null}
                            firstName={employee.firstName}
                            lastName={employee.lastName}
                            username={employee.username}
                            size={52}
                        />

                        <div className="employee-modal-identity-info">
                            <h2>{getEmployeeName(employee)}</h2>
                            <p>{employee.email}</p>
                        </div>
                    </div>

                    <button
                        type="button"
                        className="ui-button ui-button-ghost ui-button-sm employee-modal-close"
                        onClick={onClose}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <div className="employee-modal-content">
                    <div className="employee-details-section">
                        <div className="employee-details-section-title">
                            Данные сотрудника
                        </div>

                        <div className="employee-details-fields">
                        <label className="employee-details-field">
                                <span>
                                    Имя
                                </span>

                                <input
                                    className="ui-input"
                                    type="text"
                                    value={firstName}
                                    onChange={event =>
                                        setFirstName(
                                            event.target.value,
                                        )
                                    }
                                    disabled={saving}
                                />
                            </label>

                            <label className="employee-details-field">
                                <span>
                                    Фамилия
                                </span>

                                <input
                                    className="ui-input"
                                    type="text"
                                    value={lastName}
                                    onChange={event =>
                                        setLastName(
                                            event.target.value,
                                        )
                                    }
                                    disabled={saving}
                                />
                            </label>

                            <label className="employee-details-field employee-details-field-full">
                                <span>
                                    Username
                                </span>

                                <input
                                    className="ui-input"
                                    type="text"
                                    value={username}
                                    onChange={event =>
                                        setUsername(
                                            event.target.value,
                                        )
                                    }
                                    disabled={saving}
                                    autoComplete="username"
                                />
                            </label>

                            <label className="employee-details-field employee-details-field-full">
                                <span>
                                    Email
                                </span>

                                <input
                                    className="ui-input"
                                    type="email"
                                    value={email}
                                    onChange={event =>
                                        setEmail(
                                            event.target.value,
                                        )
                                    }
                                    disabled={saving}
                                    autoComplete="email"
                                />
                            </label>

                            <label className="employee-details-field employee-details-field-full">
                                <span>
                                    Телефон
                                </span>

                                <input
                                    className="ui-input"
                                    type="tel"
                                    value={phone}
                                    onChange={event =>
                                        setPhone(
                                            event.target.value,
                                        )
                                    }
                                    disabled={saving}
                                    autoComplete="tel"
                                />
                            </label>
                        </div>
                    </div>

                    <div className="employee-details-divider" />

                    <div className="employee-details-section">
                        <div className="employee-details-section-title">
                            Доступ
                        </div>

                        <button
                            type="button"
                            className={`employee-access-toggle ${
                                enabled
                                    ? 'enabled'
                                    : 'disabled'
                            }`}
                            onClick={() =>
                                setEnabled(
                                    current =>
                                        !current,
                                )
                            }
                            disabled={saving}
                        >
                            <span className="employee-access-toggle-indicator" />

                            <span>
                                {enabled
                                    ? 'Сотрудник доступен'
                                    : 'Сотрудник недоступен'}
                            </span>
                        </button>
                    </div>

                    <div className="employee-details-divider" />

                    <div className="employee-details-section">
                        <div className="employee-details-section-title">
                            Рабочие пространства
                        </div>

                        {workspaces.length ===
                        0 ? (
                            <div className="employee-no-workspaces">
                                Рабочих пространств
                                пока нет.
                            </div>
                        ) : (
                            <div className="employee-details-workspaces">
                                {workspaces.map(
                                    workspace => {
                                        const assigned =
                                            selectedWorkspaceIds.includes(
                                                workspace.id,
                                            )

                                        return (
                                            <div
                                                key={
                                                    workspace.id
                                                }
                                                className={`employee-details-workspace ${
                                                    assigned
                                                        ? 'assigned'
                                                        : ''
                                                }`}
                                            >
                                                <label className="employee-details-workspace-membership"><input
                                                    type="checkbox"
                                                    checked={
                                                        assigned
                                                    }
                                                    onChange={() =>
                                                        toggleWorkspace(
                                                            workspace.id,
                                                        )
                                                    }
                                                    disabled={
                                                        saving || loadingMemberships || !!membershipError
                                                    }
                                                />

                                                <span>
                                                    {
                                                        workspace.name
                                                    }
                                                </span></label>
                                                <label className="employee-details-supervisor">
                                                    <input type="checkbox" checked={assigned && supervisorIds.includes(workspace.id)}
                                                        disabled={saving || loadingMemberships || !!membershipError || !assigned}
                                                        aria-label={`Супервизор: ${workspace.name}`}
                                                        onChange={event => setSupervisorIds(current => event.target.checked
                                                            ? [...current, workspace.id] : current.filter(id => id !== workspace.id))} />
                                                    <span>Супервизор</span>
                                                </label>
                                            </div>
                                        )
                                    },
                                )}
                            </div>
                        )}
                        {loadingMemberships && <div className="employee-no-workspaces">Загрузка ролей…</div>}
                        {membershipError && <div className="employee-details-error" role="alert">{membershipError}
                            <button type="button" className="ui-button ui-button-secondary ui-button-sm" onClick={() => setMembershipReload(current => current + 1)}>Повторить</button>
                        </div>}
                    </div>

                    {error && (
                        <div className="employee-details-error">
                            {error}
                        </div>
                    )}
                </div>

                <div className="employee-modal-actions">
                    <button
                        type="button"
                        className="ui-button ui-button-secondary"
                        onClick={onClose}
                        disabled={saving}
                    >
                        Отмена
                    </button>

                    <button
                        type="button"
                        className="ui-button ui-button-primary"
                        onClick={
                            handleSave
                        }
                        disabled={saving || loadingMemberships || !!membershipError}
                    >
                        {saving
                            ? 'Сохранение…'
                            : 'Сохранить'}
                    </button>
                </div>
            </div>
        </div>
    )
}
