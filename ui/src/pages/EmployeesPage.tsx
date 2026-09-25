import {
    useEffect,
    useMemo,
    useState,
} from 'react'

import {
    getEmployeesByOrganization,
    getEmployeeWorkspaces,
} from '../api/employeeApi'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    useWorkspace,
} from '../workspace/WorkspaceContext'

import type {
    EmployeeDto,
} from '../auth/types'

import EmployeeCreateDialog
    from '../components/employees/EmployeeCreateDialog'

import EmployeeDetailsDialog
    from '../components/employees/EmployeeDetailsDialog'

interface EmployeeRow {
    employee: EmployeeDto
    workspaceIds: string[]
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

function EmployeesPage() {
    const {
        currentEmployee,
        isSuperAdmin,
    } = useAuth()

    const {
        workspaces,
    } = useWorkspace()

    const [
        employees,
        setEmployees,
    ] = useState<EmployeeRow[]>([])

    const [
        loading,
        setLoading,
    ] = useState(true)

    const [
        error,
        setError,
    ] = useState<string | null>(null)

    const [
        search,
        setSearch,
    ] = useState('')

    const [
        workspaceFilter,
        setWorkspaceFilter,
    ] = useState<string>('ALL')

    const [
        selectedEmployee,
        setSelectedEmployee,
    ] = useState<EmployeeRow | null>(null)

    const [
        createOpen,
        setCreateOpen,
    ] = useState(false)

    async function loadEmployees() {
        if (!currentEmployee) {
            return
        }

        setLoading(true)
        setError(null)

        try {
            const allEmployees =
                await getEmployeesByOrganization(
                    currentEmployee.organizationId,
                )

            const employeeList =
                allEmployees.filter(
                    employee =>
                        employee.role !==
                        'SUPER_ADMIN',
                )

            const rows =
                await Promise.all(
                    employeeList.map(
                        async employee => {
                            const assignments =
                                await getEmployeeWorkspaces(
                                    employee.id,
                                )

                            return {
                                employee,
                                workspaceIds:
                                    assignments.map(
                                        assignment =>
                                            assignment.workspaceId,
                                    ),
                            }
                        },
                    ),
                )

            setEmployees(rows)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить сотрудников',
            )
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadEmployees()
    }, [
        currentEmployee?.organizationId,
    ])

    const filteredEmployees =
        useMemo(() => {
            const normalizedSearch =
                search
                    .trim()
                    .toLowerCase()

            return employees.filter(
                row => {
                    const employeeName =
                        getEmployeeName(
                            row.employee,
                        ).toLowerCase()

                    const email =
                        row.employee.email
                            .toLowerCase()

                    const username =
                        (
                            row.employee.username ??
                            ''
                        ).toLowerCase()

                    const matchesSearch =
                        !normalizedSearch ||
                        employeeName.includes(
                            normalizedSearch,
                        ) ||
                        email.includes(
                            normalizedSearch,
                        ) ||
                        username.includes(
                            normalizedSearch,
                        )

                    const matchesWorkspace =
                        workspaceFilter ===
                        'ALL' ||
                        row.workspaceIds.includes(
                            workspaceFilter,
                        )

                    return (
                        matchesSearch &&
                        matchesWorkspace
                    )
                },
            )
        }, [
            employees,
            search,
            workspaceFilter,
        ])

    function handleEmployeeUpdated(
        updatedEmployee: EmployeeDto,
        workspaceIds: string[],
    ) {
        setEmployees(current =>
            current.map(row =>
                row.employee.id !==
                updatedEmployee.id
                    ? row
                    : {
                        employee:
                        updatedEmployee,
                        workspaceIds,
                    },
            ),
        )

        setSelectedEmployee(null)
    }

    function handleEmployeeCreated(
        employee: EmployeeDto,
    ) {
        setEmployees(current => [
            ...current,
            {
                employee,
                workspaceIds: [],
            },
        ])

        setCreateOpen(false)
    }

    const activeCount =
        employees.filter(
            row => row.employee.enabled,
        ).length

    return (
        <div className="employees-page">
            <div className="employees-header">
                <div>
                    <h1>
                        Сотрудники
                    </h1>

                    <p>
                        Управление сотрудниками
                    </p>
                </div>

                {isSuperAdmin && (
                    <button
                        type="button"
                        className="employee-button employee-button-primary"
                        onClick={() =>
                            setCreateOpen(true)
                        }
                    >
                        + Добавить
                    </button>
                )}
            </div>

            <div className="employees-toolbar">
                <div className="employees-search">
                    <input
                        type="text"
                        value={search}
                        onChange={event =>
                            setSearch(
                                event.target.value,
                            )
                        }
                        placeholder="Поиск сотрудника..."
                        aria-label="Поиск сотрудника"
                    />
                </div>

                <div className="employees-filter">
                    <select
                        value={workspaceFilter}
                        onChange={event =>
                            setWorkspaceFilter(
                                event.target.value,
                            )
                        }
                        aria-label="Фильтр по рабочему пространству"
                    >
                        <option value="ALL">
                            Все Пространства
                        </option>

                        {workspaces.map(
                            workspace => (
                                <option
                                    key={
                                        workspace.id
                                    }
                                    value={
                                        workspace.id
                                    }
                                >
                                    {
                                        workspace.name
                                    }
                                </option>
                            ),
                        )}
                    </select>
                </div>
            </div>

            {!loading && (
                <div className="employees-summary">
                    <span>
                        Всего: {employees.length}
                    </span>

                    <span>
                        Активных: {activeCount}
                    </span>

                    {filteredEmployees.length !==
                        employees.length && (
                            <span>
                            Найдено:{' '}
                                {
                                    filteredEmployees.length
                                }
                        </span>
                        )}
                </div>
            )}

            {error && (
                <div className="employees-error">
                    {error}
                </div>
            )}

            {loading ? (
                <div className="employees-state">
                    Загрузка сотрудников…
                </div>
            ) : employees.length === 0 ? (
                <div className="employees-state">
                    Сотрудников пока нет.
                </div>
            ) : filteredEmployees.length ===
            0 ? (
                <div className="employees-state">
                    По заданным условиям сотрудники
                    не найдены.
                </div>
            ) : (
                <div className="employees-list">
                    {filteredEmployees.map(
                        row => (
                            <button
                                key={
                                    row.employee.id
                                }
                                type="button"
                                className="employee-card"
                                onClick={() =>
                                    setSelectedEmployee(
                                        row,
                                    )
                                }
                            >
                                <div className="employee-card-identity">
                                    <h2>
                                        {getEmployeeName(
                                            row.employee,
                                        )}
                                    </h2>

                                    <p>
                                        {
                                            row
                                                .employee
                                                .email
                                        }
                                    </p>
                                </div>

                                <span
                                    className={
                                        row
                                            .employee
                                            .enabled
                                            ? 'employee-status employee-status-enabled'
                                            : 'employee-status employee-status-disabled'
                                    }
                                >
                                    {row.employee
                                        .enabled
                                        ? 'Доступен'
                                        : 'Недоступен'}
                                </span>

                                <div className="employee-card-workspaces">
                                    <div className="employee-workspaces-title">
                                        Рабочие пространства
                                    </div>

                                    {row.workspaceIds
                                        .length ===
                                    0 ? (
                                        <div className="employee-no-workspaces">
                                            Пространства не
                                            назначены
                                        </div>
                                    ) : (
                                        <div className="employee-workspace-list">
                                            {row.workspaceIds.map(
                                                workspaceId => {
                                                    const workspace =
                                                        workspaces.find(
                                                            item =>
                                                                item.id ===
                                                                workspaceId,
                                                        )

                                                    if (
                                                        !workspace
                                                    ) {
                                                        return null
                                                    }

                                                    return (
                                                        <span
                                                            key={
                                                                workspace.id
                                                            }
                                                            className="employee-workspace-tag"
                                                        >
                                                            {
                                                                workspace.name
                                                            }
                                                        </span>
                                                    )
                                                },
                                            )}
                                        </div>
                                    )}
                                </div>
                            </button>
                        ),
                    )}
                </div>
            )}

            {selectedEmployee && (
                <EmployeeDetailsDialog
                    employee={
                        selectedEmployee.employee
                    }
                    workspaceIds={
                        selectedEmployee.workspaceIds
                    }
                    workspaces={workspaces}
                    onClose={() =>
                        setSelectedEmployee(
                            null,
                        )
                    }
                    onUpdated={
                        handleEmployeeUpdated
                    }
                />
            )}

            {createOpen && currentEmployee && (
                <EmployeeCreateDialog
                    organizationId={
                        currentEmployee.organizationId
                    }
                    onClose={() =>
                        setCreateOpen(false)
                    }
                    onCreated={
                        handleEmployeeCreated
                    }
                />
            )}
        </div>
    )
}

export default EmployeesPage