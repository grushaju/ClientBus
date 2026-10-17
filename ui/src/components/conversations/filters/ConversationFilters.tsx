import { useId, type RefObject } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { useConversationFilters } from './useConversationFilters'
import ConversationFilterFields from './ConversationFilterFields'
import ConversationSortSelect from './ConversationSortSelect'
import { criteriaToApply } from './conversationFilterModel'
import { toLocalDateInput } from './filterDates'

interface Props {
    model: ReturnType<typeof useConversationFilters>
    channels: ChannelDto[]
    onApply: () => void
    onReset: () => void
    open: boolean
    onToggle: () => void
    searchInputRef: RefObject<HTMLInputElement>
}

export default function ConversationFilters({ model, channels, onApply, onReset, open, onToggle, searchInputRef }: Props) {
    const panelId = useId()
    const { applied, draft } = model
    const dirty = JSON.stringify(criteriaToApply(draft, applied))
        !== JSON.stringify({ ...applied, search: applied.search.trim() })
    const summary = [
        applied.search.trim() && `Поиск: ${applied.search.trim()}`,
        applied.channelAccountIds.length > 0 && `Каналы: ${applied.channelAccountIds.length}`,
        applied.clientAccountIds.length > 0 && `Аккаунты: ${applied.clientAccountIds.length}`,
        applied.clientLink === 'WITH_CLIENT' && 'С клиентом',
        applied.clientLink === 'WITHOUT_CLIENT' && 'Без клиента',
        applied.unread === 'HAS_UNREAD' && 'Непрочитанные',
        applied.unread === 'READ_ONLY' && 'Прочитанные',
        applied.activityFrom && `С ${toLocalDateInput(applied.activityFrom)}`,
        applied.activityTo && `По ${toLocalDateInput(applied.activityTo)}`,
    ].filter(Boolean).join(' · ')
    return (
        <form className="conversation-filters" onSubmit={event => { event.preventDefault(); onApply() }}>
            <input ref={searchInputRef} className="ui-input" type="search" value={draft.search}
                onChange={event => model.setDraft(current => ({ ...current, search: event.target.value }))}
                placeholder="Поиск по диалогам" aria-label="Поиск по диалогам" />
            <div className="conversation-filter-toolbar">
                <button type="button" className="ui-button ui-button-secondary" onClick={onToggle}
                    aria-expanded={open} aria-controls={panelId}>
                    {open ? 'Скрыть условия ▲' : 'Условия фильтра ▼'}
                </button>
                <ConversationSortSelect value={draft.sort}
                    onChange={sort => model.setDraft(current => ({ ...current, sort }))} />
            </div>
            {open && <div id={panelId} className="conversation-filter-panel">
                <ConversationFilterFields filterDraft={draft} setFilterDraft={model.setDraft} channels={channels} />
            </div>}
            {model.error && <div className="ui-form-error" role="alert">{model.error}</div>}
            <div className="conversation-filter-actions">
                <button type="submit" className="ui-button ui-button-primary">
                    Применить
                </button>
                <button type="button" className="ui-button ui-button-secondary" onClick={onReset}>Сбросить</button>
            </div>
            {dirty && <small className="conversation-filter-hint">Есть неприменённые изменения</small>}
            {summary && <div className="conversation-filter-summary" title={summary} aria-label="Применённые условия">{summary}</div>}
        </form>
    )
}
