export interface UiRecoveryPolicy {
    enabled: boolean
    fallbackMs: number
    fallbackMaxMs: number
    controlMs: number
    jitter: number
    maxConcurrent: number
    maxQueued: number
    requestSpacingMs: number
    retryMs: number
    retryMaxMs: number
}

declare const __UI_RECOVERY_POLICY__: Partial<UiRecoveryPolicy> | undefined
export function recoveryPolicy(overrides: Partial<UiRecoveryPolicy> = {}): UiRecoveryPolicy {
    const configured = typeof __UI_RECOVERY_POLICY__ === 'undefined' ? {} : __UI_RECOVERY_POLICY__
    const value = { enabled: true, fallbackMs: 10_000, fallbackMaxMs: 60_000, controlMs: 120_000,
        jitter: 0.2, maxConcurrent: 4, maxQueued: 128, requestSpacingMs: 100,
        retryMs: 1000, retryMaxMs: 60_000, ...configured, ...overrides }
    for (const [key, amount] of Object.entries(value)) {
        if (key === 'enabled') { if (typeof amount !== 'boolean') throw new Error('Invalid UI recovery flag'); continue }
        if (typeof amount !== 'number' || !Number.isFinite(amount) || amount < 0
            || (key !== 'jitter' && key !== 'requestSpacingMs' && (!Number.isInteger(amount) || amount === 0)))
            throw new Error('Invalid UI recovery limit: ' + key)
    }
    if (value.jitter > 1 || value.fallbackMaxMs < value.fallbackMs || value.retryMaxMs < value.retryMs
        || value.maxConcurrent > 32 || value.maxQueued > 1024 || value.controlMs < value.fallbackMs)
        throw new Error('Inconsistent UI recovery limits')
    return value
}
