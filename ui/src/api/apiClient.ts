import { getAccessToken } from '../auth/authStorage'
import { budgetedFetch } from './events/requestBudget'
import { retryAfterDelay } from './events/retryAfter'

export class ApiError extends Error {
    readonly status: number
    readonly code?: string
    readonly retryAfterMs: number
    readonly retryAt?: string

    constructor(
        status: number,
        message: string,
        code?: string,
        retryAfterMs = 0,
        retryAt?: string,
    ) {
        super(message)

        this.name = 'ApiError'
        this.status = status
        this.code = code
        this.retryAfterMs = retryAfterMs
        this.retryAt = retryAt
    }
}

interface ApiErrorResponse {
    code?: string
    message?: string
    retryAt?: string
}

export function getApiErrorCode(
    error: unknown,
): string | undefined {
    if (
        typeof error !== 'object' ||
        error === null
    ) {
        return undefined
    }

    const candidate =
        error as {
            code?: unknown
        }

    return typeof candidate.code === 'string'
        ? candidate.code
        : undefined
}

export async function apiFetch(
    input: RequestInfo | URL,
    init: RequestInit = {}
): Promise<Response> {
    const token = getAccessToken()

    const headers = new Headers(init.headers)

    if (
        init.body &&
        !(init.body instanceof FormData) &&
        !headers.has('Content-Type')
    ) {
        headers.set(
            'Content-Type',
            'application/json'
        )
    }

    if (token) {
        headers.set(
            'Authorization',
            `Bearer ${token}`
        )
    }

    const response = await budgetedFetch(
        input,
        {
            ...init,
            // REST reads must never be served from the HTTP cache; per-call
            // init.cache still wins when a caller explicitly sets it.
            cache: init.cache ?? 'no-store',
            headers
        }
    )

    if (!response.ok) {
        if (response.status === 401 && !init.signal?.aborted && token && token === getAccessToken()
            && String(input) !== '/api/auth/login' && typeof window !== 'undefined') {
            window.dispatchEvent(new Event('clientbus:session-expired'))
        }
        let message =
            `Request failed with status ${response.status}`

        let code: string | undefined
        let retryAt: string | undefined

        try {
            const body =
                await response.json() as ApiErrorResponse

            code = body.code
            if (typeof body.retryAt === 'string' && Number.isFinite(Date.parse(body.retryAt))) retryAt = body.retryAt

            if (body.message) {
                message = body.message
            }
        } catch {
            // Response does not contain JSON.
        }

        throw new ApiError(
            response.status,
            message,
            code,
            retryAfterDelay(response.headers.get('Retry-After')),
            retryAt,
        )
    }

    return response
}

export function getApiErrorStatus(error: unknown): number | undefined {
    if (typeof error !== 'object' || error === null) return undefined
    const status = (error as { status?: unknown }).status
    return typeof status === 'number' ? status : undefined
}
