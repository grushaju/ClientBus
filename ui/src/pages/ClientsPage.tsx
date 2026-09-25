import {
    useEffect,
    useState,
} from 'react'

import {
    useNavigate,
    useSearchParams,
} from 'react-router-dom'

import {
    getClients,
    searchClients,
} from '../api/clientApi'

import type {
    ClientListItemDto,
} from '../api/types/client'

import ClientCreateForm
    from '../components/clients/ClientCreateForm'

import ClientList
    from '../components/clients/ClientList'

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

    useEffect(() => {
        const controller =
            new AbortController()

        const timer =
            window.setTimeout(async () => {
                setLoading(true)
                setError(null)

                try {
                    const result =
                        query.trim()
                            ? await searchClients(
                                query.trim(),
                                controller.signal,
                            )
                            : await getClients(
                                controller.signal,
                            )

                    if (
                        !controller.signal.aborted
                    ) {
                        setClients(result)
                    }
                } catch (err) {
                    if (
                        controller.signal.aborted
                    ) {
                        return
                    }

                    setError(
                        err instanceof Error
                            ? err.message
                            : 'Не удалось загрузить клиентов',
                    )

                    setClients([])
                } finally {
                    if (
                        !controller.signal.aborted
                    ) {
                        setLoading(false)
                    }
                }
            }, 250)

        return () => {
            window.clearTimeout(timer)
            controller.abort()
        }
    }, [query])

    const handleSearch = (
        value: string,
    ) => {
        if (!value.trim()) {
            setSearchParams({})
            return
        }

        setSearchParams({
            q: value,
        })
    }

    const handleCreated = (
        clientId: string,
    ) => {
        setShowCreateForm(false)

        navigate(
            `/clients/${clientId}`,
        )
    }

    return (
        <div className="clients-page">
            <div className="clients-header">
                <div>
                    <h1>
                        Клиенты
                    </h1>

                    <span>
                        {clients.length}{' '}
                        клиентов
                    </span>
                </div>

                <button
                    type="button"
                    className="ui-button ui-button-primary"
                    onClick={() =>
                        setShowCreateForm(
                            value => !value,
                        )
                    }
                >
                    + Добавить
                </button>
            </div>

            <div className="clients-toolbar">
                <input
                    className="ui-input"
                    type="search"
                    value={query}
                    placeholder="Поиск по имени или телефону"
                    onChange={event =>
                        handleSearch(
                            event.target.value,
                        )
                    }
                    aria-label="Поиск клиентов"
                />
            </div>

            {showCreateForm && (
                <ClientCreateForm
                    onCreated={
                        handleCreated
                    }
                    onCancel={() =>
                        setShowCreateForm(
                            false,
                        )
                    }
                    onError={setError}
                />
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
                    <ClientList
                        clients={clients}
                        onClientClick={
                            clientId =>
                                navigate(
                                    `/clients/${clientId}`,
                                )
                        }
                    />
                )}
        </div>
    )
}

export default ClientsPage