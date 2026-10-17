import { useEffect, useState, type SetStateAction } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { InboxSelection } from '../list/inboxSelection'
import { createEmptyCriteria, criteriaFromDefinition, validateCriteria, type ConversationFilterDraft } from './conversationFilterModel'

interface FilterState {
    selectionKey: string
    pendingSelectionKey: string | null
    draft: ConversationFilterDraft
    applied: ConversationFilterDraft
    selectedId: string | null
    error: string | null
}

function withoutHiddenAccounts(criteria: ConversationFilterDraft): ConversationFilterDraft {
    return criteria.clientAccountIds.length ? { ...criteria, clientAccountIds: [] } : criteria
}

function clearSelection(state: FilterState): FilterState {
    return {
        ...state, selectedId: null, pendingSelectionKey: null, error: null,
        draft: withoutHiddenAccounts(state.draft),
        applied: withoutHiddenAccounts(state.applied),
    }
}

function syncSelection(state: FilterState, selectionKey: string): FilterState {
    if (state.selectionKey === selectionKey) return state
    // Applying a saved filter intentionally changes both representation and
    // criteria. Every other URL change, including POP, leaves the saved view.
    const next = state.pendingSelectionKey === selectionKey ? state : clearSelection(state)
    return { ...next, selectionKey, pendingSelectionKey: null }
}

export function useConversationFilters(selection: InboxSelection) {
    const selectionKey = JSON.stringify(selection)
    const [state, setState] = useState<FilterState>(() => ({
        selectionKey, pendingSelectionKey: null,
        draft: createEmptyCriteria(), applied: createEmptyCriteria(), selectedId: null, error: null,
    }))
    // Derive the correct criteria in the very first render of a changed URL,
    // before useInboxConversations can issue a request with old restrictions.
    const current = syncSelection(state, selectionKey)
    useEffect(() => {
        setState(previous => syncSelection(previous, selectionKey))
    }, [selectionKey])

    return {
        draft: current.draft,
        applied: current.applied,
        selectedId: current.selectedId,
        error: current.error,
        setDraft: (update: SetStateAction<ConversationFilterDraft>) => {
            setState(previous => {
                const next = syncSelection(previous, selectionKey)
                return { ...next, draft: typeof update === 'function' ? update(next.draft) : update, error: null }
            })
        },
        markSaved: (id: string) => setState(previous => ({ ...syncSelection(previous, selectionKey), selectedId: id })),
        clearSelection: () => setState(previous => clearSelection(syncSelection(previous, selectionKey))),
        apply: () => {
            const error = validateCriteria(current.draft)
            if (error) {
                setState(previous => ({ ...syncSelection(previous, selectionKey), error }))
                return false
            }
            setState(previous => {
                const next = syncSelection(previous, selectionKey)
                return { ...next, applied: { ...next.draft, search: next.draft.search.trim() }, selectedId: null, error: null }
            })
            return true
        },
        reset: () => setState({
            selectionKey, pendingSelectionKey: null,
            draft: createEmptyCriteria(), applied: createEmptyCriteria(), selectedId: null, error: null,
        }),
        select: (filter: SavedConversationFilterDto, target: InboxSelection) => {
            const criteria = criteriaFromDefinition(filter.filterDefinition)
            const error = validateCriteria(criteria)
            if (error) {
                setState(previous => ({ ...syncSelection(previous, selectionKey), draft: criteria, error }))
                return false
            }
            setState({
                selectionKey, pendingSelectionKey: JSON.stringify(target),
                draft: criteria, applied: criteriaFromDefinition(filter.filterDefinition), selectedId: filter.id, error: null,
            })
            return true
        },
    }
}
