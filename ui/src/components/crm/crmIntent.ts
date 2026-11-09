import type { CrmOperation } from '../../api/crmApi'
import { getApiErrorCode } from '../../api/apiClient'

export interface CrmIntentScope { organizationId: string; employeeId: string; projectId: string }
export interface CrmIntent { key: string; type: CrmOperation['type']; operationId?: string }
const prefix = 'clientbus.crm.intent.v1:'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function intentKey(scope: CrmIntentScope) {
    if (![scope.organizationId, scope.employeeId, scope.projectId].every(id => typeof id === 'string' && /^[a-z0-9_-]{1,128}$/i.test(id))) throw Error('Не определён контекст операции CRM.')
    return prefix + [scope.organizationId, scope.employeeId, scope.projectId].join(':')
}
export function readIntent(scope: CrmIntentScope): CrmIntent | null {
    const value = localStorage.getItem(intentKey(scope))
    if (!value) return null
    const intent = JSON.parse(value) as CrmIntent
    if (!uuid.test(intent.key) || !['CREATE', 'MOVE', 'CANCEL'].includes(intent.type) || intent.operationId && !uuid.test(intent.operationId)) throw Error('Не удалось прочитать незавершённую операцию CRM. Требуется проверка журнала.')
    return { key: intent.key, type: intent.type, ...(intent.operationId ? { operationId: intent.operationId } : {}) }
}
function changed(scope: CrmIntentScope) { window.dispatchEvent(new CustomEvent('clientbus:crm-intent', { detail: intentKey(scope) })) }
function write(scope: CrmIntentScope, intent: CrmIntent) {
    localStorage.setItem(intentKey(scope), JSON.stringify(intent)); changed(scope)
}
function resolveInside(scope: CrmIntentScope, operation: CrmOperation) {
    const intent = readIntent(scope)
    if (!intent || intent.key !== operation.idempotencyKey) return
    if (!uuid.test(operation.id) || operation.projectId !== scope.projectId || operation.initiatorEmployeeId !== scope.employeeId
        || !['REQUESTED', 'IN_FLIGHT', 'UNKNOWN', 'CONFIRMED', 'REJECTED'].includes(operation.status)) throw Error('Контекст ответа операции CRM не совпал.')
    if (['CONFIRMED', 'REJECTED'].includes(operation.status)) { localStorage.removeItem(intentKey(scope)); changed(scope) }
    else if (intent.operationId !== operation.id) write(scope, { ...intent, operationId: operation.id })
}
async function locked<T>(scope: CrmIntentScope, action: () => T, signal?: AbortSignal): Promise<T> {
    if (!navigator.locks) throw Error('Для безопасного сохранения операции требуется браузер с поддержкой Web Locks.')
    return signal ? navigator.locks.request(intentKey(scope), { signal }, action) : navigator.locks.request(intentKey(scope), action)
}
function clearIntent(scope: CrmIntentScope, key: string) {
    if (readIntent(scope)?.key === key) { localStorage.removeItem(intentKey(scope)); changed(scope) }
}
export async function resolveIntent(scope: CrmIntentScope, operation: CrmOperation) {
    await locked(scope, () => resolveInside(scope, operation))
}
/** The Web Lock makes the durable pointer check/write atomic across tabs of this browser. */
export async function sendIntent(scope: CrmIntentScope, type: CrmIntent['type'], send: (key: string) => Promise<CrmOperation>, signal?: AbortSignal) {
    let intent: CrmIntent | undefined
    try {
        const started = await locked(scope, () => {
            signal?.throwIfAborted()
            if (readIntent(scope)) throw Error('Сначала проверьте незавершённую операцию в журнале. Повторный запрос не отправлен.')
            intent = { key: crypto.randomUUID(), type }
            try { write(scope, intent) } catch { throw Error('Не удалось сохранить ключ операции. Запрос не отправлен.') }
            // Storage listeners may cancel the form synchronously. No POST has started yet.
            if (signal?.aborted) { clearIntent(scope, intent.key); signal.throwIfAborted() }
            // Start synchronously under the lock, leaving no await gap after persisting the key.
            // Wrapping the promise releases the lock without waiting for the network response.
            return { response: send(intent.key) }
        }, signal)
        const result = await started.response
        await resolveIntent(scope, result)
        return result
    } catch (error) {
        // Only a proved admission rejection may clear the pointer. Timeout/abort/404 lookup cannot.
        const code = getApiErrorCode(error)
        if (intent && code && ['INVALID_REQUEST', 'ACCESS_DENIED', 'NOT_FOUND', 'CRM_CONTEXT_CHANGED', 'CRM_VERSION_CONFLICT',
            'CRM_CAPABILITY_UNVERIFIED', 'CRM_CONFIGURATION_REQUIRED', 'CRM_REAUTH_REQUIRED', 'CRM_OPERATION_CONFLICT', 'CRM_PROJECT_ARCHIVED'].includes(code)
            ) await locked(scope, () => clearIntent(scope, intent!.key))
        throw error
    }
}
