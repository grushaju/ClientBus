import { useEffect, useRef, useState } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { SavedConversationFiltersModel } from './useSavedConversationFilters'
import SaveConversationFilterForm from './SaveConversationFilterForm'

interface Props {
    model: SavedConversationFiltersModel
    selectedId: string | null
    onSelect: (filter: SavedConversationFilterDto) => void
    onSave: (name: string) => Promise<void>
    isSuperAdmin: boolean
}

export default function SavedConversationFilters({ model, selectedId, onSelect, onSave, isSuperAdmin }: Props) {
    const [showSave, setShowSave] = useState(false)
    const [saving, setSaving] = useState(false)
    const mounted = useRef(false)
    useEffect(() => {
        mounted.current = true
        return () => { mounted.current = false }
    }, [])
    const sections = [
        ...(!isSuperAdmin ? [{ title: 'Мои фильтры', icon: 'private', filters: model.privateFilters, canSave: true }] : []),
        { title: 'Системные фильтры', icon: 'system', filters: model.systemFilters, canSave: isSuperAdmin },
    ]
    return (
        <div className="conversation-list-saved-filters">
            {model.error && <div className="ui-form-error" role="alert">
                {model.error}
                <button type="button" className="ui-button ui-button-secondary" onClick={model.reload}>
                    Повторить загрузку фильтров
                </button>
            </div>}
            {sections.map(section => (
                <section key={section.icon} className="conversation-list-saved-filter-section" aria-label={section.title}>
                    <div className="inbox-navigation-section-head">
                        <h3>{section.title}</h3>
                    </div>
                    {section.canSave && <button type="button" className="ui-button ui-button-secondary" disabled={saving}
                        onClick={() => setShowSave(value => !value)} aria-expanded={showSave}>
                        {showSave ? 'Отмена' : '+ Сохранить фильтр'}
                    </button>}
                    {section.canSave && showSave && <>
                        <small className="inbox-navigation-hint">Сохраняются применённые условия списка.</small>
                        <SaveConversationFilterForm onSave={async name => {
                            setSaving(true)
                            try {
                                await onSave(name)
                                if (mounted.current) setShowSave(false)
                            } finally {
                                if (mounted.current) setSaving(false)
                            }
                        }} />
                    </>}
                    {section.filters.length === 0 && <small className="inbox-navigation-hint">
                        {model.loading ? 'Загрузка фильтров…' : model.error ? 'Не все фильтры загружены.' : 'Сохранённых фильтров пока нет.'}
                    </small>}
                    {section.filters.map(filter => (
                        <button key={filter.id} type="button"
                            className={selectedId === filter.id ? 'conversation-list-saved-filter active' : 'conversation-list-saved-filter'}
                            aria-pressed={selectedId === filter.id} title={filter.name}
                            onClick={() => onSelect(filter)}>
                            <img src={`/assets/filter/${section.icon}.svg`} alt="" aria-hidden="true" />
                            <span>{filter.name}</span>
                        </button>
                    ))}
                    {section.icon === 'system' && <small className="inbox-navigation-hint">Общие для текущего Workspace</small>}
                </section>
            ))}
        </div>
    )
}
