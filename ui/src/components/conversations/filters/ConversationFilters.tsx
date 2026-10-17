import { useState } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { useConversationFilters } from './useConversationFilters'
import ConversationFilterFields from './ConversationFilterFields'

interface Props {
    model: ReturnType<typeof useConversationFilters>
    channels: ChannelDto[]
    onApply: () => void
    onReset: () => void
}

export default function ConversationFilters({ model, channels, onApply, onReset }: Props) {
    const [showFilterConditions, setShowFilterConditions] = useState(false)
    return (<>
        <button
            type="button"
            className="ui-button ui-button-secondary"
            onClick={() =>
                setShowFilterConditions(value => !value)
            }
            aria-expanded={showFilterConditions}
        >
            {showFilterConditions
                ? 'Скрыть условия ▲'
                : 'Условия фильтра ▼'}
        </button>
        {model.error && <div className="ui-form-error" role="alert">{model.error}</div>}
        {showFilterConditions && (
            <div className="conversation-list-group-form">
                <strong>Условия фильтра</strong>

                <ConversationFilterFields filterDraft={model.draft} setFilterDraft={model.setDraft} channels={channels} />
                <div className="conversation-list-group-actions">
                    <button
                        type="button"
                        className="ui-button ui-button-primary"
                        onClick={onApply}
                    >
                        Применить
                    </button>

                    <button
                        type="button"
                        className="ui-button ui-button-secondary"
                        onClick={onReset}
                    >
                        Сбросить
                    </button>
                </div>
            </div>
        )}
    </>)
}
