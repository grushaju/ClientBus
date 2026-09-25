import {
    useEffect,
    useState,
} from 'react'

import {
    createWorkspace,
} from '../../api/workspaceApi'

import {
    useAuth,
} from '../../auth/AuthContext'

import type {
    WorkspaceDto,
} from '../../auth/types'

interface CreateWorkspaceDialogProps {
    open: boolean
    onClose: () => void
    onCreated: (
        workspace: WorkspaceDto,
    ) => void
}

export function CreateWorkspaceDialog({
                                          open,
                                          onClose,
                                          onCreated,
                                      }: CreateWorkspaceDialogProps) {
    const {
        currentEmployee,
    } = useAuth()

    const [name, setName] =
        useState('')

    const [saving, setSaving] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    useEffect(() => {
        if (open) {
            setName('')
            setError(null)
        }
    }, [open])

    async function handleSubmit(
        event: React.FormEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!currentEmployee) {
            return
        }

        const trimmedName =
            name.trim()

        if (!trimmedName) {
            setError(
                'Введите название Workspace.',
            )
            return
        }

        setSaving(true)
        setError(null)

        try {
            const workspace =
                await createWorkspace({
                    organizationId:
                    currentEmployee.organizationId,
                    name: trimmedName,
                })

            onCreated(workspace)
            onClose()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось создать Workspace',
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
                    <h2>
                        Добавить Workspace
                    </h2>

                    <button
                        type="button"
                        className="ui-button ui-button-ghost ui-button-sm workspace-modal-close"
                        onClick={onClose}
                        disabled={saving}
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
                    {error && (
                        <div className="workspace-form-error">
                            {error}
                        </div>
                    )}

                    <label>
                        <span>
                            Название
                        </span>

                        <input
                            className="ui-input"
                            type="text"
                            value={name}
                            onChange={event =>
                                setName(
                                    event.target
                                        .value,
                                )
                            }
                            autoFocus
                            disabled={saving}
                            placeholder="Beauty Studio"
                        />
                    </label>

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
                                ? 'Создание…'
                                : 'Создать'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}