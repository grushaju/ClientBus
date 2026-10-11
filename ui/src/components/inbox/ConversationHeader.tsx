import {
    useEffect,
    useRef,
    useState,
} from 'react'

import type { EmployeeDto } from '../../auth/types'
import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'

import ClientAccountAvatar from '../clients/ClientAccountAvatar'
import PlatformIcon from '../common/platform/PlatformIcon'
import EmployeeAvatar from '../employees/EmployeeAvatar'

interface ConversationHeaderProps {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    channel: ChannelSummary | null
    employees: EmployeeDto[]
    currentEmployeeId: string | null
    isSuperAdmin: boolean
    isEmployee: boolean
    actionError: string | null
    onTake: () => Promise<void>
    onRelease: () => Promise<void>
    onAssign: (employeeId: string) => Promise<void>
    onUnassign: () => Promise<void>
}

function ConversationHeader({
                                conversation,
                                clientAccount,
                                channel,
                                employees,
                                currentEmployeeId,
                                isSuperAdmin,
                                isEmployee,
                                actionError,
                                onTake,
                                onRelease,
                                onAssign,
                                onUnassign,
                            }: ConversationHeaderProps) {
    const [isUpdating, setIsUpdating] =
        useState(false)

    const [isAssignmentOpen, setIsAssignmentOpen] =
        useState(false)

    const assignmentRef = useRef<HTMLDivElement>(null)

    useEffect(() => {
        if (!isAssignmentOpen) {
            return
        }

        function handleOutsideClick(event: MouseEvent) {
            if (
                assignmentRef.current &&
                !assignmentRef.current.contains(event.target as Node)
            ) {
                setIsAssignmentOpen(false)
            }
        }

        document.addEventListener('mousedown', handleOutsideClick)

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick)
        }
    }, [isAssignmentOpen])

    const clientName =
        clientAccount?.displayName ||
        clientAccount?.username ||
        clientAccount?.phone ||
        clientAccount?.externalId ||
        'Неизвестный клиент'

    const platformType =
        clientAccount?.channelType ||
        channel?.type ||
        'UNKNOWN'

    const platformName =
        clientAccount?.channelType ||
        channel?.type ||
        'Неизвестная платформа'

    const channelName =
        channel?.name ||
        'Канал не определён'

    const isAssignedToCurrentEmployee =
        isEmployee &&
        conversation.assignedEmployeeId ===
        currentEmployeeId

    const assignedEmployee =
        employees.find(
            employee =>
                employee.id ===
                conversation.assignedEmployeeId,
        ) ?? null

    const assignedEmployeeName =
        assignedEmployee
            ? `${assignedEmployee.firstName} ${assignedEmployee.lastName}`.trim() ||
            assignedEmployee.username
            : null

    async function handleTake(): Promise<void> {
        setIsUpdating(true)

        try {
            await onTake()
        } finally {
            setIsUpdating(false)
        }
    }

    async function handleRelease(): Promise<void> {
        setIsUpdating(true)

        try {
            await onRelease()
        } finally {
            setIsUpdating(false)
        }
    }

    async function handleAssignmentChange(
        value: string,
    ): Promise<void> {
        setIsUpdating(true)

        try {
            if (value === '') {
                await onUnassign()
            } else {
                await onAssign(value)
            }
        } finally {
            setIsUpdating(false)
        }
    }

    return (
        <header className="conversation-header">
            <div className="conversation-header-main">
                <div className="conversation-header-client">
                    <ClientAccountAvatar
                        accountId={clientAccount?.id ?? ''}
                        avatarUrl={clientAccount?.avatarUrl ?? null}
                        displayName={clientName}
                        size={40}
                    />

                    <div className="conversation-header-info">
                        <h2 className="conversation-header-client-name">
                            {clientName}
                        </h2>

                        <div className="conversation-header-meta">
                            <span className="conversation-header-platform">
                                <PlatformIcon
                                    type={platformType}
                                    size={18}
                                />

                                <span>
                                    {platformName}
                                </span>
                            </span>

                            <span className="conversation-header-separator">
                                ·
                            </span>

                            <span className="conversation-header-channel">
                                {channelName}
                            </span>

                            {clientAccount?.username && (
                                <>
                                    <span className="conversation-header-separator">
                                        ·
                                    </span>

                                    <span>
                                        @{clientAccount.username}
                                    </span>
                                </>
                            )}
                        </div>
                    </div>
                </div>

                <div className="conversation-header-actions">
                    {isEmployee &&
                        conversation.assignedEmployeeId === null && (
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary conversation-action-button"
                                onClick={handleTake}
                                disabled={isUpdating}
                            >
                                {isUpdating
                                    ? 'Выполняется…'
                                    : 'Взять'}
                            </button>
                        )}

                    {isEmployee &&
                        isAssignedToCurrentEmployee && (
                            <button
                                type="button"
                                className="ui-button ui-button-sm ui-button-secondary conversation-action-button"
                                onClick={handleRelease}
                                disabled={isUpdating}
                            >
                                {isUpdating
                                    ? 'Выполняется…'
                                    : 'Освободить'}
                            </button>
                        )}

                    {isSuperAdmin && (
                        <div className="conversation-assignment">
                            <label
                                htmlFor="conversation-assignment"
                                className="conversation-assignment-label"
                            >
                                Ответственный
                            </label>

                            <div
                                className="employee-assignment-picker"
                                ref={assignmentRef}
                            >
                                <button
                                    type="button"
                                    className="employee-assignment-trigger"
                                    aria-haspopup="listbox"
                                    aria-expanded={isAssignmentOpen}
                                    onClick={() =>
                                        setIsAssignmentOpen(open => !open)
                                    }
                                    disabled={isUpdating}
                                >
                                    {assignedEmployee ? (
                                        <>
                                            <EmployeeAvatar
                                                employeeId={assignedEmployee.id}
                                                avatarUrl={assignedEmployee.avatarUrl ?? null}
                                                username={assignedEmployeeName ?? 'Сотрудник'}
                                                size={28}
                                            />

                                            <span className="employee-assignment-name">
                                                {assignedEmployeeName}
                                            </span>
                                        </>
                                    ) : (
                                        <span className="employee-assignment-name">
                                            Не назначен
                                        </span>
                                    )}

                                    <span className="employee-assignment-arrow">▾</span>
                                </button>

                                {isAssignmentOpen && (
                                    <div
                                        className="employee-assignment-menu"
                                        role="listbox"
                                        aria-label="Ответственный сотрудник"
                                    >
                                        <button
                                            type="button"
                                            className="employee-assignment-option"
                                            role="option"
                                            aria-selected={!conversation.assignedEmployeeId}
                                            onClick={() => {
                                                setIsAssignmentOpen(false)
                                                void handleAssignmentChange('')
                                            }}
                                            disabled={isUpdating}
                                        >
                                            Не назначен
                                        </button>

                                        {employees.map(employee => {
                                            const name =
                                                `${employee.firstName} ${employee.lastName}`.trim()
                                                || employee.username
                                                || employee.email

                                            const selected =
                                                employee.id === conversation.assignedEmployeeId

                                            return (
                                                <button
                                                    key={employee.id}
                                                    type="button"
                                                    className={`employee-assignment-option ${
                                                        selected ? 'selected' : ''
                                                    }`}
                                                    role="option"
                                                    aria-selected={selected}
                                                    onClick={() => {
                                                        setIsAssignmentOpen(false)
                                                        void handleAssignmentChange(employee.id)
                                                    }}
                                                    disabled={isUpdating}
                                                >
                                                    <EmployeeAvatar
                                                        employeeId={employee.id}
                                                        avatarUrl={employee.avatarUrl ?? null}
                                                        username={name}
                                                        size={28}
                                                    />

                                                    <span className="employee-assignment-name">
                                                        {name}
                                                    </span>
                                                </button>
                                            )
                                        })}
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {isEmployee &&
                        conversation.assignedEmployeeId !== null &&
                        !isAssignedToCurrentEmployee && (
                            <span className="conversation-assignment-status">
                                {assignedEmployeeName
                                    ? `Назначен: ${assignedEmployeeName}`
                                    : 'Назначен другому сотруднику'}
                            </span>
                        )}
                </div>
            </div>

            {actionError && (
                <div className="conversation-header-error">
                    {actionError}
                </div>
            )}
        </header>
    )
}

export default ConversationHeader