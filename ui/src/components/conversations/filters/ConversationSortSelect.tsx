import type { ConversationSort } from '../../../api/types/conversation'

export default function ConversationSortSelect({ value, onChange }: { value: ConversationSort; onChange: (value: ConversationSort) => void }) {
    return (
        <label>
            Сортировка
            <select className="ui-input" aria-label="Сортировка" value={value} onChange={event => onChange(event.target.value as ConversationSort)}>
                <option value="LAST_MESSAGE_DESC">Сначала новые</option>
                <option value="LAST_MESSAGE_ASC">Сначала старые</option>
            </select>
        </label>
    )
}
