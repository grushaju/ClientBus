export type HistoryOperationStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export interface HistorySyncRequest { beforeExternalId: string | null; limit: number }
/** Messages are read from the regular conversation REST after completion. */
export interface HistorySyncResultDto { importedCount: number; historyStartReached: boolean }
export interface HistorySyncOperationDto {
    id: string
    conversationId: string
    status: HistoryOperationStatus
    createdAt: string
    updatedAt: string
    deadlineAt: string
    result: HistorySyncResultDto | null
    errorCode: string | null
}
export interface HistorySyncOperationPageDto {
    items: HistorySyncOperationDto[]
    nextCursor: string | null
}
