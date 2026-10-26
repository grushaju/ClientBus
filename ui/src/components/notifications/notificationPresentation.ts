import type { NotificationDto, NotificationType } from '../../api/types/notification'
import type { ChannelType } from '../../api/types/channel'
import { getChannelTypeLabel } from '../channels/channelLabels'

const labels: Record<NotificationType, string> = {
    ASSIGNMENT: 'Вам назначен диалог', INCOMING_MESSAGE: 'Новое входящее сообщение',
    OUTBOUND_FAILED: 'Не удалось отправить сообщение', CHANNEL_ACTION_REQUIRED: 'Канал требует внимания',
    MANUAL_OPERATION_RESULT: 'Операция завершена',
}
export const notificationTitle = (item: NotificationDto): string => labels[item.type] ?? 'Новое уведомление'
const text = (value: unknown) => typeof value === 'string' ? value.trim() : ''
export function notificationContact(item: NotificationDto): string | null {
    if (item.target.kind !== 'CONVERSATION') return null
    return text(item.templateArgs?.senderName) || 'Контакт без имени'
}
export function notificationSource(item: NotificationDto): string | null {
    const type = text(item.templateArgs?.channelType)
    const label = getChannelTypeLabel(type as ChannelType)
    return [label, text(item.templateArgs?.channelName)].filter(Boolean).join(' · ') || null
}
export function notificationRoute(item: NotificationDto): string | null {
    if (item.target.kind === 'CONVERSATION') return '/inbox/' + encodeURIComponent(item.target.id)
    if (item.target.kind === 'CHANNEL_ACCOUNT') return '/channels?accountId=' + encodeURIComponent(item.target.id)
    return null
}
export function canAlert(item: NotificationDto, alertsAfter: number, now = Date.now()): boolean {
    return !item.readAt && item.alertActive && Date.parse(item.createdAt) > alertsAfter && Date.parse(item.alertExpiresAt) > now
}
export function isVisibleNotificationConversation(item: NotificationDto, pathname: string): boolean {
    if (item.type !== 'INCOMING_MESSAGE' || item.target.kind !== 'CONVERSATION'
        || pathname !== '/inbox/' + item.target.id || typeof document === 'undefined' || document.hidden) return false
    if (!document.querySelector('.conversation-view')) return false
    const mobile = typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches
    return !mobile || !document.querySelector('.inbox-layout[data-mobile-list="true"]')
}
