interface StoragePort { getItem(key: string): string | null; setItem(key: string, value: string): void }
interface ChannelPort {
    postMessage(value: unknown): void
    addEventListener(type: 'message', listener: (event: MessageEvent) => void): void
    close(): void
}
const key = 'clientbus.stream.tab'
const valid = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(value)
const randomId = (): string => globalThis.crypto?.randomUUID?.() ?? `t${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`

/** Resolve sessionStorage copies between live documents; reconnects retain the document's ID. */
export function createStreamTabIdentity(storage?: StoragePort, channel?: ChannelPort, owner = randomId()) {
    let id = randomId()
    try {
        const stored = storage?.getItem(key)
        // Without cross-document arbitration, use a fresh ID rather than trusting a cloned value.
        if (channel && valid(stored)) id = stored
        storage?.setItem(key, id)
    } catch { /* A document-local ID also works when storage is denied. */ }
    const listeners = new Set<() => void>()
    const announce = () => channel?.postMessage({ tab: id, owner })
    channel?.addEventListener('message', event => {
        const other = event.data
        if (!other || other.tab !== id || !valid(other.owner) || other.owner === owner) return
        if (owner > other.owner) {
            id = randomId()
            try { storage?.setItem(key, id) } catch { /* Storage is optional. */ }
            for (const listener of listeners) listener()
        }
        announce()
    })
    announce()
    return {
        get id() { return id },
        subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
        close() { channel?.close(); listeners.clear() },
    }
}

let identity: ReturnType<typeof createStreamTabIdentity> | undefined
export function streamTabIdentity() {
    if (!identity) {
        let storage: StoragePort | undefined
        let channel: ChannelPort | undefined
        try { storage = typeof sessionStorage === 'undefined' ? undefined : sessionStorage } catch { /* Optional. */ }
        try {
            if (typeof window !== 'undefined' && typeof window.BroadcastChannel !== 'undefined')
                channel = new window.BroadcastChannel('clientbus.stream.tabs')
        } catch { /* Use a fresh document ID when coordination is unavailable. */ }
        identity = createStreamTabIdentity(storage, channel)
    }
    return identity
}
