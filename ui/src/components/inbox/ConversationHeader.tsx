import { useState } from 'react'

import type { EmployeeDto } from '../../auth/types'
import type {
    ChannelSummary,
    ClientAccountSummary,
    ConversationDto,
} from '../../api/types/conversation'
import PlatformIcon from '../common/platform/PlatformIcon'

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
    const [isUpdating, setIsUpdating] = useState(false)

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
        conversation.assignedEmployeeId === currentEmployeeId

    const assignedEmployee =
        employees.find(
            employee =>
                employee.id === conversation.assignedEmployeeId,
        ) ?? null

    const assignedEmployeeName = assignedEmployee
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
                    <div className="conversation-header-avatar">
                        {clientName.charAt(0).toUpperCase()}
                    </div>

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
                                className="conversation-action-button"
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
                                className="conversation-action-button"
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

                            <select
                                id="conversation-assignment"
                                className="conversation-assignment-select"
                                value={
                                    conversation.assignedEmployeeId ?? ''
                                }
                                onChange={event =>
                                    void handleAssignmentChange(
                                        event.target.value,
                                    )
                                }
                                disabled={isUpdating}
                            >
                                <option value="">
                                    Не назначен
                                </option>

                                {employees.map(employee => {
                                    const name =
                                        `${employee.firstName} ${employee.lastName}`.trim()

                                    return (
                                        <option
                                            key={employee.id}
                                            value={employee.id}
                                        >
                                            {name || employee.username}
                                        </option>
                                    )
                                })}
                            </select>
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