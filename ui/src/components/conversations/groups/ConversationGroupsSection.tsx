import { useEffect, useId, useRef, useState } from 'react'
import type { ConversationGroupsModel } from './useConversationGroups'
import ConversationGroupForm from './ConversationGroupForm'
import IconOptionPicker from '../IconOptionPicker'
import { resolveGroupIcon } from './groupIcons'

type Editor = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; groupId: string }
interface Props {
    model: ConversationGroupsModel
    onDeleted: (id: string) => void
    onActionError: (message: string | null) => void
}

export default function ConversationGroupsSection({ model, onDeleted, onActionError }: Props) {
    const [managedId, setManagedId] = useState<string | null>(null)
    const [editor, setEditor] = useState<Editor>({ mode: 'closed' })
    const [busy, setBusy] = useState(false)
    const [expanded, setExpanded] = useState(true)
    const panelId = useId()
    const mounted = useRef(false)
    const selected = model.groups.find(group => group.id === managedId)
    const edited = editor.mode === 'edit' ? model.groups.find(group => group.id === editor.groupId) : undefined
    useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
    const save = async (id: string | null, name: string, icon: string) => {
        setBusy(true)
        onActionError(null)
        try {
            const saved = id ? await model.rename(id, name, icon) : await model.create(name, icon)
            if (mounted.current) { setManagedId(saved.id); setEditor({ mode: 'closed' }) }
        } catch (err) {
            if (mounted.current) onActionError(`Не удалось сохранить группу «${name}»: ${err instanceof Error ? err.message : 'ошибка сервера.'}`)
        } finally { if (mounted.current) setBusy(false) }
    }
    const remove = async () => {
        if (!selected || busy || !window.confirm(`Удалить группу «${selected.name}»? Диалоги останутся без изменений.`)) return
        setBusy(true); onActionError(null)
        try {
            await model.remove(selected.id)
            if (mounted.current) { setEditor({ mode: 'closed' }); setManagedId(null); onDeleted(selected.id) }
        } catch (err) {
            if (mounted.current) onActionError(`Не удалось удалить группу «${selected.name}»: ${err instanceof Error ? err.message : 'ошибка сервера.'}`)
        } finally { if (mounted.current) setBusy(false) }
    }
    return <section className="conversation-list-groups" aria-label="Управление группами">
        <div className="inbox-navigation-section-head"><h3><button type="button" className="inbox-section-toggle"
            aria-expanded={expanded} aria-controls={panelId} onClick={() => setExpanded(current => !current)}>
            Группы <span aria-hidden="true">{expanded ? '▴' : '▾'}</span>
        </button></h3>
            <button type="button" className="ui-button ui-button-primary" disabled={busy}
                onClick={() => { onActionError(null); setExpanded(true); setEditor(editor.mode === 'create' ? { mode: 'closed' } : { mode: 'create' }) }}>
                + Группа
            </button>
        </div>
        <div id={panelId} className="inbox-groups-panel" hidden={!expanded}>
        <IconOptionPicker label="Управлять группой" placeholder={model.groups.length ? 'Выбрать группу' : 'Нет групп'} value={selected?.id ?? null}
            disabled={busy || !model.groups.length} searchable={false}
            options={model.groups.map(group => ({ value: group.id, label: group.name, icon: `/assets/group/${resolveGroupIcon(group.icon)}` }))}
            onChange={id => { setManagedId(id); setEditor({ mode: 'closed' }); onActionError(null) }} />
        {editor.mode === 'closed' && <div className="conversation-list-group-actions">
            <button type="button" className="ui-button ui-button-secondary" disabled={!selected || busy}
                onClick={() => { onActionError(null); if (selected) setEditor({ mode: 'edit', groupId: selected.id }) }}>Изменить</button>
            <button type="button" className="ui-button ui-button-danger" disabled={!selected || busy} onClick={() => void remove()}>Удалить</button>
        </div>}
        <div className="inbox-group-editor-slot">
        {editor.mode === 'create' && <ConversationGroupForm key="create" editing={false} onCancel={() => setEditor({ mode: 'closed' })}
            onSubmit={(name, icon) => save(null, name, icon)} />}
        {edited && <ConversationGroupForm key={edited.id} editing initialName={edited.name} initialIcon={edited.icon}
            onCancel={() => setEditor({ mode: 'closed' })} onSubmit={(name, icon) => save(edited.id, name, icon)} />}
        </div>
        {model.error && <div className="ui-form-error" role="alert">{model.error}</div>}
        </div>
    </section>
}
