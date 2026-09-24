import {
    FormEvent,
    useState
} from 'react'

import {
    Navigate,
    useLocation,
    useNavigate
} from 'react-router-dom'

import { useAuth } from '../auth/AuthContext'

import {
    getApiErrorCode
} from '../api/apiClient'

type LoginMode =
    | 'username'
    | 'email'

interface LoginLocationState {
    from?: {
        pathname?: string
    }
}

function LoginPage() {
    const {
        isAuthenticated,
        isLoading,
        login
    } = useAuth()

    const navigate = useNavigate()
    const location = useLocation()

    const [mode, setMode] =
        useState<LoginMode>('username')

    const [identifier, setIdentifier] =
        useState('')

    const [password, setPassword] =
        useState('')

    const [error, setError] =
        useState<string | null>(null)

    const [loading, setLoading] =
        useState(false)

    if (isLoading) {
        return (
            <main className="login-page">
                <div className="login-card">
                    Загрузка...
                </div>
            </main>
        )
    }

    if (isAuthenticated) {
        return (
            <Navigate
                to="/inbox"
                replace
            />
        )
    }

    async function handleSubmit(
        event: FormEvent<HTMLFormElement>
    ) {
        event.preventDefault()

        setError(null)
        setLoading(true)

        try {
            await login(
                mode === 'username'
                    ? identifier
                    : undefined,
                mode === 'email'
                    ? identifier
                    : undefined,
                password
            )

            const state =
                location.state as
                    | LoginLocationState
                    | null

            const from =
                state?.from?.pathname

            navigate(
                from ?? '/inbox',
                { replace: true }
            )
        } catch (error) {
            if (
                getApiErrorCode(error) ===
                'USER_DISABLED'
            ) {
                setError(
                    'Ваша учётная запись отключена. Обратитесь к администратору.'
                )
            } else {
                setError(
                    'Неверный логин или пароль'
                )
            }
        } finally {
            setLoading(false)
        }
    }

    return (
        <main className="login-page">
            <div className="login-card">
                <h1>ClientBus</h1>

                <h2>Вход</h2>

                <div className="login-mode">
                    <button
                        type="button"
                        className={
                            mode === 'username'
                                ? 'active'
                                : ''
                        }
                        onClick={() => {
                            setMode('username')
                            setIdentifier('')
                            setError(null)
                        }}
                    >
                        Username
                    </button>

                    <button
                        type="button"
                        className={
                            mode === 'email'
                                ? 'active'
                                : ''
                        }
                        onClick={() => {
                            setMode('email')
                            setIdentifier('')
                            setError(null)
                        }}
                    >
                        Email
                    </button>
                </div>

                <form onSubmit={handleSubmit}>
                    <label>
                        {
                            mode === 'username'
                                ? 'Username'
                                : 'Email'
                        }

                        <input
                            type={
                                mode === 'email'
                                    ? 'email'
                                    : 'text'
                            }
                            value={identifier}
                            onChange={(event) =>
                                setIdentifier(
                                    event.target.value
                                )
                            }
                            required
                            autoComplete={
                                mode === 'email'
                                    ? 'email'
                                    : 'username'
                            }
                            autoFocus
                        />
                    </label>

                    <label>
                        Пароль

                        <input
                            type="password"
                            value={password}
                            onChange={(event) =>
                                setPassword(
                                    event.target.value
                                )
                            }
                            required
                            autoComplete="current-password"
                        />
                    </label>

                    {error && (
                        <div className="login-error">
                            {error}
                        </div>
                    )}

                    <button
                        type="submit"
                        disabled={loading}
                    >
                        {
                            loading
                                ? 'Вход...'
                                : 'Войти'
                        }
                    </button>
                </form>
            </div>
        </main>
    )
}

export default LoginPage