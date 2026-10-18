import { useId, type ReactNode, type RefObject } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { useConversationFilters } from './useConversationFilters'
import ConversationFilterFields from './ConversationFilterFields'
import ConversationSortSelect from './ConversationSortSelect'
import { criteriaToApply } from './conversationFilterModel'


interface Props {
    model: ReturnType<typeof useConversationFilters>
    channels: ChannelDto[]
    onApply: () => void
    onReset: () => void
    searchInputRef: RefObject<HTMLInputElement>
    workspaceId: string
    area: 'conditions' | 'list'
    filterName?: string
    filterIcon?: string
    management?: ReactNode
}

export default function ConversationFilters({ model, channels, onApply, onReset, searchInputRef, workspaceId, area, filterName = 'Без фильтра', filterIcon = 'nofilter.svg', management }: Props) {
    const panelId = useId()
    const { applied, draft } = model
    const dirty = JSON.stringify(criteriaToApply(draft, applied))
        !== JSON.stringify({ ...applied, search: applied.search.trim() })
    if (area === 'conditions') return <section className="inbox-filter-editor" aria-label="Редактор фильтра">
        <div className="inbox-filter-editor-heading"><img src={`/assets/filter/${filterIcon}`} alt="" aria-hidden="true" /><strong>{filterName}</strong></div>
        <div className="inbox-filter-editor-scroll">
            <form id={panelId} className="conversation-filter-conditions-form" onSubmit={event => { event.preventDefault(); onApply() }}>
                <ConversationFilterFields filterDraft={draft} setFilterDraft={model.setDraft} channels={channels} workspaceId={workspaceId} />
                {model.error && <div className="ui-form-error" role="alert">{model.error}</div>}
            </form>
        </div>
        <div className="inbox-filter-save-slot">{management}</div>
        <div className="conversation-filter-actions">
            <button type="submit" form={panelId} className="ui-button ui-button-primary" onClick={event => { event.preventDefault(); onApply() }}>Применить</button>
            <button type="button" className="ui-button ui-button-secondary" onClick={onReset}>Сбросить</button>
        </div>

    </section>
    return (
        <form className="conversation-filters" onSubmit={event => { event.preventDefault(); onApply() }}>
            <input ref={searchInputRef} className="ui-input" type="search" value={draft.search}
                onChange={event => model.setDraft(current => ({ ...current, search: event.target.value }))}
                placeholder="Поиск по диалогам" aria-label="Поиск по диалогам" />
            <div className="conversation-filter-toolbar">
                <ConversationSortSelect value={draft.sort}
                    onChange={model.setSort} />
            </div>

            {dirty && <small className="conversation-filter-hint">Есть неприменённые изменения</small>}

        </form>
    )
}
