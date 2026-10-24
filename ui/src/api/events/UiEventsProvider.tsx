import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { getAccessToken } from '../../auth/authStorage'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { InvalidationContext } from './InvalidationContext'
import { InvalidationCoordinator } from './invalidationCoordinator'
import { startUiEventStream } from './uiEventStream'
import { affectsWorkspace, isAccessEvent, UiScopeContext } from './uiScope'
import { RequestBudget } from './requestBudget'
import { recoveryPolicy, type UiRecoveryPolicy } from './recoveryPolicy'
import { UiRecovery } from './uiRecovery'
import { NotificationFeed, NotificationFeedContext } from './NotificationFeedContext'

export function UiEventsProvider({ children, policy: overrides }: { children: ReactNode; policy?: Partial<UiRecoveryPolicy> }) {
    const { accessToken, currentEmployee, sessionEpoch, employeeWorkspaces, logout, synchronizeSession, refreshCurrentUser, invalidateIdentity } = useAuth()
    const { currentWorkspace, refreshWorkspaces, invalidateWorkspaces } = useWorkspace()
    const workspaceId = currentWorkspace?.id ?? null
    const supervisor = employeeWorkspaces?.find(item => item.workspaceId === workspaceId)?.isSupervisor
    const policy = useMemo(() => recoveryPolicy(overrides), [overrides])
    const recovery = useRef<UiRecovery>()
    const feed = useMemo(() => new NotificationFeed(), [accessToken, sessionEpoch, currentEmployee?.id,
        currentEmployee?.organizationId, currentEmployee?.role, workspaceId, supervisor, policy])
    const online = () => typeof navigator === 'undefined' || navigator.onLine !== false
    const budget = useMemo(() => policy.enabled
        ? new RequestBudget(policy.maxConcurrent, policy.maxQueued, policy.requestSpacingMs) : undefined,
    [policy, accessToken, sessionEpoch, currentEmployee?.id, currentEmployee?.organizationId, currentEmployee?.role, workspaceId, supervisor])
    // A fresh coordinator prevents work from the old identity/scope reaching new subscriptions.
    const coordinator = useMemo(() => new InvalidationCoordinator(() => !document.hidden && online(), 100, 250, 30_000,
        { budget, retryMs: policy.retryMs, retryMaxMs: policy.retryMaxMs, jitter: policy.jitter,
            onHealth: healthy => { recovery.current?.health(healthy); feed.health(healthy) } }),
        [accessToken, sessionEpoch, currentEmployee?.id, currentEmployee?.organizationId, currentEmployee?.role, workspaceId, supervisor, budget, policy])
    const currentScope = useRef<object>()
    const scope = useMemo(() => {
        const value = { owner: coordinator, workspaceId, active: true, accessVersion: 0, requestBudget: budget, managedPolling: policy.enabled,
            historyAccount: currentEmployee ? currentEmployee.organizationId + ':' + currentEmployee.id : undefined,
            isCurrent: () => value.active && currentScope.current === value && getAccessToken() === accessToken }
        return value
    }, [coordinator, workspaceId, accessToken, budget, policy])
    currentScope.current = scope
    useEffect(() => {
        if (!accessToken || !currentEmployee) return
        scope.active = true
        budget?.resume()
        const controller = new UiRecovery(coordinator, policy)
        recovery.current = controller
        const syncBudget = () => {
            if (online() && !document.hidden) budget?.resume()
            else budget?.pause()
        }
        const connectivity = () => {
            syncBudget()
            controller.connectivity(online())
        }
        controller.visibility(!document.hidden); connectivity()
        const unsubscribeIdentity = coordinator.subscribe(event => isAccessEvent(event)
            || event.type === 'workspace.invalidated' || event.type === 'employees.invalidated', async signal => {
            await refreshCurrentUser(signal)
            if (!signal.aborted) await refreshWorkspaces(signal)
        })
        const stream = startUiEventStream({
            getToken: getAccessToken, workspaceId,
            onReady: async (event, signal) => {
                const revision = feed.begin(event.stream?.alertsAfter ?? '')
                const success = await coordinator.reconcileAvailable(signal, true)
                if (!signal.aborted) feed.finish(revision, success)
                controller.health(success)
                return success
            },
            onEvent: event => {
                if (isAccessEvent(event)) {
                    feed.invalidateAlerts()
                    // Control events also invalidate reads started outside this coordinator.
                    invalidateIdentity()
                    invalidateWorkspaces()
                    if (affectsWorkspace(event, workspaceId)) scope.accessVersion++
                    coordinator.revoke(event)
                }
                else {
                    // Read/resolve on another client closes transient alerts immediately.
                    if (event.type === 'notifications.invalidated') feed.invalidateAlerts()
                    coordinator.invalidate(event)
                    if (event.type === 'notifications.created' && event.resource?.kind === 'notification') feed.created(event.resource.id)
                }
            },
            onResync: () => controller.request(),
            onState: state => {
                if (!['open', 'degraded', 'reconciling'].includes(state)) feed.reset()
                controller.state(state)
                if (getAccessToken() !== accessToken) { synchronizeSession(); return }
                if (state === 'unauthorized') logout()
                else if (state === 'forbidden') {
                    invalidateIdentity(); invalidateWorkspaces()
                    scope.accessVersion++; coordinator.revoke(); coordinator.invalidate()
                }
                else if (state === 'session-changed') synchronizeSession()
            },
        })
        const visible = () => { syncBudget(); controller.visibility(!document.hidden) }
        document.addEventListener('visibilitychange', visible)
        window.addEventListener('online', connectivity); window.addEventListener('offline', connectivity)
        const foregroundPush = (event: Event) => {
            const id = (event as CustomEvent<{ notificationId?: unknown }>).detail?.notificationId
            if (typeof id === 'string' && /^[0-9a-f-]{36}$/i.test(id)) feed.created(id, 'push')
        }
        window.addEventListener('clientbus:foreground-notification', foregroundPush)
        return () => {
            scope.active = false; feed.reset(); controller.stop(); stream.stop(); unsubscribeIdentity(); coordinator.close(); budget?.close()
            if (recovery.current === controller) recovery.current = undefined
            document.removeEventListener('visibilitychange', visible)
            window.removeEventListener('online', connectivity); window.removeEventListener('offline', connectivity)
            window.removeEventListener('clientbus:foreground-notification', foregroundPush)
        }
    }, [accessToken, currentEmployee?.id, workspaceId, coordinator, scope, refreshCurrentUser, refreshWorkspaces,
        invalidateIdentity, invalidateWorkspaces, logout, synchronizeSession, budget, policy, feed])
    return <UiScopeContext.Provider value={scope}>
        <InvalidationContext.Provider value={coordinator}><NotificationFeedContext.Provider value={feed}>
            {children}
        </NotificationFeedContext.Provider></InvalidationContext.Provider>
    </UiScopeContext.Provider>
}
