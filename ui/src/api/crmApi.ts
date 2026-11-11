import { apiFetch, ApiError } from './apiClient'

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
    zoneRevision: number; contextVersion: number; enabled: boolean; archived: boolean; connectionState: CrmConnection['status']
    version: number; capabilities: { code: string; availability: string; reasonCode: string | null }[]
}
export interface CrmProjectCheck { externalId: string; displayName: string; currency: string | null; zoneId: string | null }
export interface CrmMaster {
    id: string; projectId: string; externalId: string; displayName: string; employeeId: string | null
    mappingUpdatedAt: string | null; mappingUpdatedBy: string | null; mappingPreviousId: string | null
    mappingReason: string | null; lastObservedAt: string | null; observationState?: 'OBSERVED' | 'NOT_OBSERVED' | 'UNKNOWN'; version: number
}
export interface CrmSyncState {
    retryAt?: string | null
    dataset: string; status: string; lastAttemptAt: string | null; lastSuccessAt: string | null
    freshness: 'NEVER_LOADED' | 'CURRENT' | 'STALE'; completeness: string; errorCode: string | null; generation: number
    attemptCoverage?: { projectErrorCode?: string }
    scope?: Record<string, unknown>; successfulCoverage?: Record<string, unknown>
}
export interface CrmClient {
    id: string; projectId: string; externalId: string; identityKind: string; displayName: string
    contacts: Record<string, string>; normalizedPhone: string | null; clientId: string | null
    mappingUpdatedAt: string | null; mappingUpdatedBy: string | null; mappingPreviousId: string | null
    mappingReason: string | null; lastObservedAt: string | null; version: number
}
export interface CrmClientCandidate { id: string; firstName: string; lastName: string; phoneList: string[]; phoneMatch: boolean }
export interface CrmClientLink { client: CrmClient; project: CrmProject }
export interface CrmAppointmentClient { id: string; displayName: string; phone: string | null }
export interface CrmWorkInterval { id: string; projectId: string; masterId: string; localStart: string; localEnd: string; kind: string; externalRef: string | null; lastObservedAt: string | null; version: number }
export interface CrmAppointment {
    id: string; projectId: string; workspaceId: string; externalId: string; kind: string; crmClientId: string | null; clientId: string | null
    clientIdentityKind: string | null; externalClientId: string | null
    clientSnapshot: Record<string, unknown>; localStart: string; localEnd: string | null; zoneId: string | null
    startAt: string | null; endAt: string | null; appointmentState: string; confirmationState: string; attendanceState: string; paymentState: string
    comment: string | null; services: { id: string; displayName: string; price: string | null; currency: string | null; durationSeconds: number | null }[]
    masters: { id: string; masterId: string | null; displayName: string; localStart: string | null; localEnd: string | null }[]
    servicesComplete: boolean; mastersComplete: boolean; observationState: string; lastObservedAt: string | null
    requiresVerification: boolean; warnings: string[]; version: number
}
export interface CrmCoverage { projectId: string; workspaceId: string; scopes: CrmSyncState[] }
export interface CrmReadPage<T> { items: T[]; nextCursor: string | null; hasMore: boolean; revision: number | string; dataState: CrmSyncState; coverage?: CrmCoverage[] }
export interface CrmPeriod { dateFrom: string; dateTo: string }
export interface CrmAppointmentQuery extends CrmPeriod { masterId?: string; crmClientId?: string; externalClientId?: string; workspaceId?: string; kind?: string; appointmentState?: string; confirmationState?: string; attendanceState?: string; paymentState?: string; cursor?: string }
export interface CrmFinance { appointmentId: string; snapshot: Record<string, unknown> | null; observedAt: string | null; completeness: string; sync: CrmSyncState }
export interface CrmOperation {
    retryAt?: string | null
    id: string; projectId: string; type: 'CREATE' | 'MOVE' | 'CANCEL'; appointmentId: string | null
    initiatorEmployeeId: string; idempotencyKey: string; contextVersion: number; zoneRevision: number
    status: 'REQUESTED' | 'IN_FLIGHT' | 'CONFIRMED' | 'REJECTED' | 'UNKNOWN'
    createdAt: string; updatedAt: string; errorCode: string | null; externalAppointmentId: string | null
}
export interface CrmOperationHistory { operation: CrmOperation; authorName: string; oldStart: string | null; newStart: string | null; cancelComment: string | null; zoneId: string }
export interface CrmOperationHistoryPage { items: CrmOperationHistory[]; nextCursor: string | null; hasMore: boolean }
export interface CrmOperationPage { items: CrmOperation[]; nextCursor: string | null; hasMore: boolean }
export interface CrmOfferSelection { id: string; expectedVersion: number }
export interface CrmCreateAppointment {
    contextVersion: number; zoneRevision: number; crmClientId: string; masterId: string
    offers: CrmOfferSelection[]; localStart: string; comment?: string
}
export interface CrmChangeAppointment { contextVersion: number; zoneRevision: number; expectedVersion: number; localStart?: string; reason?: string }
export interface CrmAvailability { projectId: string; masterId: string; starts: string[]; zoneId: string; contextVersion: number; zoneRevision: number; observedAt: string }
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
const queryString = (value: object) => new URLSearchParams(Object.entries(value).filter(([, item]) => item !== undefined && item !== null && item !== '').map(([key, item]) => [key, String(item)]))
export const getCrmClients = async (id: string, query: { q?: string; unlinked?: boolean; cursor?: string }, signal?: AbortSignal): Promise<CrmReadPage<CrmClient>> => {
    const result = await json<Omit<CrmReadPage<CrmClient>, 'dataState'> & { syncState: CrmSyncState }>(`/api/crm/projects/${path(id)}/clients?${queryString(query)}`, { signal })
    return { ...result, dataState: result.syncState }
}
export const getCrmClient = (id: string, signal?: AbortSignal) => json<CrmClient>(`/api/crm/clients/${path(id)}`, { signal })
export const getCrmClientCandidates = (id: string, q: string, signal?: AbortSignal) => json<CrmClientCandidate[]>(`/api/crm/clients/${path(id)}/client-candidates?${queryString({ q })}`, { signal })
export const linkCrmClient = (row: CrmClient, clientId: string | null, signal?: AbortSignal) => clientId
    ? json<CrmClient>(`/api/crm/clients/${path(row.id)}/client-link`, body('PUT', { clientId, expectedVersion: row.version }, signal))
    : json<CrmClient>(`/api/crm/clients/${path(row.id)}/client-link?expectedVersion=${row.version}`, { method: 'DELETE', signal })
export const createLinkedCrmClient = (row: CrmClient, value: { firstName: string; lastName: string; phoneList: string[] }, signal?: AbortSignal) =>
    json<CrmClient>(`/api/crm/clients/${path(row.id)}/client`, body('POST', { ...value, expectedVersion: row.version }, signal))
export const getClientCrmLinks = (id: string, workspaceId?: string, signal?: AbortSignal) => json<CrmClientLink[]>(`/api/crm/clientbus-clients/${path(id)}/links?${queryString({ workspaceId })}`, { signal })
export const refreshCrmData = (id: string, value: { dataset: string; masterId?: string; crmClientId?: string; force?: boolean } & Partial<CrmPeriod>, signal?: AbortSignal) =>
    json<CrmSyncState>(`/api/crm/projects/${path(id)}/refresh`, body('POST', value, signal))
export const getCrmWorkIntervals = (id: string, query: CrmPeriod & { masterId: string; cursor?: string }, signal?: AbortSignal) =>
    json<CrmReadPage<CrmWorkInterval>>(`/api/crm/projects/${path(id)}/work-intervals?${queryString(query)}`, { signal })
const unknownState: CrmSyncState = { dataset: 'APPOINTMENTS', status: 'IDLE', lastAttemptAt: null, lastSuccessAt: null, freshness: 'NEVER_LOADED', completeness: 'UNKNOWN', errorCode: null, generation: 0 }
async function appointments(url: string, query: CrmAppointmentQuery, signal?: AbortSignal): Promise<CrmReadPage<CrmAppointment>> {
    const result = await json<Omit<CrmReadPage<CrmAppointment>, 'dataState'>>(url + '?' + queryString(query), { signal })
    // Coverage is displayed per scope. A populated list alone never proves completeness.
    return { ...result, dataState: unknownState }
}
export const getCrmAppointments = (id: string, query: CrmAppointmentQuery, signal?: AbortSignal) => appointments(`/api/crm/projects/${path(id)}/appointments`, query, signal)
export const getClientCrmAppointments = (id: string, query: CrmAppointmentQuery, signal?: AbortSignal) => appointments(`/api/clients/${path(id)}/crm/appointments`, query, signal)
export const getCrmAppointment = (id: string, signal?: AbortSignal) => json<CrmAppointment>(`/api/crm/appointments/${path(id)}`, { signal })
export const getCrmAppointmentClients = (id: string, query: CrmPeriod & { q?: string; externalClientId?: string; cursor?: string }, signal?: AbortSignal) =>
    json<CrmReadPage<CrmAppointmentClient>>(`/api/crm/projects/${path(id)}/appointment-clients?${queryString(query)}`, { signal })
export const getCrmFinance = (id: string, signal?: AbortSignal) => json<CrmFinance>(`/api/crm/appointments/${path(id)}/finance`, { signal })
export const refreshCrmFinance = (id: string, signal?: AbortSignal) => json<CrmFinance>(`/api/crm/appointments/${path(id)}/finance/refresh`, { method: 'POST', signal })
export const getCrmOperations = (id: string, query: { own?: boolean; active?: boolean; cursor?: string } = {}, signal?: AbortSignal) =>
    json<CrmOperationPage>(`/api/crm/projects/${path(id)}/operations?${queryString(query)}`, { signal })
export const getCrmAppointmentHistory = (id: string, query: { own?: boolean; cursor?: string } = {}, signal?: AbortSignal) =>
    json<CrmOperationHistoryPage>(`/api/crm/appointments/${path(id)}/history?${queryString(query)}`, { signal })
export const getCrmPendingCreations = (id: string, query: { own?: boolean; cursor?: string } = {}, signal?: AbortSignal) =>
    json<CrmOperationPage>(`/api/crm/projects/${path(id)}/pending-creations?${queryString(query)}`, { signal })
export const getCrmOperation = (id: string, signal?: AbortSignal) => json<CrmOperation>(`/api/crm/operations/${path(id)}`, { signal })
export const findCrmOperation = (id: string, key: string, signal?: AbortSignal) => json<CrmOperation>(`/api/crm/projects/${path(id)}/operations/by-key/${path(key)}`, { signal })
export const reconcileCrmOperation = (id: string, signal?: AbortSignal) => json<CrmOperation>(`/api/crm/operations/${path(id)}/reconcile`, { method: 'POST', signal })
export const createCrmAppointment = (id: string, key: string, value: CrmCreateAppointment, signal?: AbortSignal) =>
    json<CrmOperation>(`/api/crm/projects/${path(id)}/appointments`, { ...body('POST', value, signal), headers: { 'Idempotency-Key': key } })
export const changeCrmAppointment = (id: string, key: string, type: 'MOVE' | 'CANCEL', value: CrmChangeAppointment, signal?: AbortSignal) =>
    json<CrmOperation>(`/api/crm/appointments/${path(id)}/${type === 'MOVE' ? 'move' : 'cancel'}`, { ...body('POST', value, signal), headers: { 'Idempotency-Key': key } })
export const getCrmAvailability = (id: string, value: { contextVersion: number; zoneRevision: number; masterId: string; offers: CrmOfferSelection[]; date: string }, signal?: AbortSignal) =>
    json<CrmAvailability>(`/api/crm/projects/${path(id)}/availability`, body('POST', value, signal))
export const getCrmMoveAvailability = (id: string, value: { contextVersion: number; zoneRevision: number; expectedVersion: number; date: string }, signal?: AbortSignal) =>
    json<CrmAvailability>(`/api/crm/appointments/${path(id)}/availability`, body('POST', value, signal))
export const getCrmTypes = (signal?: AbortSignal) => json<CrmType[]>('/api/crm/types', { signal })
export const getCrmConnections = (signal?: AbortSignal) => json<CrmConnection[]>('/api/crm/connections', { signal })
export const createCrmConnection = (value: { provider: CrmType; login: string; secret: string; displayName?: string }, signal?: AbortSignal) =>
    json<CrmConnection>('/api/crm/connections', body('POST', value, signal))
export const patchCrmConnection = (id: string, value: { expectedVersion: number; login?: string; secret?: string; enabled?: boolean; displayName?: string }, signal?: AbortSignal) =>
    json<CrmConnection>(`/api/crm/connections/${path(id)}`, body('PATCH', value, signal))
export const verifyCrmConnection = (id: string, signal?: AbortSignal) =>
    json<CrmConnection>(`/api/crm/connections/${path(id)}/verify`, { method: 'POST', signal })
export const deleteCrmConnection = async (id: string, expectedVersion: number, signal?: AbortSignal): Promise<void> => {
    await apiFetch(`/api/crm/connections/${path(id)}?expectedVersion=${expectedVersion}`, { method: 'DELETE', signal })
}
export const checkCrmProject = (id: string, externalProjectId: string, signal?: AbortSignal) =>
    json<CrmProjectCheck>(`/api/crm/connections/${path(id)}/project-checks`, body('POST', { externalProjectId }, signal))
export const getAvailableCrmProjects = (id: string, workspaceId: string, signal?: AbortSignal) =>
    json<CrmProjectCheck[]>(`/api/crm/connections/${path(id)}/projects?workspaceId=${path(workspaceId)}`, { signal })
export const getCrmProjects = (workspaceId: string, signal?: AbortSignal) =>
    json<CrmProject[]>(`/api/crm/workspaces/${path(workspaceId)}/projects`, { signal })
export const getOrganizationCrmProjects = (signal?: AbortSignal) => json<CrmProject[]>('/api/crm/projects', { signal })
export const bindCrmProject = (workspaceId: string, value: { connectionId: string; externalProjectId: string; zoneId?: string; expectedArchiveVersion?: number }, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/workspaces/${path(workspaceId)}/projects`, body('POST', value, signal))
export const patchCrmProject = (id: string, value: { expectedVersion: number; enabled?: boolean; zoneId?: string }, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/projects/${path(id)}`, body('PATCH', value, signal))
export const changeCrmProjectConnection = (id: string, connectionId: string, expectedVersion: number, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/projects/${path(id)}/connection`, body('PUT', { connectionId, expectedVersion }, signal))
export const detachCrmProject = (id: string, expectedVersion: number, signal?: AbortSignal) =>
    json<CrmProject>(`/api/crm/projects/${path(id)}/detach?expectedVersion=${expectedVersion}`, { method: 'POST', signal })
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
    const message = error instanceof Error ? error.message : 'Не удалось выполнить действие CRM.'
    return error instanceof ApiError && error.retryAt ? `${message} Повторить после ${new Date(error.retryAt).toLocaleString('ru-RU')}.` : message
}
export const crmAccountName = (connection: CrmConnection) => connection.displayName || `${connection.provider} · ${connection.loginHint}`
