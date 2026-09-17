export interface LoginRequest {
    username?: string
    password: string
    email?: string
}

export interface LoginResponse {
    accessToken: string
    tokenType: string
}

export async function login(
    request: LoginRequest
): Promise<LoginResponse> {
    const response = await fetch(
        '/api/auth/login',
        {
            method: 'POST',
            headers: {
                'Content-Type':
                    'application/json'
            },
            body: JSON.stringify(request)
        }
    )

    if (!response.ok) {
        throw new Error(
            'Invalid username or password'
        )
    }

    return response.json()
}