import { getAccessToken } from '../auth/authStorage'

export class ApiError extends Error {
    readonly status: number

    constructor(
        status: number,
        message: string
    ) {
        super(message)

        this.name = 'ApiError'
        this.status = status
    }
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

        try {
            const body =
                await response.json()

            if (body?.message) {
                message = body.message
            }
        } catch {
            // Response does not contain JSON.
        }

        throw new ApiError(
            response.status,
            message
        )
    }

    return response
}