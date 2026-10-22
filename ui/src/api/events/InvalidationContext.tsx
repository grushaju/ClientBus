import { createContext, useCallback, useContext, useEffect, useRef } from 'react'
import type { UiEventEnvelope } from '../types/uiEvent'
import type { InvalidationCoordinator } from './invalidationCoordinator'

export const InvalidationContext = createContext<InvalidationCoordinator | null>(null)

export function useUiInvalidation(key: string, enabled: boolean,
    matches: (event: UiEventEnvelope) => boolean, refresh: (signal: AbortSignal) => Promise<void>, reset?: (event?: UiEventEnvelope) => void, visible = true): (() => void) | undefined {
    const coordinator = useContext(InvalidationContext)
    const handlers = useRef({ matches, refresh, reset, visible }); handlers.current = { matches, refresh, reset, visible }
    const subscription = useRef<{ invalidate: () => void }>()
    useEffect(() => {
        if (!enabled || !coordinator) return
        const unsubscribe = coordinator.subscribe(event => handlers.current.matches(event), signal => handlers.current.refresh(signal),
            event => handlers.current.reset?.(event), () => handlers.current.visible)
        subscription.current = unsubscribe
        return () => { unsubscribe(); if (subscription.current === unsubscribe) subscription.current = undefined }
    }, [coordinator, key, enabled])
    useEffect(() => { if (visible) coordinator?.wake() }, [coordinator, visible])
    const invalidate = useCallback(() => subscription.current?.invalidate(), [])
    return coordinator ? invalidate : undefined
}
