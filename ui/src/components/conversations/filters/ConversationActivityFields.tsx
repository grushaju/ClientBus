import type { Dispatch, SetStateAction } from 'react'
import type { ConversationFilterDraft } from './conversationFilterModel'
import { activityPreset, toLocalDateInput } from './filterDates'

interface Props {
    draft: ConversationFilterDraft
    setDraft: Dispatch<SetStateAction<ConversationFilterDraft>>
}

export default function ConversationActivityFields({ draft, setDraft }: Props) {
    return <div className="conversation-filter-activity">
        <div className="conversation-filter-periods" role="group" aria-label="Быстрый выбор периода активности">
            {([
                [1, 'Сегодня'], [7, 'Последние 7 дней'], [30, 'Последние 30 дней'],
            ] as const).map(([days, label]) => <button key={days} type="button"
                className="ui-button ui-button-sm ui-button-secondary"
                onClick={() => {
                    // Resolve when clicked. Save exact bounds using the existing API,
                    // including when replacing a saved partial day with a full day.
                    const period = activityPreset(days)
                    setDraft(current => ({ ...current, ...period }))
                }}>
                {label}
            </button>)}
        </div>
        <div className="conversation-filter-date-range">
            <label>
                Активность с
                <input className="ui-input" type="date" value={toLocalDateInput(draft.activityFrom)}
                    onChange={event => setDraft(current => ({ ...current, activityFrom: event.target.value }))} />
            </label>
            <label>
                Активность по
                <input className="ui-input" type="date" value={toLocalDateInput(draft.activityTo)}
                    onChange={event => setDraft(current => ({ ...current, activityTo: event.target.value }))} />
            </label>
        </div>
    </div>
}
