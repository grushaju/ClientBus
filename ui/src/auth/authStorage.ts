const ACCESS_TOKEN_KEY =
    'clientbus.accessToken'

export function getAccessToken(): string | null {
    return localStorage.getItem(
        ACCESS_TOKEN_KEY
    )
}

export function setAccessToken(
    token: string
): void {
    localStorage.setItem(
        ACCESS_TOKEN_KEY,
        token
    )
}

export function clearHistoryRecovery(): void {
    try {
        for (let index = sessionStorage.length - 1; index >= 0; index--) {
            const key = sessionStorage.key(index)
            if (key?.startsWith('clientbus.history.pending:')) sessionStorage.removeItem(key)
        }
    } catch { /* Browser storage may be disabled. */ }
}

export function clearAccessToken(): void {
    clearHistoryRecovery()
    localStorage.removeItem(
        ACCESS_TOKEN_KEY
    )
}
