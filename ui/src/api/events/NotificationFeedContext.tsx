import { createContext, useContext } from 'react'

export type NotificationFeedEvent = { kind: 'created'; id: string; source: 'sse' | 'push'; alertsAfter: number }
    | { kind: 'reset' }

/** The current stream boundary is available only after its initial reconciliation. */
export class NotificationFeed {
    private listeners = new Set<(event: NotificationFeedEvent) => void>()
    private revision = 0
    private boundary?: number
    private reconciling = false
    private ready = false
    subscribe(listener: (event: NotificationFeedEvent) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
    begin(alertsAfter: string) {
        this.reset()
        this.boundary = Date.parse(alertsAfter); this.reconciling = true
        return this.revision
    }
    finish(revision: number, success: boolean) {
        if (revision !== this.revision) return
        this.reconciling = false; this.ready = success
    }
    health(healthy: boolean) { if (!this.reconciling && this.boundary !== undefined) this.ready = healthy }
    invalidateAlerts() {
        this.ready = false
        this.clearAlerts()
    }
    /** Read-state changes cancel pending alerts without discarding an established stream boundary. */
    clearAlerts() {
        for (const listener of this.listeners) listener({ kind: 'reset' })
    }
    reset() {
        this.revision++; this.boundary = undefined; this.ready = false; this.reconciling = false
        for (const listener of this.listeners) listener({ kind: 'reset' })
    }
    created(id: string, source: 'sse' | 'push' = 'sse') {
        if (!this.ready || this.boundary === undefined || !Number.isFinite(this.boundary)) return
        for (const listener of this.listeners) listener({ kind: 'created', id, source, alertsAfter: this.boundary })
    }
}
export const NotificationFeedContext = createContext<NotificationFeed | null>(null)
export const useNotificationFeed = () => useContext(NotificationFeedContext)
