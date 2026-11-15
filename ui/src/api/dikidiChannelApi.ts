import { apiFetch } from './apiClient'

/**
 * 13.5: канал DIKIDI провизионится DIKIDI-CRM-проектом; ручное управление —
 * только подключение/отключение. Проверка доступа проходит на сервере
 * (оболочка owner-чата + список диалогов), ответ — результат проверки.
 */
export interface DikidiChannelActionResult {
    available: boolean
    errorCode: string | null
}

export async function connectDikidiChannel(
    channelAccountId: string,
): Promise<DikidiChannelActionResult> {
    const response = await apiFetch(
        `/api/channels/${channelAccountId}/dikidi/connect`,
        {
            method: 'POST',
        },
    )

    return response.json()
}

export async function disconnectDikidiChannel(
    channelAccountId: string,
): Promise<void> {
    await apiFetch(
        `/api/channels/${channelAccountId}/dikidi/disconnect`,
        {
            method: 'POST',
        },
    )
}
