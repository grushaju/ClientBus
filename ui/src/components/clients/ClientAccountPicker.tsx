import {
    useEffect,
    useState,
} from 'react'

import {
    searchUnassignedClientAccounts,
} from '../../api/clientAccountApi'

import type {
    ClientAccountDto,
} from '../../api/types/clientAccount'

interface ClientAccountPickerProps {
    open: boolean
    onClose: () => void
    onSelect: (
        account: ClientAccountDto,
    ) => void
    busy?: boolean
}

function ClientAccountPicker({
                                 open,
                                 onClose,
                                 onSelect,
                                 busy = false,
                             }: ClientAccountPickerProps) {
    const [query, setQuery] =
        useState('')

    const [accounts, setAccounts] =
        useState<ClientAccountDto[]>([])

    const [loading, setLoading] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (!open) {
            return
        }

        setQuery('')
        setAccounts([])
        setError(null)
    }, [open])

    useEffect(() => {
        if (!open) {
            return
        }

        const controller =
            new AbortController()

        const timeoutId =
            window.setTimeout(
                async () => {
                    setLoading(true)
                    setError(null)

                    try {
                        const result =
                            await searchUnassignedClientAccounts(
                                query.trim(),
                                controller.signal,
                            )

                        setAccounts(result)
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
                                : 'Не удалось загрузить аккаунты',
                        )
                    } finally {
                        if (
                            !controller.signal.aborted
                        ) {
                            setLoading(false)
                        }
                    }
                },
                250,
            )

        return () => {
            window.clearTimeout(timeoutId)
            controller.abort()
        }
    }, [open, query])

    if (!open) {
        return null
    }

    return (
        <div
            className="client-modal-overlay"
            onMouseDown={event => {
                if (
                    event.target ===
                    event.currentTarget
                ) {
                    onClose()
                }
            }}
        >
            <div
                className="client-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="client-account-picker-title"
            >
                <div className="client-modal-header">
                    <div>
                        <h2 id="client-account-picker-title">
                            Привязать аккаунт
                        </h2>

                        <span>
                            Выберите существующий аккаунт без клиента
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
                        <span>
                            Найти аккаунт
                        </span>

                        <input
                            className="ui-input"
                            autoFocus
                            value={query}
                            onChange={event =>
                                setQuery(
                                    event.target.value,
                                )
                            }
                            placeholder="Имя, username, телефон или ID"
                            disabled={busy}
                        />
                    </label>

                    {error && (
                        <div className="clients-error">
                            {error}
                        </div>
                    )}

                    {loading ? (
                        <div className="client-modal-state">
                            Поиск…
                        </div>
                    ) : accounts.length === 0 ? (
                        <div className="client-modal-state">
                            {query.trim()
                                ? 'Аккаунты не найдены'
                                : 'Нет доступных аккаунтов без клиента'}
                        </div>
                    ) : (
                        <div className="client-account-picker-list">
                            {accounts.map(
                                account => (
                                    <button
                                        key={
                                            account.id
                                        }
                                        type="button"
                                        className="client-account-picker-item"
                                        disabled={
                                            busy ||
                                            account.clientId !==
                                            null
                                        }
                                        onClick={() =>
                                            onSelect(
                                                account,
                                            )
                                        }
                                    >
                                        <div className="client-account-picker-main">
                                            <strong>
                                                {
                                                    getAccountTitle(
                                                        account,
                                                    )
                                                }
                                            </strong>

                                            <span>
                                                {
                                                    account.channelType
                                                }
                                            </span>
                                        </div>

                                        <div className="client-account-picker-meta">
                                            {account.username && (
                                                <span>
                                                    @
                                                    {
                                                        account.username
                                                    }
                                                </span>
                                            )}

                                            {account.phone && (
                                                <span>
                                                    {
                                                        account.phone
                                                    }
                                                </span>
                                            )}

                                            <span>
                                                {
                                                    account.externalId
                                                }
                                            </span>
                                        </div>
                                    </button>
                                ),
                            )}
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

function getAccountTitle(
    account: ClientAccountDto,
): string {
    return (
        account.displayName ||
        account.username ||
        account.phone ||
        account.externalId
    )
}

export default ClientAccountPicker