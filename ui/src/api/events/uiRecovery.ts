import type { InvalidationCoordinator } from './invalidationCoordinator'
import type { UiRecoveryPolicy } from './recoveryPolicy'
import type { StreamState } from './uiEventStream'

export type RecoveryMode = 'recovering' | 'live' | 'fallback' | 'paused' | 'stopped'

/** One periodic reconciliation for mounted resources; no catch-up queue for hidden/offline time. */
export class UiRecovery {
    private timer?: ReturnType<typeof setTimeout>
    private timerLive = false
    private stopped = false
    private visible = true
    private online = true
    private transport: StreamState = 'connecting'
    private healthy = true
    private running?: Promise<boolean>
    private failures = 0
    private rounds = 0
    constructor(private coordinator: InvalidationCoordinator, private policy: UiRecoveryPolicy,
        private random = Math.random) {}
    mode(): RecoveryMode {
        if (this.stopped) return 'stopped'
        if (!this.visible || !this.online) return 'paused'
        if (this.transport === 'unauthorized' || this.transport === 'session-changed' || this.transport === 'closed') return 'stopped'
        if (this.transport === 'open' && this.healthy) return 'live'
        if (this.transport === 'connecting' || this.transport === 'reconciling') return 'recovering'
        return 'fallback'
    }
    snapshot() { return { mode: this.mode(), rounds: this.rounds, failures: this.failures } }
    state(state: StreamState) {
        this.transport = state
        if (state === 'degraded') this.healthy = false
        this.schedule()
    }
    health(healthy: boolean) {
        const changed = healthy !== this.healthy
        this.healthy = healthy
        if (healthy) {
            this.failures = 0
            if (this.transport === 'degraded') this.transport = 'open'
        }
        if (changed) this.schedule()
    }
    visibility(visible: boolean) {
        const resumed = !this.visible && visible
        this.visible = visible
        if (resumed) this.request()
        this.schedule()
    }
    connectivity(online: boolean) {
        const resumed = !this.online && online
        this.online = online
        if (resumed) this.request()
        this.schedule()
    }
    request() {
        if (!['stopped', 'paused'].includes(this.mode())) this.coordinator.invalidate()
    }
    private schedule() {
        if (!this.policy.enabled || this.running || ['stopped', 'paused'].includes(this.mode())) {
            clearTimeout(this.timer); this.timer = undefined; return
        }
        const live = this.mode() === 'live'
        // Frequent connecting/backoff transitions must not postpone fallback forever.
        if (this.timer && live === this.timerLive) return
        clearTimeout(this.timer); this.timerLive = live
        const base = live ? this.policy.controlMs
            : Math.min(this.policy.fallbackMaxMs, this.policy.fallbackMs * 2 ** Math.min(this.failures, 16))
        const delay = Math.min(live ? this.policy.controlMs * (1 + this.policy.jitter) : this.policy.fallbackMaxMs,
            base * (1 + this.random() * this.policy.jitter))
        this.timer = setTimeout(() => { this.timer = undefined; void this.tick() }, delay)
    }
    private async tick() {
        if (this.running || ['stopped', 'paused'].includes(this.mode())) return
        this.rounds++
        const running = this.coordinator.reconcileAvailable(undefined, true)
        this.running = running
        try {
            const success = await running
            if (this.stopped) return
            if (success) this.health(true)
            else { this.healthy = false; this.failures++ }
        } catch { if (!this.stopped) { this.healthy = false; this.failures++ } }
        finally { if (this.running === running) { this.running = undefined; this.schedule() } }
    }
    stop() { this.stopped = true; clearTimeout(this.timer); this.timer = undefined }
}
