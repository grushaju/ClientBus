import {
    apiFetch,
} from '../api/apiClient'

interface LoginRequest {
    username?: string
    email?: string
    password: string
}

interface LoginResponse {
    accessToken: string
    tokenType: string
}

export async function login(
    request: LoginRequest,
): Promise<LoginResponse> {
    const response =
        await apiFetch(
            '/api/auth/login',
            {
                method: 'POST',
                body: JSON.stringify(
                    request,
                ),
            },
        )

    return response.json()
}