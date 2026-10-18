import { useState } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { SavedConversationFiltersModel } from './useSavedConversationFilters'

interface Props {
    model: SavedConversationFiltersModel; selectedId: string | null
    onSelect: (filter: SavedConversationFilterDto) => void; onCreate: () => void; isSuperAdmin: boolean
}
export default function SavedConversationFilters({ model, selectedId, onSelect, onCreate, isSuperAdmin }: Props) {
    const [query, setQuery] = useState('')
    const [tab, setTab] = useState<'private' | 'system'>(isSuperAdmin ? 'system' : 'private')
    const sections = [
        { title: 'Системные фильтры', icon: 'system', filters: model.systemFilters },
        ...(!isSuperAdmin ? [{ title: 'Мои фильтры', icon: 'private', filters: model.privateFilters }] : []),
    ].filter(section => section.icon === tab)
    return <section className="conversation-list-saved-filters" aria-label="Каталог фильтров">
        <div className="inbox-navigation-section-head"><h3>Фильтры</h3>
            <button type="button" className="ui-button ui-button-primary" onClick={onCreate}>+ Фильтр</button>
        </div>
        <input className="ui-input" aria-label="Поиск фильтров" placeholder="Поиск по имени" value={query} onChange={event => setQuery(event.target.value)} />
        {model.error && <div className="ui-form-error" role="alert">{model.error}
            <button type="button" className="ui-button ui-button-secondary" onClick={model.reload}>Повторить загрузку фильтров</button>
        </div>}
<div className="inbox-filter-tabs" role="group" aria-label="Тип фильтров">
            {!isSuperAdmin && <button type="button" aria-pressed={tab === 'private'} onClick={() => setTab('private')}>Мои</button>}
            <button type="button" aria-pressed={tab === 'system'} onClick={() => setTab('system')}>Системные</button>
        </div>
        <div className="inbox-filter-catalogue-scroll">
            {sections.map(section => {
                const visible = section.filters.filter(filter => filter.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
                return <section key={section.icon} className="conversation-list-saved-filter-section" aria-label={section.title}>
                    
                    {visible.map(filter => <button key={filter.id} type="button"
                        className={selectedId === filter.id ? 'conversation-list-saved-filter active' : 'conversation-list-saved-filter'}
                        aria-pressed={selectedId === filter.id} title={filter.name} onClick={() => onSelect(filter)}>
                        <img src={`/assets/filter/${section.icon}.svg`} alt="" aria-hidden="true" /><span>{filter.name}</span>
                    </button>)}
                    {!visible.length && <small className="inbox-navigation-hint">{model.loading ? 'Загрузка фильтров…' : model.error ? 'Не все фильтры загружены.' : query ? 'Фильтры не найдены.' : 'Сохранённых фильтров пока нет.'}</small>}
                </section>
            })}
        </div>
    </section>
}
