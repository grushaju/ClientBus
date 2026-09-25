import {
    useEffect,
    useMemo,
    useState,
} from 'react'

import {
    getWorkspaceEmployees,
} from '../../api/employeeApi'

import {
    assignEmployeeWorkspace,
} from '../../api/employeeWorkspaceApi'

import {
    getEmployeesByOrganization,
} from '../../api/employeeApi'

import type {
    EmployeeDto,
} from '../../auth/types'

interface WorkspaceEmployeesDialogProps {
    open: boolean
    workspaceId: string
    organizationId: string
    onClose: () => void
    onUpdated: () => void
}

function getEmployeeName(
    employee: EmployeeDto,
): string {
    const fullName = [
        employee.firstName,
        employee.lastName,
    ]
        .filter(Boolean)
        .join(' ')

    return (
        fullName ||
        employee.username ||
        employee.email
    )
}

export function WorkspaceEmployeesDialog({
                                             open,
                                             workspaceId,
                                             organizationId,
                                             onClose,
                                             onUpdated,
                                         }: WorkspaceEmployeesDialogProps) {
    const [employees, setEmployees] =
        useState<EmployeeDto[]>([])

    const [assignedEmployees, setAssignedEmployees] =
        useState<EmployeeDto[]>([])

    const [selectedIds, setSelectedIds] =
        useState<string[]>([])

    const [search, setSearch] =
        useState('')

    const [loading, setLoading] =
        useState(false)

    const [saving, setSaving] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (!open) {
            return
        }

        setSearch('')
        setSelectedIds([])
        setError(null)

        setLoading(true)

        Promise.all([
            getEmployeesByOrganization(
                organizationId,
            ),
            getWorkspaceEmployees(
                workspaceId,
            ),
        ])
            .then(([allEmployees, workspaceEmployees]) => {
                const availableEmployees =
                    allEmployees.filter(
                        employee =>
                            employee.role !==
                            'SUPER_ADMIN',
                    )

                const assignedIds =
                    new Set(
                        workspaceEmployees.map(
                            employee =>
                                employee.id,
                        ),
                    )

                setEmployees(
                    availableEmployees,
                )

                setAssignedEmployees(
                    availableEmployees.filter(
                        employee =>
                            assignedIds.has(
                                employee.id,
                            ),
                    ),
                )
            })
            .catch((err) => {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось загрузить сотрудников',
                )
            })
            .finally(() => {
                setLoading(false)
            })
    }, [
        open,
        organizationId,
        workspaceId,
    ])

    const assignedIds = useMemo(
        () =>
            new Set(
                assignedEmployees.map(
                    employee =>
                        employee.id,
                ),
            ),
        [assignedEmployees],
    )

    const availableEmployees =
        useMemo(() => {
            const normalizedSearch =
                search
                    .trim()
                    .toLocaleLowerCase('ru')

            return employees
                .filter(
                    employee =>
                        !assignedIds.has(
                            employee.id,
                        ),
                )
                .filter((employee) => {
                    if (
                        !normalizedSearch
                    ) {
                        return true
                    }

                    return getEmployeeName(
                        employee,
                    )
                        .toLocaleLowerCase('ru')
                        .includes(
                            normalizedSearch,
                        )
                })
        }, [
            employees,
            assignedIds,
            search,
        ])

    function toggleEmployee(
        employeeId: string,
    ) {
        setSelectedIds(current =>
            current.includes(employeeId)
                ? current.filter(
                    id =>
                        id !==
                        employeeId,
                )
                : [
                    ...current,
                    employeeId,
                ],
        )
    }

    async function handleSubmit() {
        if (
            selectedIds.length === 0
        ) {
            onClose()
            return
        }

        setSaving(true)
        setError(null)

        try {
            await Promise.all(
                selectedIds.map(
                    employeeId =>
                        assignEmployeeWorkspace(
                            employeeId,
                            workspaceId,
                        ),
                ),
            )

            onUpdated()
            onClose()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось добавить сотрудников',
            )
        } finally {
            setSaving(false)
        }
    }

    if (!open) {
        return null
    }

    return (
        <div
            className="workspace-modal-overlay"
            onMouseDown={(event) => {
                if (
                    event.target ===
                    event.currentTarget &&
                    !saving
                ) {
                    onClose()
                }
            }}
        >
            <div className="workspace-modal">
                <div className="workspace-modal-header">
                    <div>
                        <h2>
                            Добавить сотрудников
                        </h2>

                        <div className="workspace-modal-subtitle">
                            Выберите сотрудников,
                            которые должны иметь
                            доступ к Workspace.
                        </div>
                    </div>

                    <button
                        type="button"
                        className="workspace-modal-close"
                        onClick={onClose}
                        disabled={saving}
                    >
                        ×
                    </button>
                </div>

                <div className="workspace-form">
                    {error && (
                        <div className="workspace-form-error">
                            {error}
                        </div>
                    )}

                    <input
                        type="search"
                        className="ui-input"
                        placeholder="Поиск сотрудника"
                        value={search}
                        onChange={event =>
                            setSearch(
                                event.target.value,
                            )
                        }
                        disabled={
                            loading ||
                            saving
                        }
                    />

                    {loading ? (
                        <div className="workspace-dialog-loading">
                            Загрузка сотрудников…
                        </div>
                    ) : availableEmployees.length ===
                    0 ? (
                        <div className="workspace-dialog-empty">
                            Нет доступных сотрудников.
                        </div>
                    ) : (
                        <div className="workspace-employee-picker">
                            {availableEmployees.map(
                                employee => {
                                    const selected =
                                        selectedIds.includes(
                                            employee.id,
                                        )

                                    return (
                                        <label
                                            key={
                                                employee.id
                                            }
                                            className={`workspace-employee-picker-item ${
                                                selected
                                                    ? 'selected'
                                                    : ''
                                            }`}
                                        >
                                            <input
                                                type="checkbox"
                                                checked={
                                                    selected
                                                }
                                                onChange={() =>
                                                    toggleEmployee(
                                                        employee.id,
                                                    )
                                                }
                                                disabled={
                                                    saving
                                                }
                                            />

                                            <span>
                                                <strong>
                                                    {getEmployeeName(
                                                        employee,
                                                    )}
                                                </strong>

                                                <small>
                                                    {
                                                        employee.email
                                                    }
                                                </small>
                                            </span>
                                        </label>
                                    )
                                },
                            )}
                        </div>
                    )}
                </div>

                <div className="workspace-modal-actions">
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
                        onClick={handleSubmit}
                        disabled={
                            saving ||
                            selectedIds.length ===
                            0
                        }
                    >
                        {saving
                            ? 'Добавление…'
                            : `Добавить${
                                selectedIds.length
                                    ? ` (${selectedIds.length})`
                                    : ''
                            }`}
                    </button>
                </div>
            </div>
        </div>
    )
}