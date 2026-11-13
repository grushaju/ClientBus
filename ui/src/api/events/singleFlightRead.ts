import { abortError, withCancellation } from './requestScope'

/** A fresh reconciliation waits for an allowed earlier read, then obtains a new snapshot. */
export function singleFlightRead<T>(action: (signal: AbortSignal) => Promise<T>) {
    let running: Promise<T> | undefined, controller: AbortController | undefined
    const read = (signal?: AbortSignal, fresh = false): Promise<T> => {
        if (signal?.aborted) return Promise.reject(abortError())
        if (running) {
            const previous = running
            // Abort of a waiter cancels only this waiter's own wait. The shared
            // in-flight read belongs to its initiator and other waiters; aborting
            // its controller here would fail all of them (cross-cancellation).
            if (!signal) return previous.then(value => (fresh ? read() : value))
            return new Promise<T>((resolve, reject) => {
                const onAbort = () => reject(abortError())
                signal.addEventListener('abort', onAbort, { once: true })
                previous.then(value => {
                    signal.removeEventListener('abort', onAbort)
                    if (signal.aborted) reject(abortError())
                    else if (fresh) read(signal).then(resolve, reject)
                    else resolve(value)
                }, error => {
                    signal.removeEventListener('abort', onAbort)
                    reject(error)
                })
            })
        }
        const request = new AbortController(); controller = request
        running = withCancellation(action, [request.signal, ...(signal ? [signal] : [])])
            .finally(() => { if (controller === request) { controller = undefined; running = undefined } })
        return running
    }
    return { read, cancel: () => { controller?.abort(); controller = undefined; running = undefined } }
}
