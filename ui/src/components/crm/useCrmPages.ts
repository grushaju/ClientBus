import { useEffect, useRef, useState } from 'react'
import { useUiResource } from '../../api/events/useUiResource'
import { affectsWorkspace, isAccessEvent, useUiScope } from '../../api/events/uiScope'
import { getApiErrorCode, getApiErrorStatus } from '../../api/apiClient'
import { crmError, type CrmReadPage } from '../../api/crmApi'

type Page<T> = CrmReadPage<T>
/** Each query owns its pages. A local invalidation rereads the loaded window of the same revision. */
export function useCrmPages<T extends { id: string }>(key: string, workspaceId: string,
    read: (signal: AbortSignal, cursor?: string) => Promise<Page<T>>) {
    const scope = useUiScope()
    const [page, setPage] = useState<Page<T> | null>(null)
    const [loading, setLoading] = useState(true), [more, setMore] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const window = useRef<{ count: number; revision: number | string; epoch: number }>({ count: 1, revision: -1, epoch: 0 })
    const extra = useRef<AbortController>()
    function reset() {
        window.current = { count: 1, revision: -1, epoch: window.current.epoch + 1 }
        extra.current?.abort(); extra.current = undefined
        setPage(null); setMore(false)
    }
    function validate(result: Page<T>, cursor?: string) {
        if (result.hasMore && (!result.nextCursor || result.nextCursor === cursor || !result.items.length))
            throw Error('Не удалось загрузить следующую страницу. Повторите попытку.')
    }
    const resource = useUiResource(key, true,
        event => affectsWorkspace(event, workspaceId) && (isAccessEvent(event) || event.type === 'crm.invalidated'),
        async signal => {
            // A new snapshot during a multi-page read is retried once, starting at its first page.
            for (let attempt = 0; ; attempt++) {
                try {
                    const first = await read(signal); validate(first)
                    let result = first, count = 1
                    const wanted = first.revision === window.current.revision ? window.current.count : 1
                    while (count < wanted && result.hasMore && result.nextCursor) {
                        const next = await read(signal, result.nextCursor); validate(next, result.nextCursor)
                        if (next.revision !== first.revision) throw Error('Каталог изменился. Обновите список.')
                        result = { ...next, items: [...result.items, ...next.items] }; count++
                    }
                    return { result, count }
                } catch (failure) {
                    if (attempt || getApiErrorCode(failure) !== 'CRM_CONTEXT_CHANGED') throw failure
                    window.current.count = 1
                }
            }
        }, ({ result, count }) => {
            window.current = { count, revision: result.revision, epoch: window.current.epoch + 1 }
            extra.current?.abort(); extra.current = undefined; setMore(false)
            setPage(result); setError(null)
        }, failure => setError(crmError(failure)), reset, setLoading)
    useEffect(() => {
        setMore(false)
        return () => { extra.current?.abort(); extra.current = undefined }
    }, [key, scope])
    async function loadMore() {
        if (!page?.nextCursor || extra.current || loading) return
        const controller = new AbortController(), current = resource.capture(), epoch = window.current.epoch
        extra.current = controller; setMore(true); setError(null)
        try {
            const next = await read(controller.signal, page.nextCursor)
            if (!current() || controller.signal.aborted || epoch !== window.current.epoch) return
            validate(next, page.nextCursor)
            if (next.revision !== page.revision) { window.current.count = 1; resource.reload(); return }
            window.current.count++
            setPage(previous => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }))
        } catch (failure) {
            if (!current() || controller.signal.aborted || epoch !== window.current.epoch) return
            if ([403, 404].includes(getApiErrorStatus(failure) ?? 0)) reset()
            setError(crmError(failure))
            if (getApiErrorCode(failure) === 'CRM_CONTEXT_CHANGED') { window.current.count = 1; resource.reload() }
        } finally {
            if (extra.current === controller) { extra.current = undefined; setMore(false) }
        }
    }
    return { page, loading, more, error, loadMore, reload: resource.reload, capture: resource.capture,
        replace: (item: T) => setPage(previous => previous && ({ ...previous, items: previous.items.map(row => row.id === item.id ? item : row) })) }
}
