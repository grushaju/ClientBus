const TIME_ZONE_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i
const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/

// A calendar day is local to the browser. The API uses inclusive Instant bounds.
export function toFilterInstant(value: string, boundary: 'start' | 'end' = 'start'): string | null {
    if (!value) return null
    const parts = DATE_ONLY.exec(value)
    if (parts) {
        const year = Number(parts[1]), month = Number(parts[2]), day = Number(parts[3])
        const date = new Date(0)
        date.setFullYear(year, month - 1, day)
        date.setHours(0, 0, 0, 0)
        if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
            throw new Error('Укажите корректные даты активности.')
        }
        if (boundary === 'end') {
            // Advance by a calendar day, not 24 hours: DST days may differ.
            date.setDate(date.getDate() + 1)
            date.setHours(0, 0, 0, 0)
            date.setMilliseconds(-1)
            // PostgreSQL timestamps have microsecond precision. Include the
            // entire last second without rounding the bound into the next day.
            return date.toISOString().replace(/999Z$/, '999999Z')
        }
        return date.toISOString()
    }
    // Compatibility for callers with an existing timestamp. Restored editor
    // criteria are converted to calendar dates in criteriaFromDefinition.
    const date = new Date(value)
    if (!Number.isFinite(date.getTime())) throw new Error('Укажите корректные даты активности.')
    return TIME_ZONE_SUFFIX.test(value) ? value : date.toISOString()
}

export function toLocalDateInput(value: string): string {
    if (!value || DATE_ONLY.test(value)) return value
    const date = new Date(value)
    // Keep malformed data for validation instead of silently dropping a bound.
    if (!Number.isFinite(date.getTime())) return value
    const pad = (part: number, digits = 2) => String(part).padStart(digits, '0')
    return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
