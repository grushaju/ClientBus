import { apiFetch } from './apiClient'
import { getAccessToken } from '../auth/authStorage'
import { abortError, withCancellation } from './events/requestScope'
import type { NotificationClearResultDto, NotificationDto, NotificationPageDto, NotificationPreferencesDto, NotificationReadAllResultDto, NotificationSummaryDto, UpdateNotificationPreferencesRequest } from './types/notification'

function request<T>(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<T> {
    const token = getAccessToken()
    return withCancellation(async requestSignal => {
        if (getAccessToken() !== token) throw abortError()
        const response = await apiFetch(path, { ...init, signal: requestSignal, cache: 'no-store' })
        return response.json() as Promise<T>
    }, signal ? [signal] : [])
}
export function getNotifications(unreadOnly = false, pageCursor?: string | null, signal?: AbortSignal): Promise<NotificationPageDto> {
    const query = new URLSearchParams({ unreadOnly: String(unreadOnly), limit: '30' })
    if (pageCursor) query.set('pageCursor', pageCursor)
    return request('/api/notifications?' + query, {}, signal)
}
export function getNotificationSummary(signal?: AbortSignal): Promise<NotificationSummaryDto> {
    return request('/api/notifications/summary', {}, signal)
}
function preferences(value: NotificationPreferencesDto): NotificationPreferencesDto {
    if (!value || typeof value.soundEnabled !== 'boolean') throw new Error('Некорректная настройка звука уведомлений.')
    return { soundEnabled: value.soundEnabled }
}
export async function getNotificationPreferences(signal?: AbortSignal): Promise<NotificationPreferencesDto> {
    return preferences(await request<NotificationPreferencesDto>('/api/notifications/preferences', {}, signal))
}
export async function updateNotificationPreferences(soundEnabled: boolean, signal?: AbortSignal): Promise<NotificationPreferencesDto> {
    const body: UpdateNotificationPreferencesRequest = { soundEnabled }
    return preferences(await request<NotificationPreferencesDto>('/api/notifications/preferences', {
        method: 'PUT', body: JSON.stringify(body),
    }, signal))
}
export function getNotification(id: string, signal?: AbortSignal): Promise<NotificationDto> {
    return request('/api/notifications/' + encodeURIComponent(id), {}, signal)
}
export function readNotification(id: string, signal?: AbortSignal): Promise<NotificationDto> {
    return request('/api/notifications/' + encodeURIComponent(id) + '/read', { method: 'PUT' }, signal)
}
export function readAllNotifications(key: string, signal?: AbortSignal): Promise<NotificationReadAllResultDto> {
    return request('/api/notifications/read-all', { method: 'PUT', headers: { 'Idempotency-Key': key } }, signal)
}
export function clearNotifications(key: string, signal?: AbortSignal): Promise<NotificationClearResultDto> {
    return request('/api/notifications', { method: 'DELETE', headers: { 'Idempotency-Key': key } }, signal)
}
