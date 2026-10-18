import { useEffect, useState, type SetStateAction } from 'react'
import type { SavedConversationFilterDto } from '../../../api/types/savedConversationFilter'
import type { InboxSelection } from '../list/inboxSelection'
import { appliedCriteriaFromDefinition, createEmptyCriteria, criteriaFromDefinition, criteriaToApply, validateCriteria, type ConversationFilterDraft } from './conversationFilterModel'

interface FilterState {
    revision: number
    selectionKey: string
    draft: ConversationFilterDraft
    applied: ConversationFilterDraft
    selectedId: string | null
    error: string | null
}

function syncSelection(state: FilterState, selectionKey: string): FilterState {
    if (state.selectionKey === selectionKey) return state
    // Scope navigation preserves the filter and both applied and draft conditions.
    // Invalidate pending saves so a late response cannot override a newer choice.
    return { ...state, selectionKey, revision: state.revision + 1 }
}

export function useConversationFilters(selection: InboxSelection) {
    const selectionKey = JSON.stringify(selection)
    const [state, setState] = useState<FilterState>(() => ({
        revision: 0, selectionKey,
        draft: createEmptyCriteria(), applied: createEmptyCriteria(), selectedId: null, error: null,
    }))
    // Derive the current revision immediately when URL scope changes.
    const current = syncSelection(state, selectionKey)
    useEffect(() => {
        setState(previous => syncSelection(previous, selectionKey))
    }, [selectionKey])

    return {
        draft: current.draft,
        applied: current.applied,
        selectedId: current.selectedId,
        setSort: (sort: ConversationFilterDraft['sort']) => setState(previous => {
            const next = syncSelection(previous, selectionKey)
            return { ...next, revision: next.revision + 1,
                draft: { ...next.draft, sort }, applied: { ...next.applied, sort } }
        }),
        revision: current.revision,
        error: current.error,
        setDraft: (update: SetStateAction<ConversationFilterDraft>) => {
            setState(previous => {
                const next = syncSelection(previous, selectionKey)
                return { ...next, revision: next.revision + 1, draft: typeof update === 'function' ? update(next.draft) : update, error: null }
            })
        },
        markSaved: (id: string, expectedRevision: number, criteria?: ConversationFilterDraft) => setState(previous => previous.revision === expectedRevision
            ? { ...previous, revision: previous.revision + 1, selectedId: id,
                ...(criteria ? { applied: criteria } : {}) } : previous),
        clearSelection: (expectedId?: string) => setState(previous => {
            const next = syncSelection(previous, selectionKey)
            return expectedId !== undefined && next.selectedId !== expectedId ? next
                : { ...next, revision: next.revision + 1, selectedId: null, error: null }
        }),
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
                return { ...next, revision: next.revision + 1, applied: criteriaToApply(next.draft, next.applied), error: null }
            })
            return true
        },
        reset: () => setState(previous => ({
            revision: previous.revision + 1, selectionKey,
            draft: createEmptyCriteria(), applied: createEmptyCriteria(), selectedId: null, error: null,
        })),
        select: (filter: SavedConversationFilterDto) => {
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
                revision: previous.revision + 1, selectionKey,
                draft: criteria, applied, selectedId: filter.id, error: null,
            }))
            return true
        },
    }
}
