import { useEffect, useState, type SetStateAction } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { InboxSelection } from '../list/inboxSelection'
import { appliedCriteriaFromDefinition, createEmptyCriteria, criteriaFromDefinition, criteriaToApply, validateCriteria, type ConversationFilterDraft } from './conversationFilterModel'

interface FilterState {
    revision: number
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
        ...state, revision: state.revision + 1, selectedId: null, pendingSelectionKey: null, error: null,
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
        revision: 0, selectionKey, pendingSelectionKey: null,
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
        revision: current.revision,
        error: current.error,
        setDraft: (update: SetStateAction<ConversationFilterDraft>) => {
            setState(previous => {
                const next = syncSelection(previous, selectionKey)
                return { ...next, revision: next.revision + 1, draft: typeof update === 'function' ? update(next.draft) : update, error: null }
            })
        },
        markSaved: (id: string, expectedRevision: number) => setState(previous => previous.revision === expectedRevision
            ? { ...previous, revision: previous.revision + 1, selectedId: id } : previous),
        clearSelection: () => setState(previous => clearSelection(syncSelection(previous, selectionKey))),
        apply: () => {
            const error = validateCriteria(criteriaToApply(current.draft, current.applied))
            if (error) {
                setState(previous => {
                    const next = syncSelection(previous, selectionKey)
                    return { ...next, revision: next.revision + 1, error }
                })
                return false
            }
            setState(previous => {
                const next = syncSelection(previous, selectionKey)
                return { ...next, revision: next.revision + 1, applied: criteriaToApply(next.draft, next.applied), selectedId: null, error: null }
            })
            return true
        },
        reset: () => setState(previous => ({
            revision: previous.revision + 1, selectionKey, pendingSelectionKey: null,
            draft: createEmptyCriteria(), applied: createEmptyCriteria(), selectedId: null, error: null,
        })),
        select: (filter: SavedConversationFilterDto, target: InboxSelection) => {
            const criteria = criteriaFromDefinition(filter.filterDefinition)
            const applied = appliedCriteriaFromDefinition(filter.filterDefinition)
            const error = validateCriteria(applied)
            if (error) {
                setState(previous => {
                    const next = syncSelection(previous, selectionKey)
                    return { ...next, revision: next.revision + 1, draft: criteria, error }
                })
                return false
            }
            setState(previous => ({
                revision: previous.revision + 1, selectionKey, pendingSelectionKey: JSON.stringify(target),
                draft: criteria, applied, selectedId: filter.id, error: null,
            }))
            return true
        },
    }
}
