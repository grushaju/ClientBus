import { useState } from 'react'

export default function SaveConversationFilterForm({ onSave }: { onSave: (name: string) => Promise<void> }) {
    const [name, setName] = useState('')
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)
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
        <form className="conversation-list-group-form" onSubmit={event => { event.preventDefault(); void save() }}>
            <input className="ui-input" type="text" value={name}
                onChange={event => { setName(event.target.value); setError(null) }}
                placeholder="Название фильтра" maxLength={100} autoFocus disabled={saving} />
            <button type="submit" className="ui-button ui-button-primary" disabled={saving || !name.trim()}>
                {saving ? 'Сохранение…' : 'Сохранить'}
            </button>
            {error && <div className="ui-form-error">{error}</div>}
        </form>
    )
}
