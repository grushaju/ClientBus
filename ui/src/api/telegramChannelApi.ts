import { apiFetch } from './apiClient'

import type {
    ClientAccountDiscoveryDto,
    TelegramAuthorizationStatus,
} from './types/channel'

export async function startTelegramConnection(
    channelAccountId: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/start`,
        {
            method: 'POST',
        },
    )
}

export async function getTelegramConnectionStatus(
    channelAccountId: string,
): Promise<TelegramAuthorizationStatus> {
    const response = await apiFetch(
        `/api/channels/${channelAccountId}/telegram/status`,
    )

    return response.json()
}

export async function submitTelegramCode(
    channelAccountId: string,
    code: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/code`,
        {
            method: 'POST',
            body: JSON.stringify({ code }),
        },
    )
}

export async function submitTelegramPassword(
    channelAccountId: string,
    password: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/password`,
        {
            method: 'POST',
            body: JSON.stringify({ password }),
        },
    )
}

export async function submitTelegramEmail(
    channelAccountId: string,
    email: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/email`,
        {
            method: 'POST',
            body: JSON.stringify({ email }),
        },
    )
}

export async function disableTelegramChannel(
    channelAccountId: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/disable`,
        {
            method: 'POST',
        },
    )
}

export async function enableTelegramChannel(
    channelAccountId: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/telegram/enable`,
        {
            method: 'POST',
        },
    )
}

export async function findTelegramClientAccount(
    channelAccountId: string,
    search: {
        phone?: string
        username?: string
    },
): Promise<ClientAccountDiscoveryDto> {
    const params = new URLSearchParams()

    const phone = search.phone?.trim()
    const username = search.username?.trim()

    if (phone) {
        params.set('phone', phone)
    }

    if (username) {
        params.set(
            'username',
            username.startsWith('@')
                ? username.substring(1)
                : username,
        )
    }

    const query = params.toString()

    const response = await apiFetch(
        `/api/channels/${channelAccountId}/telegram/find${
            query ? `?${query}` : ''
        }`,
    )

    return response.json()
}