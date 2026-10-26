export type NotificationAudioState = 'locked' | 'ready' | 'blocked' | 'unavailable'

/** Context creation/resume belongs to a user gesture; an incoming notification never unlocks audio. */
export class NotificationAudio {
    private context?: AudioContext
    private buffer?: AudioBuffer
    private loading?: Promise<void>
    private download?: AbortController
    private closed = false
    private nodes = new Set<AudioScheduledSourceNode>()
    constructor(private changed: (state: NotificationAudioState) => void = () => {}) {}
    open(): void { this.closed = false }
    get ready(): boolean { return !this.closed && !this.loading && this.context?.state === 'running' }
    activate(): Promise<boolean> {
        if (this.closed) return Promise.resolve(false)
        if (this.ready) return Promise.resolve(true)
        try {
            const Constructor = window.AudioContext ?? (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
            if (!Constructor) { this.changed('unavailable'); return Promise.resolve(false) }
            if (!this.context || this.context.state === 'closed') {
                this.context = new Constructor()
                this.context.onstatechange = () => { if (!this.closed) this.changed(this.ready ? 'ready' : 'locked') }
            }
            const context = this.context
            // Call synchronously before returning from the trusted pointer/key handler.
            const resumed = context.state === 'running' ? Promise.resolve() : context.resume()
            return Promise.all([resumed, this.prepare(context)]).then(() => {
                if (this.closed || context !== this.context) return false
                this.changed(this.ready ? 'ready' : 'blocked'); return this.ready
            }, () => { if (!this.closed && context === this.context) this.changed('blocked'); return false })
        } catch { this.changed('unavailable'); return Promise.resolve(false) }
    }
    private prepare(context: AudioContext): Promise<void> {
        if (this.buffer) return Promise.resolve()
        if (this.loading) return this.loading
        const controller = new AbortController(); this.download = controller
        const timer = setTimeout(() => controller.abort(), 5000)
        const loading = Promise.resolve().then(async () => {
            const response = await window.fetch('/notification.ogg', { signal: controller.signal, cache: 'force-cache' })
            if (!response.ok) return
            const bytes = await response.arrayBuffer()
            if (bytes.byteLength > 256 * 1024 || controller.signal.aborted) return
            const buffer = await context.decodeAudioData(bytes)
            if (!this.closed && context === this.context && !controller.signal.aborted) this.buffer = buffer
        }).catch(() => { /* A missing/unsupported clip retains the short built-in tone. */ }).finally(() => {
            clearTimeout(timer)
            if (this.download === controller) this.download = undefined
            if (this.loading === loading) this.loading = undefined
        })
        this.loading = loading
        return loading
    }
    play(): boolean {
        if (!this.ready) return false
        try {
            for (const node of this.nodes) { try { node.stop() } catch { /* Already ended. */ } }
            const context = this.context!, volume = context.createGain()
            const now = context.currentTime
            if (this.buffer) {
                const source = context.createBufferSource(); source.buffer = this.buffer
                volume.gain.setValueAtTime(0.5, now)
                source.connect(volume); volume.connect(context.destination)
                this.nodes.add(source)
                source.onended = () => { this.nodes.delete(source); source.disconnect(); volume.disconnect() }
                source.start(now); source.stop(now + Math.min(this.buffer.duration, 5))
                return true
            }
            const oscillator = context.createOscillator()
            oscillator.type = 'sine'; oscillator.frequency.setValueAtTime(740, now)
            oscillator.frequency.exponentialRampToValueAtTime(620, now + 0.22)
            volume.gain.setValueAtTime(0.0001, now)
            volume.gain.exponentialRampToValueAtTime(0.07, now + 0.015)
            volume.gain.exponentialRampToValueAtTime(0.0001, now + 0.23)
            oscillator.connect(volume); volume.connect(context.destination)
            this.nodes.add(oscillator)
            oscillator.onended = () => { this.nodes.delete(oscillator); oscillator.disconnect(); volume.disconnect() }
            oscillator.start(now); oscillator.stop(now + 0.25)
            return true
        } catch { this.changed('blocked'); return false }
    }
    close(): void {
        this.closed = true
        this.download?.abort(); this.download = undefined; this.loading = undefined; this.buffer = undefined
        for (const node of this.nodes) { try { node.stop(); node.disconnect() } catch { /* Already ended. */ } }
        this.nodes.clear()
        if (this.context) {
            this.context.onstatechange = null
            void this.context.close().catch(() => {})
            this.context = undefined
        }
    }
}

const TTL = 48 * 60 * 60 * 1000, MAX_IDS = 4096, MIN_INTERVAL = 2000
type Ledger = { ids: Array<{ id: string; expiresAt: number }>; playedAt: number }
const memory = new Map<string, Ledger>()

/** Independent of visual banner dedup. Web Locks serialize the durable claim across same-origin tabs. */
export async function claimNotificationSound(account: string, id: string, eligible: () => boolean,
    play: () => boolean, signal?: AbortSignal): Promise<boolean> {
    const key = 'clientbus:notifications:sounds:' + account
    if (signal?.aborted || !eligible()) return false
    const claim = () => {
        if (signal?.aborted || !eligible()) return false
        const now = Date.now()
        let ledger = memory.get(key) ?? { ids: [], playedAt: 0 }
        try {
            const stored: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
            if (stored && typeof stored === 'object' && 'ids' in stored && Array.isArray(stored.ids)) {
                ledger = { ids: stored.ids.filter(value => value && typeof value.id === 'string' && value.id.length <= 128
                    && Number.isFinite(value.expiresAt)), playedAt: 'playedAt' in stored && typeof stored.playedAt === 'number'
                        && Number.isFinite(stored.playedAt) ? stored.playedAt : 0 }
            }
        } catch { /* Blocked storage degrades to bounded session dedup. */ }
        ledger = { ids: ledger.ids.filter(value => value.expiresAt > now && value.expiresAt <= now + TTL),
            playedAt: ledger.playedAt <= now ? ledger.playedAt : 0 }
        if (ledger.ids.some(value => value.id === id)) return false
        ledger.ids.push({ id, expiresAt: now + TTL }); ledger.ids = ledger.ids.slice(-MAX_IDS)
        const audible = !ledger.playedAt || now - ledger.playedAt >= MIN_INTERVAL
        // Burst-suppressed IDs are consumed too, so a later push cannot replay them.
        if (audible) ledger.playedAt = now
        memory.set(key, ledger)
        try { localStorage.setItem(key, JSON.stringify(ledger)) } catch { /* Storage is optional. */ }
        return audible && eligible() && !signal?.aborted && play()
    }
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
    if (!locks) return claim()
    const controller = new AbortController(), abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) controller.abort()
    const timer = setTimeout(abort, 1500)
    try { return await locks.request(key, { signal: controller.signal }, claim) }
    catch { return controller.signal.aborted ? false : claim() }
    finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
}
