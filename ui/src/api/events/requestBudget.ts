type Waiter = { signal: AbortSignal; resolve: (release: () => void) => void; reject: (error: unknown) => void; cancel: () => void }
const budgets = new WeakMap<AbortSignal, RequestBudget>()
const cancelled = () => new DOMException('Request cancelled', 'AbortError')

export function bindRequestBudget(signal: AbortSignal, budget?: RequestBudget): void {
    if (budget) budgets.set(signal, budget)
}
export function inheritRequestBudget(signal: AbortSignal, parents: AbortSignal[]): void {
    const budget = parents.map(parent => budgets.get(parent)).find(Boolean)
    bindRequestBudget(signal, budget)
}

/** FIFO admission for actual automatic GETs, including their response bodies. Commands bypass it. */
export class RequestBudget {
    private active = 0
    private queue: Waiter[] = []
    private timer?: ReturnType<typeof setTimeout>
    private nextStart = 0
    private requests = new Set<AbortController>()
    private closed = false
    private paused = false
    private counters = { started: 0, cancelled: 0, rejected: 0, peakActive: 0, peakQueued: 0 }
    constructor(private maxConcurrent = 4, private maxQueued = 128, private spacingMs = 100) {}
    snapshot() { return { ...this.counters, active: this.active, queued: this.queue.length } }
    resume() { this.closed = false; this.paused = false; this.drain() }
    pause() { this.paused = true; clearTimeout(this.timer); this.timer = undefined }
    close() {
        this.closed = true; clearTimeout(this.timer); this.timer = undefined
        for (const request of [...this.requests]) request.abort()
        for (const waiter of [...this.queue]) waiter.cancel()
    }
    private acquire(signal: AbortSignal): Promise<() => void> {
        if (signal.aborted || this.closed) return Promise.reject(cancelled())
        if (this.queue.length >= this.maxQueued) {
            this.counters.rejected++
            return Promise.reject(Object.assign(new Error('Automatic request queue is full'), { status: 429 }))
        }
        return new Promise((resolve, reject) => {
            const waiter: Waiter = { signal, resolve, reject, cancel: () => {
                const index = this.queue.indexOf(waiter)
                if (index < 0) return
                this.queue.splice(index, 1); signal.removeEventListener('abort', waiter.cancel)
                this.counters.cancelled++; reject(cancelled()); this.drain()
            } }
            this.queue.push(waiter); signal.addEventListener('abort', waiter.cancel, { once: true })
            this.counters.peakQueued = Math.max(this.counters.peakQueued, this.queue.length)
            this.drain()
        })
    }
    private drain() {
        if (this.closed || this.paused || this.timer || this.active >= this.maxConcurrent || !this.queue.length) return
        const delay = this.nextStart - Date.now()
        if (delay > 0) { this.timer = setTimeout(() => { this.timer = undefined; this.drain() }, delay); return }
        const waiter = this.queue.shift()!
        waiter.signal.removeEventListener('abort', waiter.cancel)
        if (waiter.signal.aborted) { waiter.reject(cancelled()); this.drain(); return }
        this.active++; this.counters.started++; this.counters.peakActive = Math.max(this.active, this.counters.peakActive)
        this.nextStart = Date.now() + this.spacingMs
        let released = false
        waiter.resolve(() => {
            if (released) return
            released = true; this.active--; this.drain()
        })
        this.drain()
    }
    async fetch(input: RequestInfo | URL, init: RequestInit, fetcher: typeof fetch): Promise<Response> {
        const controller = new AbortController(), parent = init.signal
        this.requests.add(controller)
        const abort = () => controller.abort()
        parent?.addEventListener('abort', abort, { once: true })
        if (parent?.aborted || this.closed) abort()
        let release: (() => void) | undefined
        let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
        let finished = false
        const finish = () => {
            if (finished) return
            finished = true
            parent?.removeEventListener('abort', abort); this.requests.delete(controller)
            controller.signal.removeEventListener('abort', aborted)
            release?.()
        }
        const aborted = () => { void reader?.cancel().catch(() => {}); finish() }
        try {
            release = await this.acquire(controller.signal)
            controller.signal.addEventListener('abort', aborted, { once: true })
            if (controller.signal.aborted) { finish(); throw cancelled() }
            const response = await fetcher(input, { ...init, signal: controller.signal })
            if (controller.signal.aborted) { void response.body?.cancel().catch(() => {}); throw cancelled() }
            if (!response.body) { finish(); return response }
            reader = response.body.getReader()
            const body = new ReadableStream<Uint8Array>({
                async pull(target) {
                    try {
                        const chunk = await reader!.read()
                        if (controller.signal.aborted) throw cancelled()
                        if (chunk.done) { finish(); reader!.releaseLock(); target.close() }
                        else target.enqueue(chunk.value)
                    } catch (error) { finish(); target.error(error) }
                },
                async cancel(reason) { try { await reader!.cancel(reason) } finally { finish() } },
            })
            return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers })
        } catch (error) { finish(); throw error }
    }
}

export function budgetedFetch(input: RequestInfo | URL, init: RequestInit): Promise<Response> {
    const budget = init.signal ? budgets.get(init.signal) : undefined
    return budget && (init.method ?? 'GET').toUpperCase() === 'GET'
        ? budget.fetch(input, init, fetch) : fetch(input, init)
}
