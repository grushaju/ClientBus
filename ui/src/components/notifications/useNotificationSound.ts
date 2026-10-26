import { useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { getNotificationPreferences, updateNotificationPreferences } from '../../api/notificationApi'
import { useUiResource } from '../../api/events/useUiResource'
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { withCancellation } from '../../api/events/requestScope'
import { isAccessEvent, useUiScope } from '../../api/events/uiScope'
import type { NotificationDto } from '../../api/types/notification'
import type { UiEventEnvelope } from '../../api/types/uiEvent'
import { canAlert } from './notificationPresentation'
import { claimNotificationSound, NotificationAudio, type NotificationAudioState } from './notificationSound'

const matchesPreferences = (event: UiEventEnvelope) => isAccessEvent(event) || event.type === 'notifications.invalidated'

export function useNotificationSound(account: string) {
    const { accessToken, sessionEpoch } = useAuth()
    const scope = useUiScope(), lifetime = useUiLifetime('notification-preferences:' + account)
    const owner = useMemo(() => ({ account, scope }), [account, scope])
    const audioOwner = useMemo(() => ({ account }), [account, accessToken, sessionEpoch])
    const backoff = useMemo(() => ({ retryAt: 0, failures: 0 }), [audioOwner])
    const [preferences, setPreferences] = useState({ owner, known: false, enabled: false, loading: true, saving: false, error: '' })
    const [audioState, setAudioState] = useState<{ owner: typeof audioOwner; value: NotificationAudioState }>({ owner: audioOwner, value: 'locked' })
    const revision = useRef(0), foregroundRevision = useRef(0), saving = useRef(false)
    const background = useRef<{ controller: AbortController; result: Promise<boolean> }>()
    const currentAudioOwner = useRef(audioOwner); currentAudioOwner.current = audioOwner
    const audio = useMemo(() => new NotificationAudio(value => {
        if (currentAudioOwner.current === audioOwner) setAudioState({ owner: audioOwner, value })
    }), [audioOwner])
    const current = useRef({ owner, preferences }); current.current = { owner, preferences }
    const visible = preferences.owner === owner ? preferences : { known: false, enabled: false, loading: true, saving: false, error: '' }
    const effective = () => current.current.owner === owner && current.current.preferences.owner === owner
        && current.current.preferences.known && current.current.preferences.enabled
        && !current.current.preferences.loading && !current.current.preferences.saving && !current.current.preferences.error
        && lifetime.isCurrent()
    const currentEffective = useRef(effective); currentEffective.current = effective
    const fencePreferences = (loading: boolean) => {
        revision.current++
        background.current?.controller.abort()
        current.current.preferences = { ...current.current.preferences, known: false, loading }
        setPreferences(previous => ({ ...previous, owner, known: false, loading }))
    }
    const query = useUiResource('notification-preferences:' + account, !!account, matchesPreferences,
        async signal => {
            const version = revision.current
            foregroundRevision.current = version
            if (Date.now() < backoff.retryAt) return { version, value: null }
            try {
                const value = await getNotificationPreferences(signal)
                if (signal.aborted || version !== revision.current) return { version, value: null }
                backoff.retryAt = 0; backoff.failures = 0
                return { version, value }
            }
            catch (error) {
                if (signal.aborted) throw error
                if (version !== revision.current) return { version, value: null }
                const hint = error && typeof error === 'object' ? (error as { retryAfterMs?: unknown }).retryAfterMs : 0
                const retryAfterMs = typeof hint === 'number' && Number.isFinite(hint) ? Math.max(0, hint) : 0
                backoff.failures++
                backoff.retryAt = Date.now() + Math.max(retryAfterMs, Math.min(60_000, 1000 * 2 ** Math.min(backoff.failures - 1, 16)))
                // Sound preferences are optional for visual notifications. Their failure must not
                // prevent the shared feed from finishing its authoritative notification reconciliation.
                return { version, value: null }
            }
        },
        ({ version, value }) => {
            if (version !== revision.current) return
            setPreferences(previous => value ? { ...previous, owner, known: true, enabled: value.soundEnabled, loading: false, error: '' }
                : { ...previous, owner, loading: false, error: 'Не удалось загрузить настройку звука.' })
        },
        () => { if (foregroundRevision.current === revision.current)
            setPreferences(previous => ({ ...previous, owner, loading: false, error: 'Не удалось загрузить настройку звука.' })) },
        () => fencePreferences(false),
        loading => setPreferences(previous => previous.owner === owner ? { ...previous, loading }
            : { owner, known: false, enabled: false, loading, saving: false, error: '' }), true,
        () => fencePreferences(true))
    useEffect(() => { saving.current = false }, [owner])
    useEffect(() => () => { background.current?.controller.abort(); background.current = undefined }, [owner])
    useEffect(() => {
        audio.open()
        if (!account) return () => audio.close()
        const activate = (event: Event) => {
            if (!event.isTrusted || !currentEffective.current()) return
            const key = event as KeyboardEvent
            if (event.type === 'keydown' && (key.ctrlKey || key.altKey || key.metaKey || key.repeat)) return
            void audio.activate()
        }
        document.addEventListener('pointerdown', activate, true); document.addEventListener('keydown', activate, true)
        return () => {
            document.removeEventListener('pointerdown', activate, true); document.removeEventListener('keydown', activate, true)
            audio.close()
        }
    }, [audio, account])

    const setEnabled = async (enabled: boolean) => {
        if (saving.current || !visible.known || visible.loading) return
        // The checkbox gesture is the employee's first possible interaction while muted.
        // Unlock now, before awaiting persistence, and never queue a tone for this activation.
        if (enabled && !visible.enabled) void audio.activate()
        saving.current = true; revision.current++
        background.current?.controller.abort()
        const allowed = lifetime.capture()
        // Muting takes effect immediately even before React renders or the request returns.
        current.current.preferences = { ...current.current.preferences, saving: true }
        setPreferences(previous => ({ ...previous, owner, saving: true, error: '' }))
        try {
            const value = await lifetime.read(signal => updateNotificationPreferences(enabled, signal))
            if (!allowed()) return
            revision.current++
            setPreferences({ owner, known: true, enabled: value.soundEnabled, loading: false, saving: false, error: '' })
        } catch {
            if (allowed()) setPreferences(previous => ({ ...previous, owner, saving: false,
                error: 'Не удалось сохранить настройку звука. Проверьте её повторной загрузкой.' }))
        } finally { if (allowed()) { saving.current = false; query.reload() } }
    }
    const canReadInBackground = () => audio.ready && lifetime.isCurrent() && !saving.current
        && current.current.owner === owner && current.current.preferences.owner === owner
        && (!current.current.preferences.known || current.current.preferences.enabled)
        && Date.now() >= backoff.retryAt
    const prepareBackground = (): Promise<boolean> => {
        if (!canReadInBackground()) return Promise.resolve(false)
        if (effective()) return Promise.resolve(true)
        if (background.current && !background.current.controller.signal.aborted) return background.current.result
        const controller = new AbortController(), allowed = lifetime.capture(), version = ++revision.current
        // One shared, deadline-bound preference read may bypass the paused hidden-tab budget.
        // No missed notification is queued. Every eventual detail read still checks current ACL.
        const pending = { controller, result: Promise.resolve(false) }
        pending.result = withCancellation(signal => getNotificationPreferences(signal), [controller.signal], 5000).then(value => {
            if (!allowed() || controller.signal.aborted || revision.current !== version) return false
            backoff.retryAt = 0; backoff.failures = 0
            const next = { owner, known: true, enabled: value.soundEnabled, loading: false, saving: false, error: '' }
            current.current.preferences = next; setPreferences(next)
            return value.soundEnabled && audio.ready
        }).catch(error => {
            if (allowed() && !controller.signal.aborted && revision.current === version) {
                const hint = typeof error?.retryAfterMs === 'number' && Number.isFinite(error.retryAfterMs) ? Math.max(0, error.retryAfterMs) : 0
                backoff.failures++
                backoff.retryAt = Date.now() + Math.max(hint, Math.min(60_000, 1000 * 2 ** Math.min(backoff.failures - 1, 16)))
                const next = { ...current.current.preferences, owner, known: false, loading: false, error: 'Не удалось загрузить настройку звука.' }
                current.current.preferences = next; setPreferences(next)
            }
            return false
        }).finally(() => { if (background.current === pending) background.current = undefined })
        background.current = pending
        return pending.result
    }
    return {
        preferences: visible, audioState: audioState.owner === audioOwner ? audioState.value : 'locked',
        setEnabled, reload: query.reload,
        test: () => { const allowed = lifetime.capture(); void audio.activate().then(ready => { if (ready && allowed()) audio.play() }) },
        canReadInBackground, prepareBackground,
        notify: (item: NotificationDto, alertsAfter: number, allowed: () => boolean, signal?: AbortSignal) =>
            claimNotificationSound(account, item.id, () => allowed() && effective() && audio.ready && canAlert(item, alertsAfter),
                () => audio.play(), signal),
    }
}
