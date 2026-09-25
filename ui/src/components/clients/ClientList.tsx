import type {
    ClientListItemDto,
} from '../../api/types/client'

interface ClientListProps {
    clients: ClientListItemDto[]

    onClientClick: (
        clientId: string,
    ) => void
}

function ClientList({
                        clients,
                        onClientClick,
                    }: ClientListProps) {
    return (
        <div className="clients-list">
            {clients.map(client => (
                <button
                    key={client.id}
                    type="button"
                    className="client-list-item"
                    onClick={() =>
                        onClientClick(
                            client.id,
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
                            {formatAccountCount(
                                client.accountCount,
                            )}
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

function formatAccountCount(
    count: number,
): string {
    if (
        count % 10 === 1 &&
        count % 100 !== 11
    ) {
        return `${count} аккаунт`
    }

    if (
        count % 10 >= 2 &&
        count % 10 <= 4 &&
        (
            count % 100 < 10 ||
            count % 100 >= 20
        )
    ) {
        return `${count} аккаунта`
    }

    return `${count} аккаунтов`
}

function formatLastContact(
    value: string | null,
): string {
    if (!value) {
        return 'Нет контактов'
    }

    const date =
        new Date(value)

    if (
        Number.isNaN(
            date.getTime(),
        )
    ) {
        return 'Нет контактов'
    }

    const now =
        new Date()

    const isToday =
        date.getFullYear() ===
        now.getFullYear() &&
        date.getMonth() ===
        now.getMonth() &&
        date.getDate() ===
        now.getDate()

    if (isToday) {
        return `Сегодня, ${new Intl.DateTimeFormat(
            'ru-RU',
            {
                hour: '2-digit',
                minute: '2-digit',
            },
        ).format(date)}`
    }

    const yesterday =
        new Date(now)

    yesterday.setDate(
        yesterday.getDate() - 1,
    )

    const isYesterday =
        date.getFullYear() ===
        yesterday.getFullYear() &&
        date.getMonth() ===
        yesterday.getMonth() &&
        date.getDate() ===
        yesterday.getDate()

    if (isYesterday) {
        return `Вчера, ${new Intl.DateTimeFormat(
            'ru-RU',
            {
                hour: '2-digit',
                minute: '2-digit',
            },
        ).format(date)}`
    }

    return new Intl.DateTimeFormat(
        'ru-RU',
        {
            day: '2-digit',
            month: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
        },
    ).format(date)
}

export default ClientList