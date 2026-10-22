export type UiResourceKind = 'conversation' | 'notification' | 'client' | 'channel'
    | 'workspace' | 'employee' | 'group' | 'filter' | 'history-operation'
export interface UiResourceRef { kind: UiResourceKind; id: string }
export type KnownUiEventType = 'stream.ready' | 'stream.heartbeat' | 'stream.resync-required'
    | 'messages.invalidated' | 'inbox.invalidated' | 'groups.invalidated'
    | 'filters.invalidated' | 'clients.invalidated' | 'channels.invalidated'
    | 'workspace.invalidated' | 'employees.invalidated' | 'identity.invalidated'
    | 'access.invalidated' | 'notifications.created' | 'notifications.invalidated'
    | 'history-operation.invalidated'
export interface StreamReadyDto {
    streamId: string
    alertsAfter: string
    heartbeatSeconds: number
}
export interface UiEventEnvelope {
    eventId: string
    schemaVersion: number
    type: string // Unknown types are handled by reconciliation, not a failing enum parser.
    occurredAt: string
    workspaceId: string | null
    resource: UiResourceRef | null
    correlationId: string | null
    stream: StreamReadyDto | null
}
