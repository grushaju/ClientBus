import { useEffect, useRef, useState } from 'react'
import type { ConversationGroupsModel } from './useConversationGroups'
import GroupIcon from './GroupIcon'
import ConversationGroupForm from './ConversationGroupForm'
import type { InboxSelection } from '../list/inboxSelection'

type Editor = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; groupId: string }

interface Props {
    model: ConversationGroupsModel
    selection: InboxSelection
    onSelect: (id: string) => void
    onDeleted: (id: string) => void
}

export default function ConversationGroupsSection({ model, selection, onSelect, onDeleted }: Props) {
    const [editor, setEditor] = useState<Editor>({ mode: 'closed' })
    const [deletingId, setDeletingId] = useState<string | null>(null)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const mounted = useRef(false)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    const selectedGroupId = selection.view === 'GROUP' ? selection.groupId : null
    const selectedGroup = model.groups.find(group => group.id === selectedGroupId)
    const editedGroup = editor.mode === 'edit' && editor.groupId === selectedGroupId ? selectedGroup : undefined
    useEffect(() => {
        setEditor(current => current.mode === 'edit' && current.groupId !== selectedGroupId
            ? { mode: 'closed' } : current)
        setError(null)
    }, [selectedGroupId])

    const save = async (id: string | null, name: string, icon: string) => {
        setSaving(true)
        try {
            if (id) await model.rename(id, name, icon)
            else await model.create(name, icon)
            if (mounted.current) setEditor({ mode: 'closed' })
        } finally {
            if (mounted.current) setSaving(false)
        }
    }

    const remove = async () => {
        if (!selectedGroup || deletingId) return
        if (!window.confirm(`Удалить группу «${selectedGroup.name}»? Диалоги останутся без изменений.`)) return
        setDeletingId(selectedGroup.id)
        setError(null)
        try {
            await model.remove(selectedGroup.id)
            if (!mounted.current) return
            setEditor({ mode: 'closed' })
            onDeleted(selectedGroup.id)
        } catch (err) {
            if (mounted.current) setError(err instanceof Error ? err.message : 'Не удалось удалить группу.')
        } finally {
            if (mounted.current) setDeletingId(null)
        }
    }

    return (
        <div className="conversation-list-groups">
            {model.groups.length > 0 && (
                <div className="conversation-list-tabs">
                    {model.groups.map(group => (
                        <button key={group.id} type="button"
                            className={selection.view === 'GROUP' && selection.groupId === group.id ? 'active' : ''}
                            onClick={() => onSelect(group.id)}>
                            <GroupIcon icon={group.icon} /><span>{group.name}</span>
                        </button>
                    ))}
                </div>
            )}
            <div className="conversation-list-group-actions">
                <button type="button" className="ui-button ui-button-secondary" disabled={deletingId !== null || saving}
                    onClick={() => { setError(null); setEditor(editor.mode === 'create' ? { mode: 'closed' } : { mode: 'create' }) }}>
                    {editor.mode === 'create' ? 'Отмена' : '+ Группа'}
                </button>
                {selectedGroup && <>
                    <button type="button" className="ui-button ui-button-secondary" disabled={deletingId !== null || saving}
                        onClick={() => { setError(null); setEditor(editedGroup ? { mode: 'closed' } : { mode: 'edit', groupId: selectedGroup.id }) }}>
                        Переименовать
                    </button>
                    <button type="button" className="ui-button ui-button-secondary" disabled={deletingId !== null || saving} onClick={() => void remove()}>
                        {deletingId === selectedGroup.id ? 'Удаление…' : 'Удалить'}
                    </button>
                </>}
            </div>
            {editor.mode === 'create' && <ConversationGroupForm key="create" editing={false}
                onCancel={() => setEditor({ mode: 'closed' })}
                onSubmit={(name, icon) => save(null, name, icon)} />}
            {editedGroup && <ConversationGroupForm key={editedGroup.id} editing
                initialName={editedGroup.name} initialIcon={editedGroup.icon}
                onCancel={() => setEditor({ mode: 'closed' })}
                onSubmit={(name, icon) => save(editedGroup.id, name, icon)} />}
            {(error || model.error) && <div className="ui-form-error">{error || model.error}</div>}
        </div>
    )
}
