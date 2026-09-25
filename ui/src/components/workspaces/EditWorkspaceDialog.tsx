import {
    useEffect,
    useState,
} from 'react'

import {
    updateWorkspace,
} from '../../api/workspaceApi'

import type {
    WorkspaceDto,
} from '../../auth/types'

interface EditWorkspaceDialogProps {
    open: boolean
    workspace: WorkspaceDto
    onClose: () => void
    onUpdated: (
        workspace: WorkspaceDto,
    ) => void
}

export function EditWorkspaceDialog({
                                        open,
                                        workspace,
                                        onClose,
                                        onUpdated,
                                    }: EditWorkspaceDialogProps) {
    const [name, setName] =
        useState(workspace.name)

    const [saving, setSaving] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (open) {
            setName(workspace.name)
            setError(null)
        }
    }, [
        open,
        workspace.name,
    ])

    async function handleSubmit(
        event: React.FormEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        const trimmedName =
            name.trim()

        if (!trimmedName) {
            setError(
                'Введите название пространства.',
            )
            return
        }

        setSaving(true)
        setError(null)

        try {
            const updated =
                await updateWorkspace(
                    workspace.id,
                    {
                        name: trimmedName,
                    },
                )

            onUpdated(updated)
            onClose()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось изменить пространство',
            )
        } finally {
            setSaving(false)
        }
    }

    if (!open) {
        return null
    }

    return (
        <div className="workspace-modal-overlay">
            <div className="workspace-modal">
                <div className="workspace-modal-header">
                    <div>
                        <h2>
                            Изменить пространство
                        </h2>

                        <p className="workspace-modal-subtitle">
                            Изменение названия рабочего
                            пространства.
                        </p>
                    </div>

                    <button
                        type="button"
                        className="workspace-modal-close"
                        onClick={onClose}
                        disabled={saving}
                        aria-label="Закрыть"
                    >
                        ×
                    </button>
                </div>

                <form
                    className="workspace-form"
                    onSubmit={
                        handleSubmit
                    }
                >
                    <label>
                        <span>
                            Название
                        </span>

                        <input
                            type="text"
                            className="ui-input"
                            value={name}
                            onChange={event =>
                                setName(
                                    event.target
                                        .value,
                                )
                            }
                            autoFocus
                            disabled={saving}
                        />
                    </label>

                    {error && (
                        <div className="workspace-form-error">
                            {error}
                        </div>
                    )}

                    <div className="workspace-modal-actions">
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
                                ? 'Сохранение…'
                                : 'Сохранить'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}