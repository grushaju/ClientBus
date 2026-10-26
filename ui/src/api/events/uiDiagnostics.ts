import type { InvalidationCoordinator } from './invalidationCoordinator'
import type { RequestBudget } from './requestBudget'
import type { UiRecovery } from './uiRecovery'
import type { StreamState } from './uiEventStream'

type Transport = Readonly<{ state: StreamState; attempts: number; reconnects: number; resyncs: number }>
type Recovery = Readonly<ReturnType<UiRecovery['snapshot']>>
type Reconciliation = Readonly<ReturnType<InvalidationCoordinator['snapshot']>>
type Requests = Readonly<ReturnType<RequestBudget['snapshot']>>

export interface UiDiagnosticsSnapshot {
    readonly version: 1
    readonly managedPolling: boolean
    readonly transport: Transport
    readonly recovery: Recovery
    readonly reconciliation: Reconciliation
    readonly requests: Requests | null
}

declare global {
    interface Window {
        /** Local counters for the current authenticated scope. No data leaves the browser. */
        clientbusNotificationDiagnostics?: () => UiDiagnosticsSnapshot | null
    }
}

let current: { read: () => UiDiagnosticsSnapshot | null; revoke: () => void } | undefined

export function readUiDiagnostics(): UiDiagnosticsSnapshot | null {
    return current?.read() ?? null
}

/** An explicit field projection keeps future resource identifiers and private details out. */
function snapshot(value: UiDiagnosticsSnapshot): UiDiagnosticsSnapshot {
    const { transport: t, recovery: r, reconciliation: c, requests: q } = value
    return Object.freeze({
        version: 1,
        managedPolling: value.managedPolling,
        transport: Object.freeze({ state: t.state, attempts: t.attempts, reconnects: t.reconnects, resyncs: t.resyncs }),
        recovery: Object.freeze({ mode: r.mode, rounds: r.rounds, failures: r.failures }),
        reconciliation: Object.freeze({ started: c.started, completed: c.completed, failed: c.failed,
            cancelled: c.cancelled, peakRunning: c.peakRunning, resources: c.resources, dirty: c.dirty, running: c.running }),
        requests: q ? Object.freeze({ started: q.started, cancelled: q.cancelled, rejected: q.rejected,
            peakActive: q.peakActive, peakQueued: q.peakQueued, active: q.active, queued: q.queued }) : null,
    })
}

/** Each registration owns its function; a retained function is revoked on scope replacement. */
export function registerUiDiagnostics(reader: () => UiDiagnosticsSnapshot, isCurrent: () => boolean): () => void {
    current?.revoke()
    let active = true
    const read = () => {
        if (!active) return null
        if (!isCurrent()) { revoke(); return null }
        return snapshot(reader())
    }
    const revoke = () => {
        active = false
        if (current?.read === read) current = undefined
        if (typeof window !== 'undefined' && window.clientbusNotificationDiagnostics === read) {
            delete window.clientbusNotificationDiagnostics
        }
    }
    current = { read, revoke }
    if (typeof window !== 'undefined') window.clientbusNotificationDiagnostics = read
    return revoke
}
