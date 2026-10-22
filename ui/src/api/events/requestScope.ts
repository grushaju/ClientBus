import { inheritRequestBudget } from './requestBudget'

export function abortError(): DOMException { return new DOMException('Запрос отменён', 'AbortError') }

/** Bounds the whole operation, including response-body parsing and adapters that ignore abort. */
export function withCancellation<T>(action: (signal: AbortSignal) => Promise<T>,
    parents: AbortSignal[] = [], timeoutMs = 30_000): Promise<T> {
    const controller = new AbortController()
    inheritRequestBudget(controller.signal, parents)
    const abort = () => controller.abort(abortError())
    for (const signal of parents) signal.addEventListener('abort', abort, { once: true })
    if (parents.some(signal => signal.aborted)) abort()
    const timer = setTimeout(() => controller.abort(new DOMException('Истекло время ожидания ответа. Повторите запрос.', 'TimeoutError')), timeoutMs)
    const cancelled = new Promise<never>((_, reject) => {
        const rejectAbort = () => reject(controller.signal.reason ?? abortError())
        controller.signal.addEventListener('abort', rejectAbort, { once: true })
        if (controller.signal.aborted) rejectAbort()
    })
    const operation = Promise.resolve().then(() => {
        if (controller.signal.aborted) throw controller.signal.reason
        return action(controller.signal)
    })
    return Promise.race([operation, cancelled]).finally(() => {
        clearTimeout(timer)
        for (const signal of parents) signal.removeEventListener('abort', abort)
        // The operation owns its child reads through body consumption. Once it settles,
        // release unfinished siblings as well, including an early Promise.all failure.
        controller.abort(abortError())
    })
}
