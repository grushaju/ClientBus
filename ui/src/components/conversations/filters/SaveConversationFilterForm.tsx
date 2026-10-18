import { useState } from 'react'

export default function SaveConversationFilterForm({ onSave, initialName = '', saveLabel = 'Сохранить', onCancel, onDelete, disabled = false, requiresChanges = false, conditionsChanged = false }: { onSave: (name: string) => Promise<void>; initialName?: string; saveLabel?: string; onCancel?: () => void; onDelete?: () => void; disabled?: boolean; requiresChanges?: boolean; conditionsChanged?: boolean }) {
    const [name, setName] = useState(initialName)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const canSave = !saving && !disabled && !!name.trim()
        && (!requiresChanges || conditionsChanged || name.trim() !== initialName.trim())
    const save = async () => {
        const trimmed = name.trim()
        if (!trimmed || trimmed.length > 100) {
            setError(!trimmed ? 'Введите название фильтра.' : 'Название фильтра не должно превышать 100 символов.')
            return
        }
        setSaving(true)
        setError(null)
        try {
            await onSave(trimmed)
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Не удалось сохранить фильтр.')
        } finally {
            setSaving(false)
        }
    }
    return (
        <form className="conversation-list-group-form inbox-filter-save-form" onSubmit={event => { event.preventDefault(); if (canSave) void save() }}>
            <div className="conversation-filter-save-name">
            <input className="ui-input" type="text" value={name}
                onChange={event => { setName(event.target.value); setError(null) }}
                placeholder="Название фильтра" aria-label="Название фильтра" maxLength={100} disabled={saving || disabled} />
            {onDelete && <button type="button" className="ui-button ui-button-danger" disabled={saving || disabled} onClick={onDelete}>Удалить</button>}</div>
            <div className="conversation-list-group-actions"><button type="submit" className="ui-button ui-button-primary" disabled={!canSave}>
                {saving ? 'Сохранение…' : saveLabel}
            </button>
            {onCancel && <button type="button" className="ui-button ui-button-secondary" disabled={saving || disabled} onClick={() => { setName(initialName); setError(null); onCancel() }}>Отмена</button>}</div>
            {error && <div className="ui-form-error">{error}</div>}
        </form>
    )
}
