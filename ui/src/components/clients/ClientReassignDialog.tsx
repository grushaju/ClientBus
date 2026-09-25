import { useEffect, useState } from 'react'

import { searchClients } from '../../api/clientApi'

import type { ClientListItemDto } from '../../api/types/client'

import type { ClientAccountDto } from '../../api/types/clientAccount'

interface ClientReassignDialogProps {
    open: boolean
    account: ClientAccountDto | null
    currentClientId: string
    onClose: () => void
    onSelect: (client: ClientListItemDto) => void
    busy?: boolean
}

function ClientReassignDialog({
                                  open,
                                  account,
                                  currentClientId,
                                  onClose,
                                  onSelect,
                                  busy = false,
                              }: ClientReassignDialogProps) {
    const [query, setQuery] = useState('')
    const [clients, setClients] = useState<ClientListItemDto[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        if (!open) {
            return
        }

        setQuery('')
        setClients([])
        setError(null)
    }, [open, account?.id])

    useEffect(() => {
        if (!open) {
            return
        }

        const normalizedQuery = query.trim()

        if (!normalizedQuery) {
            setClients([])
            setLoading(false)
            setError(null)
            return
        }

        const controller = new AbortController()

        const timeoutId = window.setTimeout(async () => {
            setLoading(true)
            setError(null)

            try {
                const result = await searchClients(
                    normalizedQuery,
                    controller.signal,
                )

                setClients(
                    result.filter(
                        client => client.id !== currentClientId,
                    ),
                )
            } catch (err) {
                if (
                    err instanceof DOMException &&
                    err.name === 'AbortError'
                ) {
                    return
                }

                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось найти клиентов',
                )
            } finally {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            }
        }, 250)

        return () => {
            window.clearTimeout(timeoutId)
            controller.abort()
        }
    }, [open, query, currentClientId])

    if (!open || !account) {
        return null
    }

    return (
        <div
            className="client-modal-overlay"
            onMouseDown={event => {
                if (event.target === event.currentTarget) {
                    onClose()
                }
            }}
        >
            <div
                className="client-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="client-reassign-title"
            >
                <div className="client-modal-header">
                    <div>
                        <h2 id="client-reassign-title">
                            Передать аккаунт
                        </h2>

                        <span>
                            Выберите клиента, которому будет передан аккаунт
                        </span>
                    </div>

                    <button
                        type="button"
                        className="client-modal-close"
                        onClick={onClose}
                        disabled={busy}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <div className="client-modal-body">
                    <label className="client-modal-search">
                        <span>Найти клиента</span>

                        <input
                            autoFocus
                            className="ui-input"
                            value={query}
                            onChange={event => setQuery(event.target.value)}
                            placeholder="Имя, фамилия или телефон"
                            disabled={busy}
                        />
                    </label>

                    {error && (
                        <div className="clients-error">
                            {error}
                        </div>
                    )}

                    {!query.trim() ? (
                        <div className="client-modal-state">
                            Введите имя, фамилию или телефон
                        </div>
                    ) : loading ? (
                        <div className="client-modal-state">
                            Поиск…
                        </div>
                    ) : clients.length === 0 ? (
                        <div className="client-modal-state">
                            Клиенты не найдены
                        </div>
                    ) : (
                        <div className="client-account-picker-list">
                            {clients.map(client => (
                                <button
                                    key={client.id}
                                    type="button"
                                    className="client-account-picker-item"
                                    disabled={busy}
                                    onClick={() => onSelect(client)}
                                >
                                    <div className="client-account-picker-main">
                                        <strong>
                                            {getClientTitle(client)}
                                        </strong>

                                        <span>
                                            аккаунтов: {client.accountCount}
                                        </span>
                                    </div>

                                    <div className="client-account-picker-meta">
                                        {client.phoneList.map(phone => (
                                            <span key={phone}>
                                                {phone}
                                            </span>
                                        ))}
                                    </div>
                                </button>
                            ))}
                        </div>
                    )}
                </div>

                <div className="client-modal-footer">
                    <button
                        type="button"
                        className="ui-button ui-button-secondary"
                        onClick={onClose}
                        disabled={busy}
                    >
                        Отмена
                    </button>
                </div>
            </div>
        </div>
    )
}

function getClientTitle(client: ClientListItemDto): string {
    const fullName = `${client.firstName} ${client.lastName}`.trim()

    return fullName || client.phoneList[0] || client.id
}

export default ClientReassignDialog