import type { ConversationSearchRequest } from '../../../api/types/conversation'
import type { InboxSelection } from '../list/inboxSelection'
import { toFilterInstant, toLocalDateInput } from './filterDates'

export interface ConversationFilterDraft {
    search: string
    channelAccountIds: string[]
    clientAccountIds: string[]
    clientLink: NonNullable<ConversationSearchRequest['clientLink']>
    unread: NonNullable<ConversationSearchRequest['unread']>
    activityFrom: string
    activityTo: string
    sort: NonNullable<ConversationSearchRequest['sort']>
}

export function createEmptyCriteria(): ConversationFilterDraft {
    return {
        search: '', channelAccountIds: [], clientAccountIds: [], clientLink: 'ANY', unread: 'ANY',
        activityFrom: '', activityTo: '', sort: 'LAST_MESSAGE_DESC',
    }
}

export function criteriaFromDefinition(definition: ConversationSearchRequest): ConversationFilterDraft {
    return {
        search: definition.search ?? '',
        channelAccountIds: [...(definition.channelAccountIds ?? [])],
        clientAccountIds: [...(definition.clientAccountIds ?? [])],
        clientLink: definition.clientLink ?? 'ANY',
        unread: definition.unread ?? 'ANY',
        activityFrom: toLocalDateInput(definition.activityFrom ?? ''),
        activityTo: toLocalDateInput(definition.activityTo ?? ''),
        sort: definition.sort ?? 'LAST_MESSAGE_DESC',
    }
}

// The editor shows calendar dates; the applied definition keeps the exact
// server bounds, including its original zone and sub-millisecond precision.
export function appliedCriteriaFromDefinition(definition: ConversationSearchRequest): ConversationFilterDraft {
    return {
        ...criteriaFromDefinition(definition),
        activityFrom: definition.activityFrom ?? '',
        activityTo: definition.activityTo ?? '',
    }
}

export function criteriaToApply(draft: ConversationFilterDraft, applied: ConversationFilterDraft): ConversationFilterDraft {
    const periodUnchanged = draft.activityFrom === toLocalDateInput(applied.activityFrom)
        && draft.activityTo === toLocalDateInput(applied.activityTo)
    return {
        ...draft,
        search: draft.search.trim(),
        ...(periodUnchanged ? { activityFrom: applied.activityFrom, activityTo: applied.activityTo } : {}),
    }
}

export function buildSavedDefinition(selection: InboxSelection, criteria: ConversationFilterDraft): ConversationSearchRequest {
    return {
        view: selection.view,
        groupId: selection.view === 'GROUP' ? selection.groupId : null,
        search: criteria.search.trim() || null,
        channelAccountIds: [...criteria.channelAccountIds],
        clientAccountIds: [...criteria.clientAccountIds],
        clientLink: criteria.clientLink,
        unread: criteria.unread,
        activityFrom: toFilterInstant(criteria.activityFrom),
        activityTo: toFilterInstant(criteria.activityTo, 'end'),
        sort: criteria.sort,
    }
}

export function validateCriteria(criteria: ConversationFilterDraft): string | null {
    try {
        const from = toFilterInstant(criteria.activityFrom)
        const to = toFilterInstant(criteria.activityTo, 'end')
        return from && to && new Date(from).getTime() > new Date(to).getTime()
            ? 'Начало периода не должно быть позже окончания.' : null
    } catch (err) {
        return err instanceof Error ? err.message : 'Укажите корректные даты активности.'
    }
}

export function buildSearchRequest(
    selection: InboxSelection,
    criteria: ConversationFilterDraft,
    page = 0,
    size = 10,
): ConversationSearchRequest {
    return { ...buildSavedDefinition(selection, criteria), page, size }
}
