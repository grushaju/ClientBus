/** Stage 11.2 contract. Endpoints are enabled in subsequent stages. */
export type NotificationType = 'ASSIGNMENT' | 'INCOMING_MESSAGE' | 'OUTBOUND_FAILED'
    | 'CHANNEL_ACTION_REQUIRED' | 'MANUAL_OPERATION_RESULT'
export type NotificationTargetKind = 'CONVERSATION' | 'CHANNEL_ACCOUNT' | 'HISTORY_OPERATION'
export interface NotificationTargetDto { kind: NotificationTargetKind; id: string }
export interface NotificationDto {
    id: string
    type: NotificationType
    workspaceId: string | null
    target: NotificationTargetDto
    /** REST adds current senderName/channelType/channelName after checking target access. */
    templateArgs: Record<string, string>
    createdAt: string
    readAt: string | null
    alertExpiresAt: string
    alertActive: boolean
}
export interface NotificationPageDto { items: NotificationDto[]; nextCursor: string | null }
export interface NotificationSummaryDto { unreadCount: number }
export interface NotificationPreferencesDto { soundEnabled: boolean }
export interface UpdateNotificationPreferencesRequest { soundEnabled: boolean }
export interface NotificationReadAllResultDto { readAt: string; updatedCount: number }
export interface NotificationClearResultDto { clearedAt: string; deletedCount: number }
export interface NotificationApiErrorDto { code: string; correlationId: string | null }
