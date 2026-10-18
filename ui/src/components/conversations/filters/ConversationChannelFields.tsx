import { useState, type Dispatch, type SetStateAction } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { ConversationFilterDraft } from './conversationFilterModel'

export default function ConversationChannelFields({ draft, setDraft, channels }: {
    draft: ConversationFilterDraft; setDraft: Dispatch<SetStateAction<ConversationFilterDraft>>; channels: ChannelDto[]
}) {
    const [query, setQuery] = useState('')
    const options = [...new Map(channels.filter(channel => channel.account).map(channel => [channel.account!.id, channel])).values()]
    const visible = options.filter(channel => [channel.name, channel.type, channel.account?.displayName,
        channel.account?.username, channel.account?.phone, channel.account?.externalId]
        .some(value => value?.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())))
    return <fieldset className="conversation-filter-channel-field">
        <legend>Каналы</legend>
        <input className="ui-input" aria-label="Поиск каналов" placeholder="Название, username или телефон канала"
            value={query} onChange={event => setQuery(event.target.value)} />
        <div className="conversation-filter-options" role="group" aria-label="Каналы">
            {visible.map(channel => <label key={channel.account!.id}>
                <input type="checkbox" checked={draft.channelAccountIds.includes(channel.account!.id)} onChange={() => setDraft(current => ({
                    ...current,
                    channelAccountIds: current.channelAccountIds.includes(channel.account!.id)
                        ? current.channelAccountIds.filter(id => id !== channel.account!.id)
                        : [...current.channelAccountIds, channel.account!.id],
                    clientIds: [], clientAccountIds: [],
                }))} />
                <span>{channel.name || channel.account!.displayName || channel.account!.username || channel.account!.id}</span>
            </label>)}
            {!visible.length && <small>Каналы не найдены.</small>}
        </div>
        <small>Выбрано: {draft.channelAccountIds.length}. Пустой выбор — любые каналы.</small>
        {!!draft.channelAccountIds.length && <button type="button" className="ui-button ui-button-secondary"
            onClick={() => setDraft(current => ({ ...current, channelAccountIds: [], clientIds: [], clientAccountIds: [] }))}>Очистить каналы</button>}
    </fieldset>
}
