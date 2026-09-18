import { useState } from 'react'

import type {
    ConversationDto,
    ClientAccountSummary
} from '../../api/types/conversation'

interface ConversationHeaderProps {
    conversation: ConversationDto
    clientAccount: ClientAccountSummary | null
    isSuperAdmin: boolean
    isEmployee: boolean
    onTake: () => Promise<void>
    onRelease: () => Promise<void>
}

function ConversationHeader({
                                conversation,
                                clientAccount,
                                isSuperAdmin,
                                isEmployee,
                                onTake,
                                onRelease
                            }: ConversationHeaderProps) {
    const [
        isUpdating,
        setIsUpdating
    ] = useState(false)

    const clientName =
        clientAccount?.displayName ||
        clientAccount?.username ||
        clientAccount?.phone ||
        clientAccount?.externalId ||
        'Неизвестный клиент'

    const platform =
        clientAccount?.channelType ??
        'UNKNOWN'

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

    return (
        <header className="conversation-header">
            <div className="conversation-header-main">
                <div>
                    <h2>
                        {clientName}
                    </h2>

                    <div className="conversation-header-meta">
                        {platform}

                        {clientAccount?.username && (
                            <span>
                                @{clientAccount.username}
                            </span>
                        )}
                    </div>
                </div>

                <div className="conversation-header-actions">
                    {isEmployee &&
                        conversation.assignedEmployeeId ===
                        null && (
                            <button
                                type="button"
                                onClick={
                                    handleTake
                                }
                                disabled={
                                    isUpdating
                                }
                            >
                                Взять
                            </button>
                        )}

                    {isEmployee &&
                        conversation.assignedEmployeeId !==
                        null && (
                            <button
                                type="button"
                                onClick={
                                    handleRelease
                                }
                                disabled={
                                    isUpdating
                                }
                            >
                                Освободить
                            </button>
                        )}

                    {isSuperAdmin && (
                        <span className="assignment-label">
                            {conversation.assignedEmployeeId
                                ? 'Назначен сотруднику'
                                : 'Неназначен'}
                        </span>
                    )}
                </div>
            </div>
        </header>
    )
}

export default ConversationHeader