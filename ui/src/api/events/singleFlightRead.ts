import { abortError, withCancellation } from './requestScope'

/** A fresh reconciliation waits for an allowed earlier read, then obtains a new snapshot. */
export function singleFlightRead<T>(action: (signal: AbortSignal) => Promise<T>) {
    let running: Promise<T> | undefined, controller: AbortController | undefined
    const read = (signal?: AbortSignal, fresh = false): Promise<T> => {
        if (signal?.aborted) return Promise.reject(abortError())
        if (running) {
            const previous = running, request = controller
            const cancel = () => request?.abort()
            signal?.addEventListener('abort', cancel, { once: true })
            return previous.finally(() => signal?.removeEventListener('abort', cancel))
                .then(value => fresh ? read(signal) : value)
        }
        const request = new AbortController(); controller = request
        running = withCancellation(action, [request.signal, ...(signal ? [signal] : [])])
            .finally(() => { if (controller === request) { controller = undefined; running = undefined } })
        return running
    }
    return { read, cancel: () => { controller?.abort(); controller = undefined; running = undefined } }
}
