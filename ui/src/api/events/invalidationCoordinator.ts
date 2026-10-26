import type { UiEventEnvelope } from '../types/uiEvent'
import { abortError, withCancellation } from './requestScope'
import { bindRequestBudget, type RequestBudget } from './requestBudget'

type Resource = {
    matches: (event: UiEventEnvelope) => boolean
    refresh: (signal: AbortSignal) => Promise<void>
    revision: number; completed: number; closed: boolean; lastStart: number
    controller: AbortController
    reset?: (event?: UiEventEnvelope) => void
    onInvalidated?: (event?: UiEventEnvelope) => void
    epoch: number
    failures: number; retryAt: number
    visible: () => boolean
    cancelRun?: () => void; running?: Promise<void>; timer?: ReturnType<typeof setTimeout>
}

/** One active REST reconciliation per mounted resource; invalidation never cancels its allowed response. */
export class InvalidationCoordinator {
    private resources = new Set<Resource>()
    private closed = false
    private counters = { started: 0, completed: 0, failed: 0, cancelled: 0, peakRunning: 0 }
    constructor(private canRefresh = () => true, private delayMs = 100, private minIntervalMs = 250,
        private requestTimeoutMs = 30_000, private options: { budget?: RequestBudget; retryMs?: number;
            retryMaxMs?: number; jitter?: number; random?: () => number; onHealth?: (healthy: boolean) => void } = {}) {}

    snapshot() { return { ...this.counters, resources: this.resources.size,
        dirty: [...this.resources].filter(resource => resource.completed < resource.revision).length,
        running: [...this.resources].filter(resource => resource.running).length } }
    private health() { if (!this.closed) this.options.onHealth?.(![...this.resources].some(resource => resource.visible() && resource.failures > 0)) }

    subscribe(matches: Resource['matches'], refresh: Resource['refresh'], reset?: Resource['reset'], visible = () => true,
        onInvalidated?: Resource['onInvalidated']): (() => void) & { invalidate: () => void } {
        this.closed = false
        const resource: Resource = { matches, refresh, reset, visible, onInvalidated, epoch: 0, failures: 0, retryAt: 0,
            revision: 0, completed: 0, closed: false, lastStart: 0, controller: new AbortController() }
        this.resources.add(resource)
        return Object.assign(() => {
            resource.closed = true; resource.controller.abort(); clearTimeout(resource.timer)
            this.resources.delete(resource)
            this.health()
        }, { invalidate: () => {
            if (!resource.closed) { resource.onInvalidated?.(); resource.revision++; this.schedule(resource) }
        } })
    }
    invalidate(event?: UiEventEnvelope): void {
        for (const resource of this.resources) if (!event || resource.matches(event)) {
            resource.onInvalidated?.(event)
            resource.revision++; this.schedule(resource)
        }
    }
    revoke(event?: UiEventEnvelope): void {
        for (const resource of this.resources) if (!event || resource.matches(event)) {
            resource.epoch++
            resource.failures = 0; resource.retryAt = 0
            resource.controller.abort(); resource.controller = new AbortController()
            resource.cancelRun?.(); resource.reset?.(event)
            resource.revision++; this.schedule(resource)
        }
    }
    wake(): void {
        for (const resource of this.resources) if (resource.completed < resource.revision) this.schedule(resource)
        this.health()
    }
    async reconcile(signal?: AbortSignal): Promise<void> {
        if (signal?.aborted) throw abortError()
        await Promise.all([...this.resources].map(async resource => {
            const required = ++resource.revision
            if (!this.canRefresh() || !resource.visible()) return
            while (!resource.closed && resource.completed < required) {
                if (signal?.aborted) throw abortError()
                const cancel = () => resource.cancelRun?.()
                signal?.addEventListener('abort', cancel, { once: true })
                try { await (resource.running ?? this.run(resource, signal)) }
                finally { signal?.removeEventListener('abort', cancel) }
            }
        }))
    }
    /** A partial REST failure preserves dirty without tearing down a healthy SSE connection. */
    async reconcileAvailable(signal?: AbortSignal, respectBackoff = false): Promise<boolean> {
        if (signal?.aborted) throw abortError()
        const outcomes = await Promise.all([...this.resources].map(async resource => {
            const required = ++resource.revision
            if (!this.canRefresh() || !resource.visible()) return true
            try {
                while (!resource.closed && resource.completed < required) {
                    if (signal?.aborted) throw abortError()
                    if (respectBackoff && !resource.running && resource.retryAt > Date.now()) { this.schedule(resource); return false }
                    const cancel = () => resource.cancelRun?.()
                    signal?.addEventListener('abort', cancel, { once: true })
                    try { await (resource.running ?? this.run(resource, signal)) }
                    finally { signal?.removeEventListener('abort', cancel) }
                }
                return true
            } catch (error) {
                if (signal?.aborted) throw error
                return resource.closed || !resource.visible()
            }
        }))
        return outcomes.every(Boolean)
    }
    private schedule(resource: Resource, retry = false): void {
        if (resource.closed || resource.running || resource.timer || !this.canRefresh() || !resource.visible()) return
        const delay = Math.max(retry ? 0 : this.delayMs, resource.retryAt - Date.now(), resource.lastStart + this.minIntervalMs - Date.now())
        resource.timer = setTimeout(() => {
            resource.timer = undefined
            if (this.canRefresh() && resource.visible()) void this.run(resource).catch(() => {})
        }, delay)
    }
    private run(resource: Resource, attempt?: AbortSignal): Promise<void> {
        if (resource.running) return resource.running
        if (resource.closed) return Promise.resolve()
        clearTimeout(resource.timer); resource.timer = undefined
        const revision = resource.revision
        const epoch = resource.epoch
        const request = new AbortController()
        bindRequestBudget(request.signal, this.options.budget)
        resource.cancelRun = () => request.abort()
        resource.lastStart = Date.now()
        let failed = false
        this.counters.started++
        this.counters.peakRunning = Math.max(this.counters.peakRunning, this.snapshot().running + 1)
        resource.running = withCancellation(signal => resource.refresh(signal),
            [resource.controller.signal, request.signal, ...(attempt ? [attempt] : [])], this.requestTimeoutMs)
        .then(() => { if (resource.epoch === epoch) { resource.completed = revision; resource.failures = 0; resource.retryAt = 0; this.counters.completed++ } })
        .catch(error => {
            failed = true
            if (resource.epoch === epoch && !resource.closed && error?.name !== 'AbortError') {
                resource.failures++; this.counters.failed++
                const base = this.options.retryMs ?? 1000, max = this.options.retryMaxMs ?? 60_000
                const hint = (error as { retryAfterMs?: unknown })?.retryAfterMs
                const delay = Math.min(max, Math.max(typeof hint === 'number' && Number.isFinite(hint) ? hint : 0,
                    base * 2 ** Math.min(resource.failures - 1, 16)))
                resource.retryAt = Date.now() + Math.min(max, delay * (1 + (this.options.random ?? Math.random)() * (this.options.jitter ?? 0)))
            } else this.counters.cancelled++
            throw error
        }).finally(() => {
            resource.running = undefined
            resource.cancelRun = undefined
            this.health()
            if (resource.completed < resource.revision) this.schedule(resource, failed)
        })
        return resource.running
    }
    close(): void {
        this.closed = true
        // Reusable after effect cleanup/setup (React StrictMode).
        for (const resource of this.resources) {
            resource.closed = true; resource.controller.abort(); clearTimeout(resource.timer)
        }
        this.resources.clear()
    }
}
