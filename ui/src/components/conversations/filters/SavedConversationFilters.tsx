import { useEffect, useRef, useState } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { SavedConversationFiltersModel } from './useSavedConversationFilters'
import SaveConversationFilterForm from './SaveConversationFilterForm'

interface Props {
    model: SavedConversationFiltersModel
    selectedId: string | null
    onSelect: (filter: SavedConversationFilterDto) => void
    onSave: (name: string) => Promise<void>
}

export default function SavedConversationFilters({ model, selectedId, onSelect, onSave }: Props) {
    const [showSave, setShowSave] = useState(false)
    const [saving, setSaving] = useState(false)
    const mounted = useRef(false)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    const sections = [
        { title: 'Общие фильтры', icon: 'system', filters: model.systemFilters },
        { title: 'Мои фильтры', icon: 'private', filters: model.privateFilters },
    ]
    return (
        <div className="conversation-list-saved-filters">
            <button type="button" className="ui-button ui-button-secondary" disabled={saving} onClick={() => setShowSave(value => !value)}>
                {showSave ? 'Отмена' : '+ Сохранить фильтр'}
            </button>
            {showSave && <SaveConversationFilterForm onSave={async name => {
                setSaving(true)
                try {
                    await onSave(name)
                    if (mounted.current) setShowSave(false)
                } finally {
                    if (mounted.current) setSaving(false)
                }
            }} />}
            {model.error && <div className="conversation-list-state conversation-list-error">{model.error}</div>}
            {sections.filter(section => section.filters.length > 0).map(section => (
                <div key={section.icon} className="conversation-list-saved-filter-section">
                    <span className="conversation-list-saved-filter-title">{section.title}</span>
                    {section.filters.map(filter => (
                        <button key={filter.id} type="button"
                            className={selectedId === filter.id ? 'conversation-list-saved-filter active' : 'conversation-list-saved-filter'}
                            onClick={() => onSelect(filter)}>
                            <img src={`/assets/filter/${section.icon}.svg`} alt="" aria-hidden="true" />
                            <span>{filter.name}</span>
                        </button>
                    ))}
                </div>
            ))}
        </div>
    )
}
