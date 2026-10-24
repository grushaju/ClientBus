import { apiFetch } from './apiClient'
import { abortError, withCancellation } from './events/requestScope'
import type { HistorySyncOperationDto, HistorySyncRequest } from './types/historySyncOperation'

declare const __HISTORY_OPERATIONS_ENABLED__: boolean | undefined
export const historyOperationsEnabled = () => typeof __HISTORY_OPERATIONS_ENABLED__ !== 'undefined' && __HISTORY_OPERATIONS_ENABLED__
export interface HistoryContext { account: string; workspaceId?: string | null; anchorMessageId?: string | null; manual?: boolean }
interface Pending { key: string; body: HistorySyncRequest; createdAt: number; workspaceId: string | null }
interface Discovered { operationId: string; createdAt: number; workspaceId: string | null }
type Recovery = Pending | Discovered
export interface HistoryOutcome { operation: HistorySyncOperationDto; key?: string }
const WINDOW = 7 * 24 * 60 * 60 * 1000
const PREFIX = 'clientbus.history.pending:'
const storageKey = (account: string, conversation: string) => PREFIX + account + ':' + conversation
const waiters = new Map<string, Set<() => void>>()

function storedHistory(account: string, conversation: string): Recovery | null {
    try {
        const value: Recovery | null = JSON.parse(sessionStorage.getItem(storageKey(account, conversation)) ?? 'null')
        if (!value) return null
        if (!Number.isFinite(value.createdAt)) return null
        if ('operationId' in value) return typeof value.operationId === 'string' && value.operationId.length > 0
            && value.operationId.length <= 128 ? value : null
        if (typeof value.key !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.key)
            || !value.body || !Number.isInteger(value.body.limit) || value.body.limit < 1 || value.body.limit > 100
            || (value.body.beforeExternalId !== null && (typeof value.body.beforeExternalId !== 'string' || value.body.beforeExternalId.length > 256))) return null
        return value
    } catch { return null }
}
export function pendingHistory(account: string, conversation: string): Pending | null {
    const value = storedHistory(account, conversation)
    return value && 'key' in value ? value : null
}
export function hasHistoryRecovery(account: string, conversation: string): boolean { return storedHistory(account, conversation) !== null }
function persist(account: string, conversation: string, pending: Recovery) {
    try {
        const key = storageKey(account, conversation)
        const keys = Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
        if (!sessionStorage.getItem(key) && keys.filter(item => item?.startsWith(PREFIX + account + ':')).length >= 32)
            throw new Error('Pending history limit')
        sessionStorage.setItem(key, JSON.stringify(pending))
    } catch { throw new Error('Не удалось сохранить состояние загрузки истории. Проверьте доступность хранилища браузера.') }
}
export function completeHistory(account: string, conversation: string, outcome: HistoryOutcome) {
    try {
        const stored = storedHistory(account, conversation)
        if (stored && ('key' in stored ? !!outcome.key && stored.key === outcome.key : stored.operationId === outcome.operation.id))
            sessionStorage.removeItem(storageKey(account, conversation))
    } catch { /* A retained terminal key is safe to recover again. */ }
}
function checkStoredCommand(account: string, conversation: string, manual = false) {
    let raw: string | null = null
    try { raw = sessionStorage.getItem(storageKey(account, conversation)) } catch { return }
    if (raw && !storedHistory(account, conversation)) {
        if (!manual) throw new Error('Не удалось восстановить состояние загрузки. Нажмите «Повторить», чтобы начать новую загрузку.')
        sessionStorage.removeItem(storageKey(account, conversation))
    }
}
export function wakeHistory(conversation: string) { waiters.get(conversation)?.forEach(wake => wake()) }
function wait(conversation: string, signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
        const callbacks = waiters.get(conversation) ?? new Set<() => void>()
        waiters.set(conversation, callbacks)
        const finish = () => { cleanup(); signal.aborted ? reject(signal.reason ?? abortError()) : resolve() }
        const timer = setTimeout(finish, 2000)
        const cleanup = () => {
            clearTimeout(timer); callbacks.delete(finish)
            if (!callbacks.size) waiters.delete(conversation)
            signal.removeEventListener('abort', finish)
            if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', finish)
            if (typeof window !== 'undefined') window.removeEventListener('online', finish)
        }
        callbacks.add(finish)
        signal.addEventListener('abort', finish, { once: true })
        if (typeof document !== 'undefined') document.addEventListener('visibilitychange', finish)
        if (typeof window !== 'undefined') window.addEventListener('online', finish)
        if (signal.aborted) finish()
    })
}
async function request<T>(url: string, signal: AbortSignal, init?: RequestInit): Promise<T> {
    return withCancellation(async child => {
        const response = await apiFetch(url, { ...init, signal: child })
        return response.json()
    }, [signal], 15_000)
}
async function follow(conversation: string, initial: HistorySyncOperationDto, signal: AbortSignal): Promise<HistorySyncOperationDto> {
    let operation = initial
    while (true) {
        if (signal.aborted) throw signal.reason ?? abortError()
        if (!operation.id || operation.conversationId !== conversation || !['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(operation.status))
            throw new Error('Не удалось прочитать состояние загрузки истории.')
        if (operation.status === 'SUCCEEDED' || operation.status === 'FAILED') return operation
        await wait(conversation, signal)
        if ((typeof document !== 'undefined' && document.hidden) || (typeof navigator !== 'undefined' && navigator.onLine === false)) continue
        operation = await request('/api/history-sync-operations/' + encodeURIComponent(operation.id), signal)
    }
}
async function recover(conversation: string, pending: Recovery, signal: AbortSignal): Promise<HistoryOutcome> {
    if (Date.now() - pending.createdAt >= WINDOW)
        throw new Error('Срок восстановления загрузки истёк. Нажмите «Повторить», чтобы начать новую загрузку.')
    if ('operationId' in pending) return { operation: await follow(conversation,
        await request<HistorySyncOperationDto>('/api/history-sync-operations/' + encodeURIComponent(pending.operationId), signal), signal) }
    const operation = await request<HistorySyncOperationDto>('/api/conversations/' + encodeURIComponent(conversation) + '/history-sync', signal,
        { method: 'POST', headers: { 'Idempotency-Key': pending.key }, body: JSON.stringify(pending.body) })
    return { operation: await follow(conversation, operation, signal), key: pending.key }
}
export async function startHistory(conversation: string, body: HistorySyncRequest, context: HistoryContext, signal: AbortSignal): Promise<HistoryOutcome> {
    checkStoredCommand(context.account, conversation, context.manual)
    let pending = storedHistory(context.account, conversation)
    if (pending && Date.now() - pending.createdAt >= WINDOW && context.manual) {
        completeHistory(context.account, conversation, 'key' in pending
            ? { key: pending.key, operation: {} as HistorySyncOperationDto }
            : { operation: { id: pending.operationId } as HistorySyncOperationDto })
        pending = null
    }
    if (!pending) {
        pending = { key: crypto.randomUUID(), body, createdAt: Date.now(), workspaceId: context.workspaceId ?? null }
        persist(context.account, conversation, pending) // No POST is allowed before durable client storage succeeds.
    }
    return recover(conversation, pending, signal)
}
export async function resumeHistory(conversation: string, context: HistoryContext, signal: AbortSignal): Promise<HistoryOutcome | null> {
    checkStoredCommand(context.account, conversation)
    const pending = storedHistory(context.account, conversation)
    if (pending) return recover(conversation, pending, signal)
    if (!historyOperationsEnabled()) return null
    const page = await request<{ items: HistorySyncOperationDto[] }>('/api/conversations/' + encodeURIComponent(conversation) + '/history-sync-operations?activeOnly=true&limit=1', signal)
    if (!page.items.length) return null
    const operation = page.items[0]
    if (!operation.id || operation.conversationId !== conversation) throw new Error('Не удалось прочитать состояние загрузки истории.')
    // Retain the operation ID before following it: active-only discovery cannot
    // find it after completion, including after a lost status/cache response.
    persist(context.account, conversation, { operationId: operation.id, createdAt: Date.now(), workspaceId: context.workspaceId ?? null })
    return { operation: await follow(conversation, operation, signal) }
}
export function historyFailure(code: string | null): string {
    const labels: Record<string, string> = { ACCESS_REVOKED: 'Доступ к загрузке истории отозван.',
        CONNECTOR_UNAVAILABLE: 'Канал недоступен. Уже загруженная история сохранена.',
        DEADLINE_EXCEEDED: 'Истекло время загрузки истории. Уже загруженные сообщения сохранены.',
        PUBLICATION_FAILED: 'Не удалось запустить загрузку истории.', IMPORT_FAILED: 'Не удалось полностью загрузить историю.' }
    return labels[code ?? ''] ?? 'Не удалось загрузить историю.'
}
