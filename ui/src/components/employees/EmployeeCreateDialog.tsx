import {
    useState,
} from 'react'

import {
    createEmployee,
} from '../../api/employeeApi'

import type {
    EmployeeDto,
} from '../../auth/types'

interface EmployeeCreateDialogProps {
    organizationId: string
    onClose: () => void
    onCreated: (
        employee: EmployeeDto,
    ) => void
}

function EmployeeCreateDialog({
                                  organizationId,
                                  onClose,
                                  onCreated,
                              }: EmployeeCreateDialogProps) {
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
        password,
        setPassword,
    ] = useState('')

    const [
        saving,
        setSaving,
    ] = useState(false)

    const [
        error,
        setError,
    ] = useState<string | null>(null)

    async function handleSubmit(
        event: React.FormEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        const trimmedFirstName =
            firstName.trim()

        const trimmedLastName =
            lastName.trim()

        const trimmedUsername =
            username.trim()

        const trimmedEmail =
            email.trim()

        const trimmedPhone =
            phone.trim()

        if (
            !trimmedFirstName ||
            !trimmedLastName ||
            !trimmedUsername ||
            !trimmedEmail ||
            !password
        ) {
            setError(
                'Заполните все обязательные поля.',
            )
            return
        }

        setSaving(true)
        setError(null)

        try {
            const employee =
                await createEmployee({
                    organizationId,
                    username:
                    trimmedUsername,
                    email:
                    trimmedEmail,
                    password,
                    firstName:
                    trimmedFirstName,
                    lastName:
                    trimmedLastName,
                    phone:
                        trimmedPhone ||
                        null,
                })

            onCreated(employee)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать сотрудника',
            )
        } finally {
            setSaving(false)
        }
    }

    return (
        <div className="employee-modal-overlay">
            <div className="employee-modal">
                <div className="employee-modal-header">
                    <div>
                        <h2>
                            Добавить сотрудника
                        </h2>

                        <p>
                            Создание новой учётной
                            записи сотрудника.
                        </p>
                    </div>

                    <button
                        type="button"
                        className="ui-button ui-button-ghost ui-button-sm employee-modal-close"
                        onClick={onClose}
                        disabled={saving}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <form
                    className="employee-modal-content"
                    onSubmit={
                        handleSubmit
                    }
                >
                    <div className="employee-details-fields">
                        <label className="employee-details-field">
                            <span>
                                Имя
                            </span>

                            <input
                                className="ui-input"
                                type="text"
                                value={firstName}
                                onChange={event =>
                                    setFirstName(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                                autoFocus
                            />
                        </label>

                        <label className="employee-details-field">
                            <span>
                                Фамилия
                            </span>

                            <input
                                className="ui-input"
                                type="text"
                                value={lastName}
                                onChange={event =>
                                    setLastName(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                            />
                        </label>

                        <label className="employee-details-field">
                            <span>
                                Username
                            </span>

                            <input
                                className="ui-input"
                                type="text"
                                value={username}
                                onChange={event =>
                                    setUsername(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                                autoComplete="username"
                            />
                        </label>

                        <label className="employee-details-field">
                            <span>
                                Email
                            </span>

                            <input
                                className="ui-input"
                                type="email"
                                value={email}
                                onChange={event =>
                                    setEmail(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                                autoComplete="email"
                            />
                        </label>

                        <label className="employee-details-field">
                            <span>
                                Телефон
                            </span>

                            <input
                                className="ui-input"
                                type="tel"
                                value={phone}
                                onChange={event =>
                                    setPhone(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                                autoComplete="tel"
                            />
                        </label>

                        <label className="employee-details-field">
                            <span>
                                Пароль
                            </span>

                            <input
                                className="ui-input"
                                type="password"
                                value={password}
                                onChange={event =>
                                    setPassword(
                                        event.target
                                            .value,
                                    )
                                }
                                disabled={saving}
                                autoComplete="new-password"
                            />
                        </label>
                    </div>

                    {error && (
                        <div className="employee-details-error">
                            {error}
                        </div>
                    )}

                    <div className="employee-modal-actions">
                        <button
                            type="button"
                            className="ui-button ui-button-secondary"
                            onClick={onClose}
                            disabled={saving}
                        >
                            Отмена
                        </button>

                        <button
                            type="submit"
                            className="ui-button ui-button-primary"
                            disabled={saving}
                        >
                            {saving
                                ? 'Создание…'
                                : 'Создать'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}

export default EmployeeCreateDialog