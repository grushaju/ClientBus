import { useEffect, useMemo, useRef } from 'react'
import { abortError, withCancellation } from './requestScope'
import { useUiScope } from './uiScope'
import { bindRequestBudget } from './requestBudget'

/** Covers reads outside SSE (initial loads, manual refresh, polling) and late command results. */
export function useUiLifetime(key: string) {
    const scope = useUiScope()
    const owner = useMemo(() => ({ active: true, epoch: 0, controller: new AbortController() }), [scope, key])
    const currentOwner = useRef(owner); currentOwner.current = owner
    bindRequestBudget(owner.controller.signal, scope?.requestBudget)
    useEffect(() => {
        owner.active = true
        if (owner.controller.signal.aborted) owner.controller = new AbortController()
        bindRequestBudget(owner.controller.signal, scope?.requestBudget)
        return () => { owner.active = false; owner.epoch++; owner.controller.abort() }
    }, [owner])
    return useMemo(() => ({
        capture: () => {
            const epoch = owner.epoch
            return () => owner.active && currentOwner.current === owner && owner.epoch === epoch && (!scope || scope.isCurrent())
        },
        isCurrent: () => owner.active && currentOwner.current === owner && (!scope || scope.isCurrent()),
        reset: () => { owner.epoch++; owner.controller.abort(); owner.controller = new AbortController(); bindRequestBudget(owner.controller.signal, scope?.requestBudget) },
        read: <T,>(action: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal) => {
            if (!owner.active || currentOwner.current !== owner || (scope && !scope.isCurrent())) return Promise.reject<T>(abortError())
            return withCancellation(action, [owner.controller.signal, ...(signal ? [signal] : [])])
        },
    }), [owner])
}
