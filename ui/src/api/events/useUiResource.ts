import { useCallback, useEffect, useMemo, useRef } from 'react'
import { getApiErrorStatus } from '../apiClient'
import type { UiEventEnvelope } from '../types/uiEvent'
import { useUiInvalidation } from './InvalidationContext'
import { abortError, withCancellation } from './requestScope'
import { useUiScope } from './uiScope'
import { bindRequestBudget } from './requestBudget'

/** Shares initial/manual/SSE reads and owns cancellation for one mounted query. */
export function useUiResource<T>(key: string, enabled: boolean, matches: (event: UiEventEnvelope) => boolean,
    read: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void,
    fail: (error: unknown) => void, reset: () => void, pending?: (value: boolean) => void, visible = true,
    onInvalidated?: (event?: UiEventEnvelope) => void) {
    const scope = useUiScope()
    const owner = useMemo(() => ({ active: true, epoch: 0, denied: false,
        controller: undefined as AbortController | undefined, running: undefined as Promise<void> | undefined }), [scope, key, enabled])
    const handlers = useRef({ read, apply, fail, reset, pending }); handlers.current = { read, apply, fail, reset, pending }
    const currentOwner = useRef(owner); currentOwner.current = owner
    const refresh = useCallback((signal?: AbortSignal): Promise<void> => {
        if (!owner.active || currentOwner.current !== owner || !enabled || owner.denied) return Promise.resolve()
        if (signal?.aborted) return Promise.reject(abortError())
        if (owner.running) {
            const previous = owner.running, controller = owner.controller
            const cancel = () => controller?.abort()
            signal?.addEventListener('abort', cancel, { once: true })
            return previous.finally(() => signal?.removeEventListener('abort', cancel)).then(() => refresh(signal))
        }
        const controller = new AbortController(), epoch = owner.epoch, callbacks = handlers.current
        bindRequestBudget(controller.signal, scope?.requestBudget)
        owner.controller = controller
        const current = () => owner.active && currentOwner.current === owner && epoch === owner.epoch
            && (!scope || scope.isCurrent()) && !controller.signal.aborted && !signal?.aborted
        callbacks.pending?.(true)
        owner.running = withCancellation(async requestSignal => {
            const value = await callbacks.read(requestSignal)
            if (current() && !requestSignal.aborted) callbacks.apply(value)
        }, [controller.signal, ...(signal ? [signal] : [])]).catch(error => {
            if (!current()) throw error
            const denied = [403,404].includes(getApiErrorStatus(error) ?? 0)
            if (denied) { owner.denied = true; callbacks.reset() }
            callbacks.fail(error)
            if (!denied) throw error
        }).finally(() => {
            if (owner.controller === controller) {
                owner.running = undefined; owner.controller = undefined
                if (owner.active && currentOwner.current === owner) callbacks.pending?.(false)
            }
        })
        return owner.running
    }, [owner, enabled])
    const revoke = useCallback(() => {
        owner.epoch++; owner.denied = false; owner.controller?.abort(); handlers.current.reset()
    }, [owner])
    useEffect(() => {
        owner.active = true
        void refresh().catch(() => {})
        return () => {
            owner.active = false; owner.epoch++; owner.controller?.abort()
            owner.running = undefined; owner.controller = undefined
        }
    }, [owner, refresh])
    useUiInvalidation(key, enabled, matches, signal => refresh(signal), revoke, visible, onInvalidated)
    return { reload: useCallback(() => { owner.denied = false; void refresh().catch(() => {}) }, [owner, refresh]),
        capture: () => {
            const epoch = owner.epoch
            return () => owner.active && currentOwner.current === owner && owner.epoch === epoch && (!scope || scope.isCurrent())
        },
        isCurrent: () => owner.active && currentOwner.current === owner && (!scope || scope.isCurrent()) }
}
