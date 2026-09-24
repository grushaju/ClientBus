import { getAccessToken } from '../auth/authStorage'

export class ApiError extends Error {
    readonly status: number
    readonly code?: string

    constructor(
        status: number,
        message: string,
        code?: string
    ) {
        super(message)

        this.name = 'ApiError'
        this.status = status
        this.code = code
    }
}

interface ApiErrorResponse {
    code?: string
    message?: string
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

    const response = await fetch(
        input,
        {
            ...init,
            headers
        }
    )

    if (!response.ok) {
        let message =
            `Request failed with status ${response.status}`

        let code: string | undefined

        try {
            const body =
                await response.json() as ApiErrorResponse

            code = body.code

            if (body.message) {
                message = body.message
            }
        } catch {
            // Response does not contain JSON.
        }

        throw new ApiError(
            response.status,
            message,
            code
        )
    }

    return response
}