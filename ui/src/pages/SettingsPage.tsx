import {
    useEffect,
    useState,
} from 'react'

import {
    changeCurrentPassword,
    updateCurrentCredentials,
    updateCurrentEmployee,
} from '../api/employeeApi'

import {
    updateOrganization,
} from '../api/organizationApi'

import {
    useAuth,
} from '../auth/AuthContext'
import {getApiErrorCode} from "../api/apiClient";

type SettingsSection =
    | 'PROFILE'
    | 'SECURITY'
    | 'ORGANIZATION'

function SettingsPage() {
    const {
        currentEmployee,
        currentOrganization,
        isSuperAdmin,
        refreshCurrentUser,
    } = useAuth()

    const [
        section,
        setSection,
    ] = useState<SettingsSection>(
        'PROFILE',
    )

    const [
        firstName,
        setFirstName,
    ] = useState('')

    const [
        lastName,
        setLastName,
    ] = useState('')

    const [
        username,
        setUsername,
    ] = useState('')

    const [
        email,
        setEmail,
    ] = useState('')

    const [
        phone,
        setPhone,
    ] = useState('')

    const [
        organizationName,
        setOrganizationName,
    ] = useState('')

    const [
        currentPassword,
        setCurrentPassword,
    ] = useState('')

    const [
        newPassword,
        setNewPassword,
    ] = useState('')

    const [
        confirmPassword,
        setConfirmPassword,
    ] = useState('')

    const [
        saving,
        setSaving,
    ] = useState(false)

    const [
        success,
        setSuccess,
    ] = useState<string | null>(
        null,
    )

    const [
        error,
        setError,
    ] = useState<string | null>(
        null,
    )

    useEffect(() => {
        if (!currentEmployee) {
            return
        }

        setFirstName(
            currentEmployee.firstName ?? '',
        )

        setLastName(
            currentEmployee.lastName ?? '',
        )

        setUsername(
            currentEmployee.username ?? '',
        )

        setEmail(
            currentEmployee.email ?? '',
        )

        setPhone(
            currentEmployee.phone ?? '',
        )
    }, [
        currentEmployee,
    ])

    useEffect(() => {
        if (!currentOrganization) {
            return
        }

        setOrganizationName(
            currentOrganization.name,
        )
    }, [
        currentOrganization,
    ])

    if (!currentEmployee) {
        return (
            <div className="settings-page">
                <div className="settings-state">
                    Загрузка настроек…
                </div>
            </div>
        )
    }

    /*
     * После проверки выше currentEmployee
     * гарантированно существует.
     *
     * Локальная константа позволяет TypeScript
     * корректно сохранить narrowing внутри
     * вложенных callback/handler-функций.
     */
    const employee = currentEmployee

    function clearMessages() {
        setError(null)
        setSuccess(null)
    }

    async function handleProfileSave() {
        clearMessages()
        setSaving(true)

        try {
            const profileChanged =
                firstName !==
                (employee.firstName ?? '') ||
                lastName !==
                (employee.lastName ?? '') ||
                phone !==
                (employee.phone ?? '')

            const credentialsChanged =
                username !==
                (employee.username ?? '') ||
                email !==
                (employee.email ?? '')

            if (profileChanged) {
                await updateCurrentEmployee({
                    firstName:
                        firstName.trim(),
                    lastName:
                        lastName.trim(),
                    phone:
                        phone.trim(),
                })
            }

            if (credentialsChanged) {
                await updateCurrentCredentials({
                    username:
                        username.trim(),
                    email:
                        email.trim(),
                })
            }

            if (
                profileChanged ||
                credentialsChanged
            ) {
                await refreshCurrentUser()
            }

            setSuccess(
                'Профиль сохранён.',
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось сохранить профиль.',
            )
        } finally {
            setSaving(false)
        }
    }

    async function handlePasswordChange() {
        clearMessages()

        if (!currentPassword) {
            setError(
                'Введите текущий пароль.',
            )
            return
        }

        if (!newPassword) {
            setError(
                'Введите новый пароль.',
            )
            return
        }

        if (
            newPassword !==
            confirmPassword
        ) {
            setError(
                'Новые пароли не совпадают.',
            )
            return
        }

        setSaving(true)

        try {
            await changeCurrentPassword(
                currentPassword,
                newPassword,
            )

            setCurrentPassword('')
            setNewPassword('')
            setConfirmPassword('')

            setSuccess(
                'Пароль изменён.',
            )
        } catch (err) {
            if (
                getApiErrorCode(err) ===
                'INVALID_CURRENT_PASSWORD'
            ) {
                setError(
                    'Текущий пароль указан неверно.',
                )
            } else {
                setError(
                    err instanceof Error
                        ? err.message
                        : 'Не удалось изменить пароль.',
                )
            }
        } finally {
            setSaving(false)
        }
    }

    async function handleOrganizationSave() {
        if (!currentOrganization) {
            return
        }

        const name =
            organizationName.trim()

        clearMessages()

        if (!name) {
            setError(
                'Введите название организации.',
            )
            return
        }

        setSaving(true)

        try {
            await updateOrganization(
                currentOrganization.id,
                {
                    name,
                },
            )

            await refreshCurrentUser()

            setSuccess(
                'Организация сохранена.',
            )
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось сохранить организацию.',
            )
        } finally {
            setSaving(false)
        }
    }

    return (
        <div className="settings-page">
            <div className="settings-header">
                <div>
                    <h1>
                        Настройки
                    </h1>

                    <p>
                        Управление профилем
                        и параметрами аккаунта.
                    </p>
                </div>
            </div>

            <div className="settings-layout">
                <aside className="settings-sidebar">
                    <button
                        type="button"
                        className={
                            section ===
                            'PROFILE'
                                ? 'active'
                                : ''
                        }
                        onClick={() => {
                            setSection(
                                'PROFILE',
                            )
                            clearMessages()
                        }}
                    >
                        Профиль
                    </button>

                    <button
                        type="button"
                        className={
                            section ===
                            'SECURITY'
                                ? 'active'
                                : ''
                        }
                        onClick={() => {
                            setSection(
                                'SECURITY',
                            )
                            clearMessages()
                        }}
                    >
                        Безопасность
                    </button>

                    {isSuperAdmin && (
                        <button
                            type="button"
                            className={
                                section ===
                                'ORGANIZATION'
                                    ? 'active'
                                    : ''
                            }
                            onClick={() => {
                                setSection(
                                    'ORGANIZATION',
                                )
                                clearMessages()
                            }}
                        >
                            Организация
                        </button>
                    )}
                </aside>

                <main className="settings-content">
                    {section ===
                        'PROFILE' && (
                            <section className="settings-section">
                                <div className="settings-section-header">
                                    <h2>
                                        Профиль
                                    </h2>

                                    <p>
                                        Основные данные
                                        вашей учётной записи.
                                    </p>
                                </div>

                                <div className="settings-fields">
                                    <label className="settings-field">
                                    <span>
                                        Имя
                                    </span>

                                        <input
                                            type="text"
                                            value={
                                                firstName
                                            }
                                            onChange={event =>
                                                setFirstName(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="given-name"
                                        />
                                    </label>

                                    <label className="settings-field">
                                    <span>
                                        Фамилия
                                    </span>

                                        <input
                                            type="text"
                                            value={
                                                lastName
                                            }
                                            onChange={event =>
                                                setLastName(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="family-name"
                                        />
                                    </label>

                                    <label className="settings-field">
                                    <span>
                                        Username
                                    </span>

                                        <input
                                            type="text"
                                            value={
                                                username
                                            }
                                            onChange={event =>
                                                setUsername(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="username"
                                        />
                                    </label>

                                    <label className="settings-field">
                                    <span>
                                        Email
                                    </span>

                                        <input
                                            type="email"
                                            value={
                                                email
                                            }
                                            onChange={event =>
                                                setEmail(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="email"
                                        />
                                    </label>

                                    <label className="settings-field settings-field-full">
                                    <span>
                                        Телефон
                                    </span>

                                        <input
                                            type="tel"
                                            value={
                                                phone
                                            }
                                            onChange={event =>
                                                setPhone(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="tel"
                                        />
                                    </label>
                                </div>

                                {error && (
                                    <div className="settings-error">
                                        {error}
                                    </div>
                                )}

                                {success && (
                                    <div className="settings-success">
                                        {success}
                                    </div>
                                )}

                                <div className="settings-actions">
                                    <button
                                        type="button"
                                        className="settings-button settings-button-primary"
                                        onClick={
                                            handleProfileSave
                                        }
                                        disabled={
                                            saving
                                        }
                                    >
                                        {saving
                                            ? 'Сохранение…'
                                            : 'Сохранить'}
                                    </button>
                                </div>
                            </section>
                        )}

                    {section ===
                        'SECURITY' && (
                            <section className="settings-section">
                                <div className="settings-section-header">
                                    <h2>
                                        Безопасность
                                    </h2>

                                    <p>
                                        Изменение пароля
                                        вашей учётной записи.
                                    </p>
                                </div>

                                <div className="settings-security-fields">
                                    <label className="settings-field settings-field-full">
                                    <span>
                                        Текущий пароль
                                    </span>

                                        <input
                                            type="password"
                                            value={
                                                currentPassword
                                            }
                                            onChange={event =>
                                                setCurrentPassword(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="current-password"
                                        />
                                    </label>

                                    <label className="settings-field settings-field-full">
                                    <span>
                                        Новый пароль
                                    </span>

                                        <input
                                            type="password"
                                            value={
                                                newPassword
                                            }
                                            onChange={event =>
                                                setNewPassword(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="new-password"
                                        />
                                    </label>

                                    <label className="settings-field settings-field-full">
                                    <span>
                                        Подтверждение
                                        нового пароля
                                    </span>

                                        <input
                                            type="password"
                                            value={
                                                confirmPassword
                                            }
                                            onChange={event =>
                                                setConfirmPassword(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                            autoComplete="new-password"
                                        />
                                    </label>
                                </div>

                                {error && (
                                    <div className="settings-error">
                                        {error}
                                    </div>
                                )}

                                {success && (
                                    <div className="settings-success">
                                        {success}
                                    </div>
                                )}

                                <div className="settings-actions">
                                    <button
                                        type="button"
                                        className="settings-button settings-button-primary"
                                        onClick={
                                            handlePasswordChange
                                        }
                                        disabled={
                                            saving
                                        }
                                    >
                                        {saving
                                            ? 'Сохранение…'
                                            : 'Изменить пароль'}
                                    </button>
                                </div>
                            </section>
                        )}

                    {section ===
                        'ORGANIZATION' &&
                        isSuperAdmin && (
                            <section className="settings-section">
                                <div className="settings-section-header">
                                    <h2>
                                        Организация
                                    </h2>

                                    <p>
                                        Основные параметры
                                        организации.
                                    </p>
                                </div>

                                <div className="settings-fields">
                                    <label className="settings-field settings-field-full">
                                    <span>
                                        Название
                                    </span>

                                        <input
                                            type="text"
                                            value={
                                                organizationName
                                            }
                                            onChange={event =>
                                                setOrganizationName(
                                                    event
                                                        .target
                                                        .value,
                                                )
                                            }
                                            disabled={
                                                saving
                                            }
                                        />
                                    </label>
                                </div>

                                {error && (
                                    <div className="settings-error">
                                        {error}
                                    </div>
                                )}

                                {success && (
                                    <div className="settings-success">
                                        {success}
                                    </div>
                                )}

                                <div className="settings-actions">
                                    <button
                                        type="button"
                                        className="settings-button settings-button-primary"
                                        onClick={
                                            handleOrganizationSave
                                        }
                                        disabled={
                                            saving
                                        }
                                    >
                                        {saving
                                            ? 'Сохранение…'
                                            : 'Сохранить'}
                                    </button>
                                </div>
                            </section>
                        )}
                </main>
            </div>
        </div>
    )
}

export default SettingsPage