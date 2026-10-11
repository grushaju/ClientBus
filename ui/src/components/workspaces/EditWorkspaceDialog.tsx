import {
    useEffect,
    useState,
    useRef,
} from 'react'

import {
    clearWorkspaceAvatar,
    uploadWorkspaceAvatar,
    updateWorkspace,
} from '../../api/workspaceApi'

import type {
    WorkspaceDto,
} from '../../auth/types'
import WorkspaceAvatar from "./WorkspaceAvatar";

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

    const avatarInputRef = useRef<HTMLInputElement>(null)

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

    async function handleAvatarSelected(
        event: React.ChangeEvent<HTMLInputElement>,
    ) {
        const file = event.target.files?.[0]
        event.target.value = ''

        if (!file) {
            return
        }

        if (!file.type.startsWith('image/')) {
            setError('Выберите файл изображения.')
            return
        }

        setSaving(true)
        setError(null)

        try {
            const updated = await uploadWorkspaceAvatar(
                workspace.id,
                file,
            )

            onUpdated(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось загрузить аватар рабочего пространства',
            )
        } finally {
            setSaving(false)
        }
    }

    async function handleAvatarClear() {
        setSaving(true)
        setError(null)

        try {
            const updated = await clearWorkspaceAvatar(
                workspace.id,
            )

            onUpdated(updated)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить аватар рабочего пространства',
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
                    <div className="workspace-avatar-editor">
                        <WorkspaceAvatar
                            workspaceId={workspace.id}
                            avatarUrl={workspace.avatarUrl}
                            name={workspace.name}
                            size={80}
                        />

                        <div className="workspace-avatar-editor-controls">
                            <strong>Аватар рабочего пространства</strong>

                            <p>
                                Изображение, которое будет отображаться рядом
                                с названием пространства.
                            </p>

                            <input
                                ref={avatarInputRef}
                                type="file"
                                accept="image/*"
                                hidden
                                disabled={saving}
                                onChange={handleAvatarSelected}
                            />

                            <div className="workspace-avatar-editor-actions">
                                <button
                                    type="button"
                                    className="ui-button ui-button-secondary"
                                    disabled={saving}
                                    onClick={() => avatarInputRef.current?.click()}
                                >
                                    {saving ? 'Сохранение…' : 'Выбрать изображение'}
                                </button>

                                {workspace.avatarUrl && (
                                    <button
                                        type="button"
                                        className="ui-button ui-button-secondary"
                                        disabled={saving}
                                        onClick={handleAvatarClear}
                                    >
                                        Удалить аватар
                                    </button>
                                )}
                            </div>
                        </div>
                    </div>

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