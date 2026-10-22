export function retryAfterDelay(value: string | null, now = Date.now(), capMs = 60_000): number {
    if (!value) return 0
    const delay = /^\d+(\.\d+)?$/.test(value) ? Number(value) * 1000 : Date.parse(value) - now
    return Number.isFinite(delay) && delay > 0 ? Math.min(delay, capMs) : 0
}
