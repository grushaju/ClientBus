import type { Dispatch, SetStateAction } from 'react'
import type { ChannelDto } from '../../../api/types/channel'
import type { ConversationFilterDraft } from './conversationFilterModel'
import { toLocalDateInput } from './filterDates'
import ConversationClientFields from './ConversationClientFields'
import ConversationChannelFields from './ConversationChannelFields'

interface Props {
    filterDraft: ConversationFilterDraft
    setFilterDraft: Dispatch<SetStateAction<ConversationFilterDraft>>
    channels: ChannelDto[]
    workspaceId: string
}

export default function ConversationFilterFields({ filterDraft, setFilterDraft, channels, workspaceId }: Props) {
    return (
        <div className="conversation-filter-conditions">
            <ConversationChannelFields draft={filterDraft} setDraft={setFilterDraft} channels={channels} />

            <label>
                Связь с клиентом
                <select
                    className="ui-input"
                    value={filterDraft.clientLink}
                    aria-label="Связь с клиентом"
                    onChange={event =>
                        setFilterDraft(current => ({
                            ...current,
                            clientIds: event.target.value === 'WITHOUT_CLIENT' ? [] : current.clientIds,
                            clientAccountIds: [],
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

            <ConversationClientFields draft={filterDraft} setDraft={setFilterDraft} workspaceId={workspaceId} />
            {filterDraft.clientAccountIds.length > 0 && <div className="conversation-filter-account-restriction">
                <small>Выбрано аккаунтов: {filterDraft.clientAccountIds.length}</small>
                <button type="button" className="ui-button ui-button-secondary"
                    onClick={() => setFilterDraft(current => ({ ...current, clientAccountIds: [] }))}>
                    Снять ограничение по аккаунтам
                </button>
            </div>}
        </div>
    )
}
