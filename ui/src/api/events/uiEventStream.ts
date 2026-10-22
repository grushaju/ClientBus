import type { UiEventEnvelope } from '../types/uiEvent'
import { SseParser } from './sseParser'
import { retryAfterDelay } from './retryAfter'

export type StreamState = 'connecting' | 'reconciling' | 'open' | 'backoff'
    | 'degraded' | 'offline' | 'incompatible' | 'unauthorized' | 'forbidden' | 'session-changed' | 'disabled' | 'closed'
export interface UiStreamOptions {
    getToken: () => string | null
    workspaceId: string | null
    onReady: (event: UiEventEnvelope, signal: AbortSignal) => void | boolean | Promise<void | boolean>
    onEvent: (event: UiEventEnvelope) => void
    onResync: () => void
    onState?: (state: StreamState) => void
    signal?: AbortSignal
    fetcher?: typeof fetch
    /** Tests may shorten timers; production callers use defaults. */
    reconnectBaseMs?: number
    handshakeTimeoutMs?: number
    reconciliationTimeoutMs?: number
    watchdogIntervalMs?: number
    isOnline?: () => boolean
    connectivityTarget?: EventTarget
}
const known = new Set(['stream.ready', 'stream.heartbeat', 'stream.resync-required',
    'messages.invalidated', 'inbox.invalidated', 'groups.invalidated', 'filters.invalidated',
    'clients.invalidated', 'channels.invalidated', 'workspace.invalidated', 'employees.invalidated',
    'identity.invalidated', 'access.invalidated', 'notifications.created', 'notifications.invalidated',
    'history-operation.invalidated'])
const uuid = (value: unknown): value is string => typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const date = (value: unknown): boolean => typeof value === 'string' && Number.isFinite(Date.parse(value))

function envelope(value: unknown, type: string): UiEventEnvelope {
    if (!value || typeof value !== 'object') throw new Error('Invalid SSE envelope')
    const e = value as UiEventEnvelope
    if (!uuid(e.eventId) || e.type !== type || !date(e.occurredAt)
        || !Number.isInteger(e.schemaVersion) || typeof e.type !== 'string'
        || (e.workspaceId != null && !uuid(e.workspaceId))
        || (e.resource != null && (!uuid(e.resource.id) || typeof e.resource.kind !== 'string')))
        throw new Error('Invalid SSE envelope')
    return e
}

/** Transport layer. Minimal AppLayout/REST integration is provided by UiEventsProvider (11.5). */
export function startUiEventStream(options: UiStreamOptions): { stop: () => void; done: Promise<void> } {
    const lifetime = new AbortController()
    const sessionToken = options.getToken()
    const stop = () => lifetime.abort()
    options.signal?.addEventListener('abort', stop, { once: true })
    if (options.signal?.aborted) stop()
    const state = (value: StreamState) => options.onState?.(value)
    const isOnline = options.isOnline ?? (() => typeof navigator === 'undefined' || navigator.onLine !== false)
    const connectivity = options.connectivityTarget ?? (typeof window === 'undefined' ? undefined : window)
    let activeAttempt: AbortController | undefined
    const offline = () => { state('offline'); activeAttempt?.abort() }
    connectivity?.addEventListener('offline', offline)
    const pause = (ms: number) => new Promise<void>(resolve => {
        if (lifetime.signal.aborted) return resolve()
        const finish = () => {
            clearTimeout(timer); lifetime.signal.removeEventListener('abort', finish)
            connectivity?.removeEventListener('online', finish); connectivity?.removeEventListener('offline', finish); resolve()
        }
        const timer = setTimeout(finish, ms)
        lifetime.signal.addEventListener('abort', finish, { once: true })
        connectivity?.addEventListener('online', finish, { once: true }); connectivity?.addEventListener('offline', finish, { once: true })
    })
    const waitForNetwork = () => new Promise<void>(resolve => {
        if (isOnline() || lifetime.signal.aborted) return resolve()
        const finish = () => { connectivity?.removeEventListener('online', finish); lifetime.signal.removeEventListener('abort', finish); resolve() }
        connectivity?.addEventListener('online', finish, { once: true }); lifetime.signal.addEventListener('abort', finish, { once: true })
    })
    const done = (async () => {
        let failures = 0
        let terminal = false
        while (!lifetime.signal.aborted && !terminal) {
            if (!isOnline()) { state('offline'); await waitForNetwork(); if (lifetime.signal.aborted) break }
            const token = options.getToken()
            if (!token) { state('unauthorized'); break }
            if (token !== sessionToken) { state('session-changed'); break }
            const attempt = new AbortController()
            activeAttempt = attempt
            const abort = () => attempt.abort()
            lifetime.signal.addEventListener('abort', abort, { once: true })
            let active = true, ready = false, reconciled = false
            let openedAt: number | undefined
            let lastSeen = Date.now(), idleLimit = options.handshakeTimeoutMs ?? 20_000
            let reconciliationDeadline: number | undefined
            let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
            const buffered: UiEventEnvelope[] = []
            let retryAfter = 0
            let resyncOnFailure = false
            const current = () => {
                if (!active || attempt.signal.aborted || lifetime.signal.aborted) return false
                if (options.getToken() !== token) {
                    terminal = true; state('session-changed'); attempt.abort(); return false
                }
                return true
            }
            const watch = setInterval(() => {
                if (options.getToken() !== token) { terminal = true; state('session-changed'); attempt.abort() }
                else if (Date.now() - lastSeen > idleLimit
                    || (reconciliationDeadline !== undefined && !reconciled && Date.now() >= reconciliationDeadline)) attempt.abort()
            }, options.watchdogIntervalMs ?? 1000)
            try {
                state('connecting')
                const query = options.workspaceId ? '?' + new URLSearchParams({ workspaceId: options.workspaceId }) : ''
                const response = await (options.fetcher ?? fetch)('/api/events' + query, {
                    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
                    signal: attempt.signal, cache: 'no-store', credentials: 'omit',
                })
                if (!current()) continue
                if (response.status === 404) { terminal = true; state('disabled'); continue }
                if (response.status === 401 || response.status === 403) {
                    terminal = true; state(response.status === 401 ? 'unauthorized' : 'forbidden'); continue
                }
                if (!response.ok) {
                    retryAfter = retryAfterDelay(response.headers.get('Retry-After'))
                    throw new Error('Stream unavailable')
                }
                if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('text/event-stream') || !response.body)
                    throw new Error('Expected SSE response')
                resyncOnFailure = true
                const parser = new SseParser(frame => {
                    if (!current()) return
                    if (new TextEncoder().encode(frame.data).length > 16_384) throw new Error('SSE event too large')
                    const event = envelope(JSON.parse(frame.data), frame.event)
                    lastSeen = Date.now()
                    if (event.schemaVersion !== 1) {
                        terminal = true; state('incompatible'); options.onResync(); throw new Error('Unsupported SSE schema')
                    }
                    if (event.type === 'stream.ready') {
                        if (ready || !event.stream || !uuid(event.stream.streamId) || !date(event.stream.alertsAfter)
                            || !Number.isInteger(event.stream.heartbeatSeconds) || event.stream.heartbeatSeconds < 1 || event.stream.heartbeatSeconds > 60)
                            throw new Error('Invalid stream ready')
                        ready = true; idleLimit = event.stream.heartbeatSeconds * 2500 + 5000
                        reconciliationDeadline = Date.now() + (options.reconciliationTimeoutMs ?? 30_000)
                        state('reconciling')
                        const finishReconciliation = (success: void | boolean) => {
                            if (!current()) return
                            reconciled = true; openedAt = Date.now(); state(success === false ? 'degraded' : 'open')
                            for (const item of buffered.splice(0)) { if (current()) options.onEvent(item) }
                        }
                        Promise.resolve().then(() => options.onReady(event, attempt.signal))
                            .then(finishReconciliation).catch(() => finishReconciliation(false))
                    } else {
                        if (!ready) throw new Error('Event before ready')
                        if (event.type === 'stream.heartbeat') return
                        if (event.type === 'stream.resync-required' || !known.has(event.type)) { options.onResync(); return }
                        // ACL/identity changes cancel old reads even while ready is still reconciling.
                        if (reconciled || event.type === 'access.invalidated' || event.type === 'identity.invalidated') options.onEvent(event)
                        else {
                            if (buffered.length >= 128) { options.onResync(); throw new Error('Reconciliation queue overflow') }
                            buffered.push(event)
                        }
                    }
                })
                reader = response.body.getReader()
                const decoder = new TextDecoder('utf-8', { fatal: true })
                while (current()) {
                    const chunk = await reader.read()
                    if (chunk.done) { parser.push(decoder.decode()); break }
                    parser.push(decoder.decode(chunk.value, { stream: true }))
                }
            } catch {
                // EOF, malformed/oversized frame, timeout and transient HTTP/network failure all reconcile on reconnect.
            } finally {
                active = false; buffered.length = 0; clearInterval(watch); attempt.abort()
                try { await reader?.cancel() } catch { /* Reader may already be errored. */ }
                reader?.releaseLock()
                lifetime.signal.removeEventListener('abort', abort)
                if (activeAttempt === attempt) activeAttempt = undefined
            }
            if (!terminal && !lifetime.signal.aborted) {
                if (!isOnline()) continue
                if (resyncOnFailure) options.onResync()
                state('backoff')
                if (openedAt !== undefined && Date.now() - openedAt >= 10_000) failures = 0
                const base = options.reconnectBaseMs ?? 1000
                const delay = Math.min(30_000, base * 2 ** Math.min(failures++, 5))
                await pause(Math.max(retryAfter, delay + Math.random() * delay * 0.2))
            }
        }
        if (lifetime.signal.aborted) state('closed')
    })().finally(() => { options.signal?.removeEventListener('abort', stop); connectivity?.removeEventListener('offline', offline) })
    return { stop, done }
}
