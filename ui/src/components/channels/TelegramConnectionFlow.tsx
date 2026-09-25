import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react'

import type { FormEvent } from 'react'

import type {
    ChannelDto,
    TelegramAuthorizationStatus,
} from '../../api/types/channel'

import {
    getTelegramConnectionStatus,
    startTelegramConnection,
    submitTelegramCode,
    submitTelegramEmail,
    submitTelegramPassword,
} from '../../api/telegramChannelApi'

interface TelegramConnectionFlowProps {
    channel: ChannelDto
    onClose: () => void
    onConnected: (channel: ChannelDto) => void
}

const POLL_INTERVAL_MS = 500
const POLL_ATTEMPTS = 20

const TERMINAL_AUTHORIZATION_STATES:
    TelegramAuthorizationStatus[] = [
    'WAIT_CODE',
    'WAIT_PASSWORD',
    'WAIT_EMAIL',
    'READY',
    'ERROR',
    'CLOSED',
]

function delay(ms: number): Promise<void> {
    return new Promise((resolve) => {
        window.setTimeout(resolve, ms)
    })
}

function isTerminalAuthorizationState(
    status: TelegramAuthorizationStatus,
): boolean {
    return TERMINAL_AUTHORIZATION_STATES.includes(status)
}

async function waitForAuthorizationState(
    channelAccountId: string,
    onStatus: (
        status: TelegramAuthorizationStatus,
    ) => void,
    isCancelled: () => boolean,
): Promise<TelegramAuthorizationStatus> {
    for (
        let attempt = 0;
        attempt < POLL_ATTEMPTS;
        attempt++
    ) {
        const status =
            await getTelegramConnectionStatus(
                channelAccountId,
            )

        if (isCancelled()) {
            throw new Error(
                'Авторизация Telegram была закрыта.',
            )
        }

        onStatus(status)

        if (isTerminalAuthorizationState(status)) {
            return status
        }

        await delay(POLL_INTERVAL_MS)

        if (isCancelled()) {
            throw new Error(
                'Авторизация Telegram была закрыта.',
            )
        }
    }

    throw new Error(
        'Не удалось получить состояние авторизации Telegram.',
    )
}

async function waitForAuthorizationStateChange(
    channelAccountId: string,
    previousStatus: TelegramAuthorizationStatus,
    onStatus: (
        status: TelegramAuthorizationStatus,
    ) => void,
    isCancelled: () => boolean,
): Promise<TelegramAuthorizationStatus> {
    await delay(POLL_INTERVAL_MS)

    for (
        let attempt = 0;
        attempt < POLL_ATTEMPTS;
        attempt++
    ) {
        const status =
            await getTelegramConnectionStatus(
                channelAccountId,
            )

        if (isCancelled()) {
            throw new Error(
                'Авторизация Telegram была закрыта.',
            )
        }

        if (status !== previousStatus) {
            onStatus(status)
            return status
        }

        await delay(POLL_INTERVAL_MS)

        if (isCancelled()) {
            throw new Error(
                'Авторизация Telegram была закрыта.',
            )
        }
    }

    throw new Error(
        'Telegram не изменил состояние авторизации в течение ожидаемого времени.',
    )
}

function getStatusTitle(
    status: TelegramAuthorizationStatus | null,
): string {
    switch (status) {
        case 'WAIT_PHONE_NUMBER':
            return 'Подготовка авторизации'

        case 'WAIT_CODE':
            return 'Введите код'

        case 'WAIT_PASSWORD':
            return 'Введите пароль'

        case 'WAIT_EMAIL':
            return 'Введите email'

        case 'READY':
            return 'Telegram подключён'

        case 'ERROR':
            return 'Ошибка авторизации'

        case 'CLOSED':
            return 'Авторизация закрыта'

        default:
            return 'Подключение Telegram'
    }
}

function getStatusDescription(
    status: TelegramAuthorizationStatus | null,
): string {
    switch (status) {
        case 'WAIT_PHONE_NUMBER':
            return 'Подготавливаем соединение с Telegram...'

        case 'WAIT_CODE':
            return 'Код был отправлен в Telegram.'

        case 'WAIT_PASSWORD':
            return 'Введите пароль двухэтапной аутентификации Telegram.'

        case 'WAIT_EMAIL':
            return 'Telegram запросил адрес электронной почты.'

        case 'READY':
            return 'Авторизация успешно завершена.'

        case 'ERROR':
            return 'Не удалось завершить авторизацию Telegram.'

        case 'CLOSED':
            return 'Сессия авторизации Telegram закрыта.'

        default:
            return 'Получаем состояние авторизации...'
    }
}

export function TelegramConnectionFlow({
                                           channel,
                                           onClose,
                                           onConnected,
                                       }: TelegramConnectionFlowProps) {
    const accountId = channel.account?.id ?? null

    const [status, setStatus] =
        useState<TelegramAuthorizationStatus | null>(null)

    const [code, setCode] = useState('')
    const [password, setPassword] = useState('')
    const [email, setEmail] = useState('')

    const [loading, setLoading] = useState(true)
    const [submitting, setSubmitting] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const connectedHandledRef = useRef(false)

    const cancelledRef = useRef(false)

    const handleStatus = useCallback(
        (nextStatus: TelegramAuthorizationStatus) => {
            setStatus(nextStatus)

            if (
                nextStatus === 'READY' &&
                !connectedHandledRef.current
            ) {
                connectedHandledRef.current = true
                onConnected(channel)
            }
        },
        [channel, onConnected],
    )

    useEffect(() => {
        cancelledRef.current = false

        async function initialize() {
            if (!accountId) {
                setError(
                    'У канала отсутствует подключённый аккаунт.',
                )
                setLoading(false)
                return
            }

            setLoading(true)
            setError(null)

            const isCancelled = () =>
                cancelledRef.current

            try {
                if (
                    channel.status === 'CREATED' ||
                    channel.status === 'DISCONNECTED' ||
                    channel.status === 'ERROR'
                ) {
                    await startTelegramConnection(
                        accountId,
                    )

                    if (isCancelled()) {
                        return
                    }

                    await waitForAuthorizationState(
                        accountId,
                        handleStatus,
                        isCancelled,
                    )
                } else {
                    const currentStatus =
                        await getTelegramConnectionStatus(
                            accountId,
                        )

                    if (isCancelled()) {
                        return
                    }

                    handleStatus(currentStatus)
                }
            } catch (err) {
                if (isCancelled()) {
                    return
                }

                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось запустить авторизацию Telegram.',
                )
            } finally {
                if (!isCancelled()) {
                    setLoading(false)
                }
            }
        }

        void initialize()

        return () => {
            cancelledRef.current = true
        }
    }, [
        accountId,
        channel.status,
        handleStatus,
    ])

    async function handleSubmitCode(
        event: FormEvent,
    ) {
        event.preventDefault()

        if (!accountId || !code.trim()) {
            return
        }

        setSubmitting(true)
        setError(null)

        try {
            await submitTelegramCode(
                accountId,
                code.trim(),
            )

            setCode('')

            await waitForAuthorizationStateChange(
                accountId,
                'WAIT_CODE',
                handleStatus,
                () => cancelledRef.current,
            )
        } catch (err) {
            if (cancelledRef.current) {
                return
            }

            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось обработать код Telegram.',
            )
        } finally {
            if (!cancelledRef.current) {
                setSubmitting(false)
            }
        }
    }

    async function handleSubmitPassword(
        event: FormEvent,
    ) {
        event.preventDefault()

        if (!accountId || !password) {
            return
        }

        setSubmitting(true)
        setError(null)

        try {
            await submitTelegramPassword(
                accountId,
                password,
            )

            setPassword('')

            await waitForAuthorizationStateChange(
                accountId,
                'WAIT_PASSWORD',
                handleStatus,
                () => cancelledRef.current,
            )
        } catch (err) {
            if (cancelledRef.current) {
                return
            }

            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось обработать пароль Telegram.',
            )
        } finally {
            if (!cancelledRef.current) {
                setSubmitting(false)
            }
        }
    }

    async function handleSubmitEmail(
        event: FormEvent,
    ) {
        event.preventDefault()

        if (!accountId || !email.trim()) {
            return
        }

        setSubmitting(true)
        setError(null)

        try {
            await submitTelegramEmail(
                accountId,
                email.trim(),
            )

            setEmail('')

            await waitForAuthorizationStateChange(
                accountId,
                'WAIT_EMAIL',
                handleStatus,
                () => cancelledRef.current,
            )
        } catch (err) {
            if (cancelledRef.current) {
                return
            }

            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось обработать email Telegram.',
            )
        } finally {
            if (!cancelledRef.current) {
                setSubmitting(false)
            }
        }
    }

    function renderContent() {
        if (loading) {
            return (
                <div className="channel-form">
                    <div className="channel-form-hint">
                        {getStatusDescription(status)}
                    </div>
                </div>
            )
        }

        if (error) {
            return (
                <>
                    <div className="channel-form">
                        <div className="channel-form-error">
                            {error}
                        </div>
                    </div>

                    <div className="channel-modal-actions">
                        <button
                            type="button"
                            className="ui-button ui-button-secondary"
                            onClick={onClose}
                        >
                            Закрыть
                        </button>
                    </div>
                </>
            )
        }

        switch (status) {
            case 'WAIT_CODE':
                return (
                    <form
                        className="channel-form"
                        onSubmit={handleSubmitCode}
                    >
                        <div className="channel-form-hint">
                            {getStatusDescription(status)}
                        </div>

                        <label className="channel-form-field">
                            <span>Код из Telegram</span>

                            <input
                                type="text"
                                inputMode="numeric"
                                autoComplete="one-time-code"
                                value={code}
                                onChange={(event) =>
                                    setCode(event.target.value)
                                }
                                placeholder="12345"
                                autoFocus
                                disabled={submitting}
                            />
                        </label>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={onClose}
                                disabled={submitting}
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                className="ui-button ui-button-primary"
                                disabled={
                                    submitting ||
                                    !code.trim()
                                }
                            >
                                {submitting
                                    ? 'Проверка...'
                                    : 'Продолжить'}
                            </button>
                        </div>
                    </form>
                )

            case 'WAIT_PASSWORD':
                return (
                    <form
                        className="channel-form"
                        onSubmit={handleSubmitPassword}
                    >
                        <div className="channel-form-hint">
                            {getStatusDescription(status)}
                        </div>

                        <label className="channel-form-field">
                            <span>
                                Пароль двухэтапной аутентификации
                            </span>

                            <input
                                type="password"
                                autoComplete="current-password"
                                value={password}
                                onChange={(event) =>
                                    setPassword(
                                        event.target.value,
                                    )
                                }
                                autoFocus
                                disabled={submitting}
                            />
                        </label>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={onClose}
                                disabled={submitting}
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                className="ui-button ui-button-primary"
                                disabled={
                                    submitting ||
                                    !password
                                }
                            >
                                {submitting
                                    ? 'Проверка...'
                                    : 'Продолжить'}
                            </button>
                        </div>
                    </form>
                )

            case 'WAIT_EMAIL':
                return (
                    <form
                        className="channel-form"
                        onSubmit={handleSubmitEmail}
                    >
                        <div className="channel-form-hint">
                            {getStatusDescription(status)}
                        </div>

                        <label className="channel-form-field">
                            <span>Email</span>

                            <input
                                type="email"
                                autoComplete="email"
                                value={email}
                                onChange={(event) =>
                                    setEmail(event.target.value)
                                }
                                placeholder="name@example.com"
                                autoFocus
                                disabled={submitting}
                            />
                        </label>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={onClose}
                                disabled={submitting}
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                className="ui-button ui-button-primary"
                                disabled={
                                    submitting ||
                                    !email.trim()
                                }
                            >
                                {submitting
                                    ? 'Проверка...'
                                    : 'Продолжить'}
                            </button>
                        </div>
                    </form>
                )

            case 'READY':
                return (
                    <>
                        <div className="channel-connection-success">
                            <div className="channel-connection-success-title">
                                Telegram подключён
                            </div>

                            <div className="channel-connection-success-text">
                                Авторизация Telegram успешно
                                завершена.
                            </div>
                        </div>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-primary"
                                onClick={onClose}
                            >
                                Готово
                            </button>
                        </div>
                    </>
                )

            case 'ERROR':
                return (
                    <>
                        <div className="channel-form">
                            <div className="channel-form-error">
                                {getStatusDescription(status)}
                            </div>
                        </div>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={onClose}
                            >
                                Закрыть
                            </button>
                        </div>
                    </>
                )

            case 'CLOSED':
                return (
                    <>
                        <div className="channel-form">
                            <div className="channel-form-hint">
                                {getStatusDescription(status)}
                            </div>
                        </div>

                        <div className="channel-modal-actions">
                            <button
                                type="button"
                                className="ui-button ui-button-secondary"
                                onClick={onClose}
                            >
                                Закрыть
                            </button>
                        </div>
                    </>
                )

            case 'WAIT_PHONE_NUMBER':
            default:
                return (
                    <div className="channel-form">
                        <div className="channel-form-hint">
                            {getStatusTitle(status)}
                        </div>

                        <div className="channel-form-hint">
                            {getStatusDescription(status)}
                        </div>
                    </div>
                )
        }
    }

    return (
        <>
            {renderContent()}
        </>
    )
}