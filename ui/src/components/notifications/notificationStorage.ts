const ALERT_TTL = 48 * 60 * 60 * 1000
const COMMAND_TTL = 7 * 24 * 60 * 60 * 1000
const MAX_ALERTS = 4096
const memory = new Map<string, Array<{ id: string; expiresAt: number }>>()
const prefix = (account: string, kind: string) => 'clientbus:notifications:' + kind + ':' + account
export const notificationAccount = (organizationId: string, employeeId: string) => encodeURIComponent(organizationId) + ':' + encodeURIComponent(employeeId)
export interface PendingReadAll { key: string; createdAt: number }

/** Only opaque IDs/expiry are persisted. One synchronous claim is shared by SSE and foreground push. */
export function claimNotificationAlert(account: string, id: string, now = Date.now()): boolean {
    const key = prefix(account, 'alerts')
    let values = memory.get(key) ?? []
    try {
        const serialized = localStorage.getItem(key)
        const stored: unknown = serialized === null ? values : JSON.parse(serialized)
        if (Array.isArray(stored)) values = stored.filter(item => item && typeof item.id === 'string'
            && item.id.length <= 128 && typeof item.expiresAt === 'number' && Number.isFinite(item.expiresAt))
    } catch { /* In-memory dedup still protects this session if storage is unavailable. */ }
    values = values.filter(item => item.expiresAt > now && item.expiresAt <= now + ALERT_TTL)
    if (values.some(item => item.id === id)) return false
    values.push({ id, expiresAt: now + ALERT_TTL })
    values = values.slice(-MAX_ALERTS)
    memory.set(key, values)
    try { localStorage.setItem(key, JSON.stringify(values)) } catch { /* Storage is optional for alert display. */ }
    return true
}
export function clearNotificationAlertDedup(account: string): void {
    const key = prefix(account, 'alerts'); memory.delete(key)
    try { localStorage.removeItem(key) } catch { /* Logout must also work with blocked storage. */ }
}
function pendingCommand(account: string, kind: string, now = Date.now()): PendingReadAll | null {
    try {
        const value = JSON.parse(sessionStorage.getItem(prefix(account, kind)) ?? 'null')
        if (value && typeof value.key === 'string' && /^[0-9a-f-]{36}$/i.test(value.key)
            && Number.isFinite(value.createdAt) && value.createdAt <= now && now - value.createdAt < COMMAND_TTL) return value
        sessionStorage.removeItem(prefix(account, kind))
    } catch { /* An unreadable command is never retried. */ }
    return null
}
function beginCommand(account: string, kind: string, now = Date.now()): PendingReadAll {
    const command = { key: crypto.randomUUID(), createdAt: now }
    // A tab owns its pending action, including when a duplicated tab starts a different action.
    // Session storage survives reload/logout; failure to persist prevents an unsafe send.
    sessionStorage.setItem(prefix(account, kind), JSON.stringify(command))
    return command
}
function completeCommand(account: string, kind: string, key: string): void {
    if (pendingCommand(account, kind)?.key === key) sessionStorage.removeItem(prefix(account, kind))
}
export const pendingReadAll = (account: string, now = Date.now()) => pendingCommand(account, 'read-all', now)
export const beginReadAll = (account: string, now = Date.now()) => beginCommand(account, 'read-all', now)
export const completeReadAll = (account: string, key: string) => completeCommand(account, 'read-all', key)
export const pendingClear = (account: string, now = Date.now()) => pendingCommand(account, 'clear', now)
export const beginClear = (account: string, now = Date.now()) => beginCommand(account, 'clear', now)
export const completeClear = (account: string, key: string) => completeCommand(account, 'clear', key)
