import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { getApiErrorStatus } from '../../api/apiClient'
import { clearNotifications, getNotification, getNotifications, getNotificationSummary, readAllNotifications, readNotification } from '../../api/notificationApi'
import type { NotificationDto, NotificationPageDto } from '../../api/types/notification'
import type { UiEventEnvelope } from '../../api/types/uiEvent'
import { useUiResource } from '../../api/events/useUiResource'
import { useUiLifetime } from '../../api/events/useUiLifetime'
import { isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { useNotificationFeed } from '../../api/events/NotificationFeedContext'
import { useUiInvalidation } from '../../api/events/InvalidationContext'
import { withCancellation } from '../../api/events/requestScope'
import { beginClear, beginReadAll, claimNotificationAlert, completeClear, completeReadAll, notificationAccount, pendingClear, pendingReadAll, type PendingReadAll } from './notificationStorage'
import { canAlert, isVisibleNotificationConversation, notificationContact, notificationRoute, notificationSource, notificationTitle } from './notificationPresentation'
import { useNotificationSound } from './useNotificationSound'
import './Notifications.css'

const matches = (event: UiEventEnvelope) => isAccessEvent(event)
    || event.type === 'notifications.created' || event.type === 'notifications.invalidated'
const failureMessage = (error: unknown) => [403,404].includes(getApiErrorStatus(error) ?? 0)
    ? 'Уведомление или его цель недоступны.' : error instanceof Error ? error.message : 'Не удалось обновить уведомления.'

function NotificationContent({ item }: { item: NotificationDto }) {
    const contact = notificationContact(item), source = notificationSource(item)
    return <>
        <strong>{notificationTitle(item)}</strong>
        {contact && <span className="notification-sender">{item.type === 'INCOMING_MESSAGE' ? 'От: ' : 'Диалог: '}{contact}</span>}
        {source && <span className="notification-source">{source}</span>}
    </>
}

/** All-workspace private resources share the existing SSE/REST recovery policy. */
export default function NotificationCenter() {
    const { currentEmployee } = useAuth()
    const { workspaces, setCurrentWorkspaceId } = useWorkspace()
    const location = useLocation(), navigate = useNavigate(), scope = useUiScope(), feed = useNotificationFeed()
    const account = currentEmployee ? notificationAccount(currentEmployee.organizationId, currentEmployee.id) : ''
    const owner = useMemo(() => ({ account, scope }), [account, scope])
    const commands = useUiLifetime('notification-commands:' + account)
    const alertsLifetime = useUiLifetime('notification-alerts:' + account)
    const sound = useNotificationSound(account), latestSound = useRef(sound); latestSound.current = sound
    const soundWork = useRef(new Map<AbortController, string>())
    const [open, setOpen] = useState(false), [unreadOnly, setUnreadOnly] = useState(false)
    const [summary, setSummary] = useState({ owner, unreadCount: 0, loading: true, error: '' })
    const [list, setList] = useState({ owner, unreadOnly, items: [] as NotificationDto[], nextCursor: null as string | null, loading: true, error: '' })
    const [action, setAction] = useState({ owner, pending: false, error: '' })
    const [pending, setPending] = useState<{ owner: typeof owner; command: PendingReadAll | null }>({ owner, command: pendingReadAll(account) })
    const [clearing, setClearing] = useState<{ owner: typeof owner; command: PendingReadAll | null; confirm: boolean }>({ owner, command: pendingClear(account), confirm: false })
    const [banners, setBanners] = useState<{ owner: typeof owner; items: Array<{ item: NotificationDto; until: number }> }>({ owner, items: [] })
    const pages = useRef(1), pendingCommand = useRef(false), button = useRef<HTMLButtonElement>(null), dialog = useRef<HTMLElement>(null)
    const clearButton = useRef<HTMLButtonElement>(null), cancelClearButton = useRef<HTMLButtonElement>(null)
    const latest = useRef({ pathname: location.pathname }); latest.current = { pathname: location.pathname }
    const visibleSummary = summary.owner === owner ? summary : { unreadCount: 0, loading: true, error: '' }
    const visibleList = list.owner === owner && list.unreadOnly === unreadOnly ? list : { items: [], nextCursor: null, loading: true, error: '' }
    const visibleAction = action.owner === owner ? action : { pending: false, error: '' }
    const visibleBanners = banners.owner === owner ? banners.items : []
    const pendingAction = pending.owner === owner ? pending.command : pendingReadAll(account)
    const pendingClearAction = clearing.owner === owner ? clearing.command : pendingClear(account)
    const confirmClear = clearing.owner === owner && clearing.confirm

    const summaryQuery = useUiResource('notification-summary:' + account, !!account, matches,
        getNotificationSummary,
        value => setSummary({ owner, unreadCount: value.unreadCount, loading: false, error: '' }),
        error => setSummary(previous => ({ ...previous, owner, loading: false, error: failureMessage(error) })),
        () => setSummary({ owner, unreadCount: 0, loading: false, error: '' }),
        loading => setSummary(previous => previous.owner === owner ? { ...previous, loading } : { owner, unreadCount: 0, loading, error: '' }))
    const listQuery = useUiResource('notification-list:' + account + ':' + unreadOnly, !!account && open, matches,
        async signal => {
            let nextCursor: string | null = null
            const items: NotificationDto[] = []
            for (let index = 0; index < pages.current; index++) {
                const page: NotificationPageDto = await getNotifications(unreadOnly, nextCursor, signal)
                items.push(...page.items); nextCursor = page.nextCursor
                if (!nextCursor) break
            }
            return { items: [...new Map(items.map(item => [item.id, item])).values()], nextCursor }
        }, value => setList({ owner, unreadOnly, ...value, loading: false, error: '' }),
        error => setList(previous => ({ ...previous, owner, unreadOnly, loading: false, error: failureMessage(error) })),
        () => setList({ owner, unreadOnly, items: [], nextCursor: null, loading: false, error: '' }),
        loading => setList(previous => previous.owner === owner && previous.unreadOnly === unreadOnly ? { ...previous, loading }
            : { owner, unreadOnly, items: [], nextCursor: null, loading, error: '' }), open)

    useEffect(() => {
        pages.current = 1; pendingCommand.current = false
        setPending({ owner, command: pendingReadAll(account) })
        setClearing({ owner, command: pendingClear(account), confirm: false })
        setAction({ owner, pending: false, error: '' })
    }, [owner])
    useUiInvalidation('notification-access:' + account, !!account, isAccessEvent, async () => {}, () => {
        commands.reset(); alertsLifetime.reset(); pendingCommand.current = false
        for (const controller of soundWork.current.keys()) controller.abort()
        soundWork.current.clear()
        setAction({ owner, pending: false, error: '' }); setBanners({ owner, items: [] })
        setClearing({ owner, command: pendingClear(account), confirm: false })
    })

    useEffect(() => { if (confirmClear) cancelClearButton.current?.focus() }, [confirmClear])

    useEffect(() => {
        if (!feed || !account) return
        const pendingIds = new Set<string>()
        let hiddenReads = 0
        const cancelSound = () => { for (const controller of soundWork.current.keys()) controller.abort(); soundWork.current.clear() }
        const unsubscribe = feed.subscribe(event => {
            if (event.kind === 'reset') {
                alertsLifetime.reset(); cancelSound(); pendingIds.clear(); setBanners({ owner, items: [] }); return
            }
            const hidden = document.hidden
            if (pendingIds.has(event.id) || pendingIds.size >= 32) return
            // Hidden automatic resources remain paused. A sound-only ACL read is bounded separately,
            // has no queue and is unnecessary when audio is locked or the employee muted it.
            if (hidden && (hiddenReads >= 2 || !latestSound.current.canReadInBackground())) return
            const allowed = alertsLifetime.capture()
            const controller = new AbortController()
            soundWork.current.set(controller, event.id)
            pendingIds.add(event.id)
            if (hidden) hiddenReads++
            const detail = hidden
                ? withCancellation(async signal => await latestSound.current.prepareBackground()
                    ? getNotification(event.id, signal) : null, [controller.signal], 5000)
                : alertsLifetime.read(signal => getNotification(event.id, signal))
            void detail.then(async item => {
                if (!item || !allowed() || controller.signal.aborted || !canAlert(item, event.alertsAfter)) return
                if (isVisibleNotificationConversation(item, latest.current.pathname)) return
                const audible = latestSound.current.notify(item, event.alertsAfter, allowed, controller.signal)
                // Foreground banners keep their own ledger and do not depend on sound preferences.
                if (!hidden && !document.hidden && claimNotificationAlert(account, item.id))
                    setBanners(previous => ({ owner, items: [...(previous.owner === owner ? previous.items : []),
                        { item, until: Math.min(Date.parse(item.alertExpiresAt), Date.now() + 10_000) }].slice(-3) }))
                await audible
            }).catch(() => { /* Alerts are best effort; recovery still retains the center entry. */ })
                .finally(() => { pendingIds.delete(event.id); soundWork.current.delete(controller); if (hidden) hiddenReads-- })
        })
        return () => { unsubscribe(); cancelSound() }
    }, [feed, owner, account, alertsLifetime])
    useEffect(() => {
        if (!visibleBanners.length) return
        const timer = setTimeout(() => setBanners(previous => previous.owner === owner
            ? { owner, items: previous.items.filter(value => value.until > Date.now()) } : previous),
        Math.max(0, Math.min(...visibleBanners.map(value => value.until)) - Date.now()))
        return () => clearTimeout(timer)
    }, [banners, owner])

    useEffect(() => {
        if (!open) return
        const previous = document.activeElement as HTMLElement | null
        dialog.current?.querySelector<HTMLButtonElement>('button')?.focus()
        const keydown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') { setOpen(false); setClearing(previous => ({ ...previous, confirm: false })); return }
            if (event.key !== 'Tab') return
            const elements = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), a[href]')
            if (!elements?.length) return
            const first = elements[0], last = elements[elements.length - 1]
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
        }
        document.addEventListener('keydown', keydown)
        return () => { document.removeEventListener('keydown', keydown); (button.current ?? previous)?.focus() }
    }, [open])

    const refresh = () => { summaryQuery.reload(); if (open) listQuery.reload() }
    const updateRead = (item: NotificationDto) => {
        for (const [controller, id] of soundWork.current) if (id === item.id) { controller.abort(); soundWork.current.delete(controller) }
        setList(previous => previous.owner !== owner ? previous : { ...previous, items: unreadOnly
            ? previous.items.filter(value => value.id !== item.id) : previous.items.map(value => value.id === item.id ? item : value) })
        setBanners(previous => previous.owner === owner ? { owner, items: previous.items.filter(value => value.item.id !== item.id) } : previous)
        refresh()
    }
    const actOn = async (item: NotificationDto, openTarget: boolean) => {
        if (pendingCommand.current) return
        pendingCommand.current = true
        const allowed = commands.capture()
        setAction({ owner, pending: true, error: '' })
        try {
            const current = openTarget ? await commands.read(signal => getNotification(item.id, signal)) : item
            if (!allowed()) return
            const route = notificationRoute(current)
            if (openTarget && (!route || !current.workspaceId || !workspaces.some(value => value.id === current.workspaceId)))
                throw new Error('Цель уведомления недоступна.')
            const updated = await commands.read(signal => readNotification(current.id, signal))
            if (!allowed()) return
            updateRead(updated)
            if (openTarget && route) {
                setOpen(false); setCurrentWorkspaceId(current.workspaceId!); navigate(route)
            }
        } catch (error) { if (allowed()) { setAction({ owner, pending: false, error: failureMessage(error) }); refresh() } }
        finally { if (allowed()) { pendingCommand.current = false; setAction(previous => previous.owner === owner ? { ...previous, pending: false } : previous) } }
    }
    const markAll = async (retry = false) => {
        if (pendingCommand.current) return
        pendingCommand.current = true
        const allowed = commands.capture()
        setAction({ owner, pending: true, error: '' })
        try {
            const command = retry ? pendingAction : beginReadAll(account)
            if (!command || (retry && pendingReadAll(account)?.key !== command.key))
                throw new Error('Срок повтора истёк. Запустите новую отметку прочитанного.')
            setPending({ owner, command })
            await commands.read(signal => readAllNotifications(command.key, signal))
            completeReadAll(account, command.key)
            if (allowed()) {
                alertsLifetime.reset()
                for (const controller of soundWork.current.keys()) controller.abort()
                soundWork.current.clear()
                setPending({ owner, command: null }); refresh()
            }
        } catch (error) { if (allowed()) setAction({ owner, pending: false, error: failureMessage(error) }) }
        finally { if (allowed()) { pendingCommand.current = false; setAction(previous => previous.owner === owner ? { ...previous, pending: false } : previous) } }
    }
    const clearAll = async (retry = false) => {
        if (pendingCommand.current) return
        pendingCommand.current = true
        const allowed = commands.capture()
        setAction({ owner, pending: true, error: '' })
        try {
            const command = retry ? pendingClearAction : beginClear(account)
            if (!command || (retry && pendingClear(account)?.key !== command.key))
                throw new Error('Срок повтора истёк. Запустите новую очистку уведомлений.')
            setClearing({ owner, command, confirm: false })
            await commands.read(signal => clearNotifications(command.key, signal))
            completeClear(account, command.key)
            if (allowed()) {
                alertsLifetime.reset()
                for (const controller of soundWork.current.keys()) controller.abort()
                soundWork.current.clear(); setBanners({ owner, items: [] })
                setClearing({ owner, command: null, confirm: false }); pages.current = 1; refresh()
            }
        } catch (error) { if (allowed()) { setAction({ owner, pending: false, error: failureMessage(error) }); refresh() } }
        finally { if (allowed()) { pendingCommand.current = false; setAction(previous => previous.owner === owner ? { ...previous, pending: false } : previous) } }
    }
    const toggle = () => { if (!open) { pages.current = 1; setAction({ owner, pending: false, error: '' }); setClearing(previous => ({ ...previous, confirm: false })) } setOpen(value => !value) }

    return <div className="notification-center">
        <button ref={button} type="button" className="notification-bell" aria-label={`Уведомления: ${visibleSummary.unreadCount} непрочитанных`}
            aria-haspopup="dialog" aria-expanded={open} onClick={toggle}>
            <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.7">
                <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
            </svg>
            {visibleSummary.unreadCount > 0 && <span className="notification-count" aria-hidden="true">{visibleSummary.unreadCount > 99 ? '99+' : visibleSummary.unreadCount}</span>}
            {!!visibleSummary.error && <span className="notification-summary-warning" aria-label="Счётчик временно недоступен">!</span>}
        </button>
        {open && <>
            <button type="button" className="notification-backdrop" aria-label="Закрыть уведомления" tabIndex={-1} onClick={() => setOpen(false)} />
            <section ref={dialog} className="notification-dialog" role="dialog" aria-modal="true" aria-labelledby="notification-heading">
                <header><h2 id="notification-heading">Уведомления</h2><button type="button" className="ui-button ui-button-secondary" aria-label="Закрыть центр уведомлений" onClick={() => setOpen(false)}>✕</button></header>
                <div className="notification-controls">
                    <label><input type="checkbox" checked={unreadOnly} disabled={visibleAction.pending} onChange={event => { pages.current = 1; setUnreadOnly(event.target.checked) }} /> Только непрочитанные</label>
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={visibleAction.pending} onClick={() => void markAll()}>Отметить все прочитанными</button>
                    <button ref={clearButton} type="button" className="ui-button ui-button-danger ui-button-sm" disabled={visibleAction.pending}
                        aria-expanded={confirmClear} aria-controls="notification-clear-confirm" onClick={() => setClearing({ owner, command: pendingClearAction, confirm: !confirmClear })}>Очистить уведомления</button>
                </div>
                {confirmClear && <div id="notification-clear-confirm" className="notification-clear-confirm" role="group" aria-label="Подтверждение очистки уведомлений" aria-describedby="notification-clear-description">
                    <p id="notification-clear-description">Удалить все ваши уведомления во всех рабочих пространствах, включая прочитанные? Восстановить их будет нельзя.</p>
                    <div>
                        <button ref={cancelClearButton} type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={visibleAction.pending}
                            onClick={() => { setClearing(previous => ({ ...previous, confirm: false })); clearButton.current?.focus() }}>Отмена</button>
                        <button type="button" className="ui-button ui-button-danger ui-button-sm" disabled={visibleAction.pending}
                            onClick={() => void clearAll()}>Удалить все</button>
                    </div>
                </div>}
                <div className="notification-sound-controls" aria-busy={sound.preferences.loading || sound.preferences.saving}>
                    <label><input type="checkbox" aria-label="Звуковые уведомления" checked={sound.preferences.known && sound.preferences.enabled}
                        disabled={!sound.preferences.known || sound.preferences.loading || sound.preferences.saving}
                        onChange={event => void sound.setEnabled(event.target.checked)} /> Звук уведомлений</label>
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm"
                        disabled={!sound.preferences.known || sound.preferences.loading || sound.preferences.saving} onClick={sound.test}>Проверить звук</button>
                    <p role="status">{sound.preferences.loading ? 'Загрузка настройки звука…' : sound.preferences.saving ? 'Сохранение настройки…'
                        : !sound.preferences.known ? 'Настройка звука пока недоступна.' : !sound.preferences.enabled ? 'Звук выключен.'
                            : sound.audioState === 'unavailable' ? 'В этом браузере звук недоступен.'
                                : sound.audioState === 'blocked' ? 'Браузер заблокировал звук. Нажмите «Проверить звук».'
                                    : sound.audioState === 'locked' ? 'Звук включится после действия на странице.' : 'Звук включён.'}</p>
                    {!!sound.preferences.error && <div role="alert" className="notification-sound-error">{sound.preferences.error}
                        <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={sound.preferences.loading || sound.preferences.saving}
                            onClick={sound.reload}>Повторить загрузку настройки</button></div>}
                </div>
                {!!pendingAction && <div className="notification-notice">Предыдущая отметка прочитанного не подтверждена.
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={visibleAction.pending} onClick={() => void markAll(true)}>Повторить отметку</button></div>}
                {!!pendingClearAction && <div className="notification-notice">Предыдущая очистка уведомлений не подтверждена.
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={visibleAction.pending}
                        onClick={() => void clearAll(true)}>Повторить очистку</button></div>}
                {!!visibleAction.error && <p role="alert" className="notification-error">{visibleAction.error}</p>}
                {!!(visibleSummary.error || visibleList.error) && <div role="alert" className="notification-error">{visibleList.error || visibleSummary.error}
                    <button type="button" className="ui-button ui-button-secondary ui-button-sm" onClick={refresh}>Повторить загрузку уведомлений</button></div>}
                <div className="notification-items" aria-busy={visibleList.loading}>
                    {visibleList.loading && !visibleList.items.length && <p role="status">Загрузка уведомлений…</p>}
                    {!visibleList.loading && !visibleList.error && !visibleList.items.length && <p>Уведомлений пока нет.</p>}
                    <ul>{visibleList.items.map(item => <li key={item.id} data-notification-id={item.id} className={item.readAt ? 'notification-item' : 'notification-item unread'}>
                        <button type="button" className="notification-target" disabled={visibleAction.pending} onClick={() => void actOn(item, true)}>
                            <NotificationContent item={item} /><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString('ru-RU')}</time>
                        </button>
                        {!item.readAt && <button type="button" className="ui-button ui-button-secondary ui-button-sm" disabled={visibleAction.pending} onClick={() => void actOn(item, false)}>Прочитано</button>}
                    </li>)}</ul>
                    {!!visibleList.nextCursor && <button type="button" className="ui-button ui-button-secondary" disabled={visibleList.loading || visibleAction.pending} onClick={() => { pages.current++; listQuery.reload() }}>Показать ещё</button>}
                </div>
            </section>
        </>}
        <div className="notification-alerts" aria-live="polite" aria-atomic="false">{visibleBanners.map(({ item }) => <div key={item.id} className="notification-banner" role="status">
            <button type="button" className="notification-target" onClick={() => void actOn(item, true)}><NotificationContent item={item} /></button>
            <button type="button" className="notification-dismiss" aria-label="Скрыть уведомление" onClick={() => setBanners(previous => ({ owner, items: previous.items.filter(value => value.item.id !== item.id) }))}>✕</button>
        </div>)}</div>
    </div>
}
