import { apiFetch } from './apiClient'

export type CrmType = 'DIKIDI' | 'AMO' | 'BITRIX'
export interface CrmConnection {
    displayName?: string | null
    id: string; provider: CrmType; loginHint: string; credentialConfigured: boolean
    status: 'CONFIGURED' | 'CONNECTED' | 'REAUTH_REQUIRED' | 'DEGRADED' | 'DISABLED'
    lastCheckedAt: string | null; errorCode: string | null; version: number
}
export interface CrmProject {
    id: string; connectionId: string; workspaceId: string; provider: CrmType; externalId: string
    displayName: string; currency: string | null; zoneId: string | null; zoneSource: string | null
    zoneRevision: number; contextVersion: number; enabled: boolean; connectionState: CrmConnection['status']
    version: number; capabilities: { code: string; availability: string; reasonCode: string | null }[]
}
export interface CrmProjectCheck { externalId: string; displayName: string; currency: string | null; zoneId: string | null }
export interface CrmMaster {
    id: string; projectId: string; externalId: string; displayName: string; employeeId: string | null
    mappingUpdatedAt: string | null; mappingUpdatedBy: string | null; mappingPreviousId: string | null
    mappingReason: string | null; lastObservedAt: string | null; observationState?: 'OBSERVED' | 'NOT_OBSERVED' | 'UNKNOWN'; version: number
}
export interface CrmSyncState {
    dataset: string; status: string; lastAttemptAt: string | null; lastSuccessAt: string | null
    freshness: 'NEVER_LOADED' | 'CURRENT' | 'STALE'; completeness: string; errorCode: string | null; generation: number
}
export interface CrmMasterPage { items: CrmMaster[]; nextCursor: string | null; hasMore: boolean; dataState: CrmSyncState; revision: number }
export interface CrmServiceOffer {
    masterDisplayName: string
    id: string; masterId: string; externalMasterId: string; serviceId: string
    price: string | null; currency: string | null; durationSeconds: number | null
    offerState: { active?: boolean; online?: boolean }; attributes: Record<string, unknown>
    observationState: string; lastObservedAt: string; warnings: string[]; version: number
}
export interface CrmService {
    id: string; projectId: string; externalId: string; displayName: string; category: string | null
    attributes: Record<string, unknown>; offers: CrmServiceOffer[]; observationState: string; lastObservedAt: string; version: number
}
export interface CrmServicePage { items: CrmService[]; nextCursor: string | null; hasMore: boolean; dataState: CrmSyncState; revision: number }
export interface CrmEmployeeCandidate { id: string; displayName: string; username: string; enabled: boolean }
const json = async <T>(url: string, init?: RequestInit): Promise<T> => (await apiFetch(url, init)).json()
const body = (method: string, value: unknown, signal?: AbortSignal): RequestInit => ({ method, body: JSON.stringify(value), signal })
const path = (id: string) => encodeURIComponent(id)
export const getCrmTypes = (signal?: AbortSignal) => json<CrmType[]>('/api/crm/types', { signal })
export const getCrmConnections = (signal?: AbortSignal) => json<CrmConnection[]>('/api/crm/connections', { signal })
export const createCrmConnection = (value: { provider: CrmType; login: string; secret: string; displayName?: string }, signal?: AbortSignal) =>
    json<CrmConnection>('/api/crm/connections', body('POST', value, signal))
export const patchCrmConnection = (id: string, value: { expectedVersion: number; login?: string; secret?: string; enabled?: boolean; displayName?: string }, signal?: AbortSignal) =>
    json<CrmConnection>(`/api/crm/connections/${path(id)}`, body('PATCH', value, signal))
export const verifyCrmConnection = (id: string, signal?: AbortSignal) =>
    json<CrmConnection>(`/api/crm/connections/${path(id)}/verify`, { method: 'POST', signal })
export const checkCrmProject = (id: string, externalProjectId: string, signal?: AbortSignal) =>
    json<CrmProjectCheck>(`/api/crm/connections/${path(id)}/project-checks`, body('POST', { externalProjectId }, signal))
export const getCrmProjects = (workspaceId: string, signal?: AbortSignal) =>
    json<CrmProject[]>(`/api/crm/workspaces/${path(workspaceId)}/projects`, { signal })
export const getOrganizationCrmProjects = (signal?: AbortSignal) => json<CrmProject[]>('/api/crm/projects', { signal })
export const bindCrmProject = (workspaceId: string, value: { connectionId: string; externalProjectId: string; zoneId?: string }, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/workspaces/${path(workspaceId)}/projects`, body('POST', value, signal))
export const patchCrmProject = (id: string, value: { expectedVersion: number; enabled?: boolean; zoneId?: string }, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/projects/${path(id)}`, body('PATCH', value, signal))
export const changeCrmProjectConnection = (id: string, connectionId: string, expectedVersion: number, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/projects/${path(id)}/connection`, body('PUT', { connectionId, expectedVersion }, signal))
export const getCrmMasters = (id: string, signal?: AbortSignal, cursor?: string | null,
    query: { q?: string; unlinked?: boolean; includeNotObserved?: boolean } = {}) => {
    const params = new URLSearchParams()
    if (cursor) params.set('cursor', cursor)
    if (query.q) params.set('q', query.q)
    if (query.unlinked) params.set('unlinked', 'true')
    if (query.includeNotObserved !== undefined) params.set('includeNotObserved', String(query.includeNotObserved))
    return json<CrmMasterPage>(`/api/crm/projects/${path(id)}/masters${params.size ? '?' + params : ''}`, { signal })
}
export const getCrmMaster = (id: string, signal?: AbortSignal) => json<CrmMaster>(`/api/crm/masters/${path(id)}`, { signal })
export const getCrmEmployeeCandidates = (id: string, signal?: AbortSignal) =>
    json<CrmEmployeeCandidate[]>(`/api/crm/projects/${path(id)}/employee-candidates`, { signal })
export const refreshCrmMasters = (id: string, signal?: AbortSignal) =>
    json<CrmSyncState>(`/api/crm/projects/${path(id)}/refresh`, body('POST', { dataset: 'MASTERS' }, signal))
export const refreshCrmCatalog = (id: string, signal?: AbortSignal) =>
    json<CrmSyncState>(`/api/crm/projects/${path(id)}/refresh`, body('POST', { dataset: 'CATALOG' }, signal))
export const getCrmServices = (id: string, query: { masterId?: string; serviceId?: string; includeNotObserved?: boolean; cursor?: string; q?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams()
    if (query.masterId) params.set('masterId', query.masterId)
    if (query.serviceId) params.set('serviceId', query.serviceId)
    if (query.includeNotObserved) params.set('includeNotObserved', 'true')
    if (query.cursor) params.set('cursor', query.cursor)
    if (query.q) params.set('q', query.q)
    return json<CrmServicePage>(`/api/crm/projects/${path(id)}/services${params.size ? '?' + params : ''}`, { signal })
}
export const linkCrmMaster = (master: CrmMaster, employeeId: string | null, signal?: AbortSignal) =>
    employeeId ? json<CrmMaster>(`/api/crm/masters/${path(master.id)}/employee-link`, body('PUT', { employeeId, expectedVersion: master.version }, signal))
        : json<CrmMaster>(`/api/crm/masters/${path(master.id)}/employee-link?expectedVersion=${master.version}`, { method: 'DELETE', signal })

export const crmConnectionLabel = (state: CrmConnection['status']) => ({
    CONFIGURED: 'Не проверено', CONNECTED: 'Подключено', REAUTH_REQUIRED: 'Требуется вход',
    DEGRADED: 'Ошибка подключения', DISABLED: 'Отключено',
}[state])
export function crmError(error: unknown): string {
    return error instanceof Error ? error.message : 'Не удалось выполнить действие CRM.'
}
export const crmAccountName = (connection: CrmConnection) => connection.displayName || `${connection.provider} · ${connection.loginHint}`
