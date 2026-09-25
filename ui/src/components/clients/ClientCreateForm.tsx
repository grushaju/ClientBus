import {
    FormEvent,
    useState,
} from 'react'

import {
    createClient,
} from '../../api/clientApi'

interface ClientCreateFormProps {
    onCreated: (
        clientId: string,
    ) => void

    onCancel: () => void

    onError: (
        message: string | null,
    ) => void
}

function ClientCreateForm({
                              onCreated,
                              onCancel,
                              onError,
                          }: ClientCreateFormProps) {
    const [firstName, setFirstName] =
        useState('')

    const [lastName, setLastName] =
        useState('')

    const [phone, setPhone] =
        useState('')

    const [creating, setCreating] =
        useState(false)

    const handleSubmit = async (
        event: FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault()

        const normalizedFirstName =
            firstName.trim()

        if (!normalizedFirstName) {
            return
        }

        setCreating(true)
        onError(null)

        try {
            const client =
                await createClient({
                    firstName:
                    normalizedFirstName,

                    lastName:
                        lastName.trim(),

                    phoneList:
                        phone.trim()
                            ? [phone.trim()]
                            : [],
                })

            onCreated(
                client.id,
            )
        } catch (err) {
            onError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать клиента',
            )
        } finally {
            setCreating(false)
        }
    }

    return (
        <form
            className="client-create-form"
            onSubmit={handleSubmit}
        >
            <div className="client-form-fields">
                <label>
                    <span>
                        Имя
                    </span>

                    <input
                        className="ui-input"
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
                        className="ui-input"
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
                        className="ui-input"
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
                    className="ui-button ui-button-secondary"
                    onClick={onCancel}
                    disabled={creating}
                >
                    Отмена
                </button>

                <button
                    type="submit"
                    className="ui-button ui-button-primary"
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
    )
}

export default ClientCreateForm