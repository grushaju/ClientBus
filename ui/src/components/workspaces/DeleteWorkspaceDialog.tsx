import {
    useState,
} from 'react'

import type {
    WorkspaceDto,
} from '../../auth/types'

interface DeleteWorkspaceDialogProps {
    open: boolean
    workspace: WorkspaceDto
    onClose: () => void
    onConfirm: () => Promise<void>
}

export function DeleteWorkspaceDialog({
                                          open,
                                          workspace,
                                          onClose,
                                          onConfirm,
                                      }: DeleteWorkspaceDialogProps) {
    const [saving, setSaving] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    if (!open) {
        return null
    }

    async function handleConfirm() {
        setSaving(true)
        setError(null)

        try {
            await onConfirm()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось удалить Workspace',
            )
        } finally {
            setSaving(false)
        }
    }

    return (
        <div className="workspace-modal-overlay">
            <div className="workspace-modal">
                <div className="workspace-modal-header">
                    <h2>
                        Удалить Workspace
                    </h2>

                    <button
                        type="button"
                        className="workspace-modal-close"
                        onClick={onClose}
                        disabled={saving}
                    >
                        ×
                    </button>
                </div>

                <div className="workspace-delete-content">
                    {error && (
                        <div className="workspace-form-error">
                            {error}
                        </div>
                    )}

                    <p>
                        Вы действительно хотите
                        удалить Workspace
                        <strong>
                            {' '}
                            «{workspace.name}»
                        </strong>
                        ?
                    </p>

                    <p>
                        Это действие нельзя отменить.
                    </p>
                </div>

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
                        type="button"
                        className="ui-button ui-button-danger"
                        onClick={
                            handleConfirm
                        }
                        disabled={saving}
                    >
                        {saving
                            ? 'Удаление…'
                            : 'Удалить'}
                    </button>
                </div>
            </div>
        </div>
    )
}