import { useState } from 'react'
import GroupIconSelect from './GroupIconSelect'
import { DEFAULT_GROUP_ICON, resolveGroupIcon } from './groupIcons'

interface Props {
    initialName?: string
    initialIcon?: string
    editing: boolean
    onSubmit: (name: string, icon: string) => Promise<void>
    onCancel: () => void
}

export default function ConversationGroupForm({ initialName = '', initialIcon = DEFAULT_GROUP_ICON, editing, onSubmit, onCancel }: Props) {
    const [name, setName] = useState(initialName)
    const [icon, setIcon] = useState(() => resolveGroupIcon(initialIcon))
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const submit = async () => {
        const trimmed = name.trim()
        if (!trimmed || trimmed.length > 100) {
            setError(!trimmed ? 'Введите название группы.' : 'Название группы не должно превышать 100 символов.')
            return
        }
        setSaving(true)
        setError(null)
        try {
            await onSubmit(trimmed, icon)
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Не удалось сохранить группу.')
        } finally {
            setSaving(false)
        }
    }

    return (
        <form className="conversation-list-group-form" onSubmit={event => { event.preventDefault(); void submit() }}>
            <input className="ui-input" type="text" value={name}
                onChange={event => { setName(event.target.value); setError(null) }}
                placeholder="Название группы" maxLength={100} autoFocus disabled={saving} />
            <GroupIconSelect value={icon} onChange={setIcon} disabled={saving} />
            <button type="submit" className="ui-button ui-button-primary" disabled={saving || !name.trim()}>
                {saving ? (editing ? 'Сохранение…' : 'Создание…') : (editing ? 'Сохранить' : 'Создать')}
            </button>
            {editing && <button type="button" className="ui-button ui-button-secondary" disabled={saving} onClick={onCancel}>Отмена</button>}
            {error && <div className="ui-form-error">{error}</div>}
        </form>
    )
}
