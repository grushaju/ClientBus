import {
    useEffect,
    useState,
} from 'react'

import {
    useNavigate,
    useSearchParams,
} from 'react-router-dom'

import {
    createClient,
    getClients,
    searchClients,
} from '../api/clientApi'

import type {
    ClientListItemDto,
} from '../api/types/client'

function ClientsPage() {
    const navigate = useNavigate()

    const [searchParams, setSearchParams] =
        useSearchParams()

    const query =
        searchParams.get('q') ?? ''

    const [clients, setClients] =
        useState<ClientListItemDto[]>([])

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState<string | null>(null)

    const [showCreateForm, setShowCreateForm] =
        useState(false)

    const [firstName, setFirstName] =
        useState('')

    const [lastName, setLastName] =
        useState('')

    const [phone, setPhone] =
        useState('')

    const [creating, setCreating] =
        useState(false)

    const loadClients = async (
        currentQuery: string,
    ) => {
        setLoading(true)
        setError(null)

        try {
            const result =
                currentQuery.trim()
                    ? await searchClients(
                        currentQuery.trim(),
                    )
                    : await getClients()

            setClients(result)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить клиентов',
            )

            setClients([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        const timer =
            window.setTimeout(() => {
                void loadClients(query)
            }, 250)

        return () =>
            window.clearTimeout(timer)
    }, [query])

    const handleSearch = (
        value: string,
    ) => {
        const trimmed =
            value.trim()

        if (!trimmed) {
            setSearchParams({})
            return
        }

        setSearchParams({
            q: value,
        })
    }

    const handleCreate = async (
        event: React.FormEvent,
    ) => {
        event.preventDefault()

        if (!firstName.trim()) {
            return
        }

        setCreating(true)
        setError(null)

        try {
            const client =
                await createClient({
                    firstName:
                        firstName.trim(),
                    lastName:
                        lastName.trim(),
                    phoneList:
                        phone.trim()
                            ? [phone.trim()]
                            : [],
                })

            setShowCreateForm(false)
            setFirstName('')
            setLastName('')
            setPhone('')

            navigate(
                `/clients/${client.id}`,
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать клиента',
            )
        } finally {
            setCreating(false)
        }
    }

    return (
        <div className="clients-page">
            <div className="clients-header">
                <div>
                    <h1>Клиенты</h1>

                    <span>
                        {clients.length}{' '}
                        клиентов
                    </span>
                </div>

                <button
                    type="button"
                    className="clients-primary-button"
                    onClick={() =>
                        setShowCreateForm(
                            value => !value,
                        )
                    }
                >
                    Новый клиент
                </button>
            </div>

            <div className="clients-toolbar">
                <input
                    type="search"
                    value={query}
                    placeholder="Поиск по имени или телефону"
                    onChange={event =>
                        handleSearch(
                            event.target.value,
                        )
                    }
                />
            </div>

            {showCreateForm && (
                <form
                    className="client-create-form"
                    onSubmit={handleCreate}
                >
                    <div className="client-form-fields">
                        <label>
                            <span>
                                Имя
                            </span>

                            <input
                                value={firstName}
                                onChange={event =>
                                    setFirstName(
                                        event.target.value,
                                    )
                                }
                                autoFocus
                            />
                        </label>

                        <label>
                            <span>
                                Фамилия
                            </span>

                            <input
                                value={lastName}
                                onChange={event =>
                                    setLastName(
                                        event.target.value,
                                    )
                                }
                            />
                        </label>

                        <label>
                            <span>
                                Телефон
                            </span>

                            <input
                                value={phone}
                                onChange={event =>
                                    setPhone(
                                        event.target.value,
                                    )
                                }
                            />
                        </label>
                    </div>

                    <div className="client-form-actions">
                        <button
                            type="button"
                            onClick={() =>
                                setShowCreateForm(
                                    false,
                                )
                            }
                        >
                            Отмена
                        </button>

                        <button
                            type="submit"
                            className="clients-primary-button"
                            disabled={
                                creating ||
                                !firstName.trim()
                            }
                        >
                            {creating
                                ? 'Создание…'
                                : 'Создать'}
                        </button>
                    </div>
                </form>
            )}

            {error && (
                <div className="clients-error">
                    {error}
                </div>
            )}

            {loading && (
                <div className="clients-state">
                    Загрузка…
                </div>
            )}

            {!loading &&
                !error &&
                clients.length === 0 && (
                    <div className="clients-state">
                        {query
                            ? 'Клиенты не найдены'
                            : 'Клиентов пока нет'}
                    </div>
                )}

            {!loading &&
                clients.length > 0 && (
                    <div className="clients-list">
                        {clients.map(client => (
                            <button
                                key={client.id}
                                type="button"
                                className="client-list-item"
                                onClick={() =>
                                    navigate(
                                        `/clients/${client.id}`,
                                    )
                                }
                            >
                                <div className="client-list-avatar">
                                    {getInitials(
                                        client.firstName,
                                        client.lastName,
                                    )}
                                </div>

                                <div className="client-list-main">
                                    <div className="client-list-name">
                                        <strong>
                                            {client.firstName}{' '}
                                            {client.lastName}
                                        </strong>

                                        {!client.enabled && (
                                            <span className="client-disabled-badge">
                                                Отключён
                                            </span>
                                        )}
                                    </div>

                                    <div className="client-list-meta">
                                        {client.phoneList
                                            .length > 0
                                            ? client.phoneList.join(
                                                ', ',
                                            )
                                            : 'Телефон не указан'}
                                    </div>
                                </div>

                                <div className="client-list-stats">
                                    <span>
                                        {client.accountCount}{' '}
                                        аккаунтов
                                    </span>

                                    <span>
                                        {formatLastContact(
                                            client.lastContactAt,
                                        )}
                                    </span>
                                </div>
                            </button>
                        ))}
                    </div>
                )}
        </div>
    )
}

function getInitials(
    firstName: string,
    lastName: string,
): string {
    return (
        `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`
    ).toUpperCase()
}

function formatLastContact(
    value: string | null,
): string {
    if (!value) {
        return 'Нет контактов'
    }

    return new Intl.DateTimeFormat(
        'ru-RU',
        {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        },
    ).format(new Date(value))
}

export default ClientsPage