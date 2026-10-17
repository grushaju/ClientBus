import type { Dispatch, SetStateAction } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { ConversationFilterDraft } from './conversationFilterModel'
import { toLocalDateInput } from './filterDates'

interface Props {
    filterDraft: ConversationFilterDraft
    setFilterDraft: Dispatch<SetStateAction<ConversationFilterDraft>>
    channels: ChannelDto[]
}

export default function ConversationFilterFields({ filterDraft, setFilterDraft, channels }: Props) {
    return (
        <div className="conversation-filter-conditions">
            <label>
                Каналы
                <select
                    className="ui-input"
                    multiple
                    value={filterDraft.channelAccountIds}
                    onChange={event =>
                        setFilterDraft(current => ({
                            ...current,
                            channelAccountIds: Array.from(
                                event.target.selectedOptions,
                                option => option.value,
                            ),
                        }))
                    }
                    aria-label="Каналы"
                >
                    {[
                        ...new Map(
                            channels
                                .filter(channel => channel.account)
                                .map(channel => [
                                    channel.account!.id,
                                    {
                                        id: channel.account!.id,
                                        name:
                                            channel.name ||
                                            channel.account!.displayName ||
                                            channel.account!.username ||
                                            channel.account!.id,
                                    },
                                ]),
                        ).values(),
                    ].map(channel => (
                        <option key={channel.id} value={channel.id}>
                            {channel.name}
                        </option>
                    ))}
                </select>
                <small>Для выбора нескольких каналов используйте Ctrl или Cmd.</small>
            </label>

            <label>
                Связь с клиентом
                <select
                    className="ui-input"
                    value={filterDraft.clientLink}
                    aria-label="Связь с клиентом"
                    onChange={event =>
                        setFilterDraft(current => ({
                            ...current,
                            clientLink: event.target.value as
                                ConversationFilterDraft['clientLink'],
                        }))
                    }
                >
                    <option value="ANY">Любая</option>
                    <option value="WITH_CLIENT">С клиентом</option>
                    <option value="WITHOUT_CLIENT">Без клиента</option>
                </select>
            </label>

            <label>
                Сообщения
                <select
                    className="ui-input"
                    value={filterDraft.unread}
                    aria-label="Сообщения"
                    onChange={event =>
                        setFilterDraft(current => ({
                            ...current,
                            unread: event.target.value as
                                ConversationFilterDraft['unread'],
                        }))
                    }
                >
                    <option value="ANY">Все</option>
                    <option value="HAS_UNREAD">Есть непрочитанные</option>
                    <option value="READ_ONLY">Только прочитанные</option>
                </select>
            </label>

            <div className="conversation-filter-date-range">
                <label>
                    Активность с
                    <input
                        className="ui-input"
                        type="date"
                        value={toLocalDateInput(filterDraft.activityFrom)}
                        onChange={event =>
                            setFilterDraft(current => ({
                                ...current,
                                activityFrom: event.target.value,
                            }))
                        }
                    />
                </label>

                <label>
                    Активность по
                    <input
                        className="ui-input"
                        type="date"
                        value={toLocalDateInput(filterDraft.activityTo)}
                        onChange={event =>
                            setFilterDraft(current => ({
                                ...current,
                                activityTo: event.target.value,
                            }))
                        }
                    />
                </label>
            </div>

            {filterDraft.clientAccountIds.length > 0 && <div className="conversation-filter-account-restriction">
                <small>Аккаунтов из сохранённого фильтра: {filterDraft.clientAccountIds.length}</small>
                <button type="button" className="ui-button ui-button-secondary"
                    onClick={() => setFilterDraft(current => ({ ...current, clientAccountIds: [] }))}>
                    Снять ограничение по аккаунтам
                </button>
            </div>}
        </div>
    )
}
