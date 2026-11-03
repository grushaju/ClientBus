import { Link, useSearchParams } from 'react-router-dom'
import type { CrmProject, CrmServiceOffer, CrmSyncState } from '../../api/crmApi'

export function crmLocation(workspaceId: string, projectId?: string, tab = 'services') {
    return '/crm?' + new URLSearchParams({ workspace: workspaceId, ...(projectId ? { project: projectId } : {}), tab })
}
export function ReturnToCrm() {
    const [params] = useSearchParams()
    const workspace = params.get('returnWorkspace'), project = params.get('returnProject')
    if (!workspace) return null
    return <Link className="ui-button ui-button-secondary" to={crmLocation(workspace, project ?? undefined, params.get('returnTab') ?? 'services')}>Вернуться в CRM</Link>
}
export function recoveryQuery(project: CrmProject, tab: string) {
    return new URLSearchParams({ returnWorkspace: project.workspaceId, returnProject: project.id, returnTab: tab })
}
export function CrmFreshness({ state }: { state?: CrmSyncState | null }) {
    if (!state) return null
    return <div className="crm-freshness" role="status">
        {state.lastSuccessAt ? <>Данные от {new Date(state.lastSuccessAt).toLocaleString('ru-RU')}</> : 'Каталог ещё не загружен'}
        {state.freshness === 'STALE' && ' · требуют обновления'}
        {state.completeness === 'PARTIAL' && ' · загрузка неполная'}
        {['QUEUED', 'RUNNING'].includes(state.status) && ' · обновляем…'}
        {state.status === 'FAILED' && <span className="crm-error-text"> · Обновление не удалось. Сохранённые данные доступны.</span>}
    </div>
}
export function OfferTerms({ offer }: { offer: CrmServiceOffer }) {
    const price = offer.price === null ? 'Цена не указана' : `${offer.price} ${offer.currency ?? '(валюта не указана)'}`
    const duration = offer.durationSeconds === null ? 'Длительность не указана'
        : offer.durationSeconds % 60 ? `${offer.durationSeconds} сек` : `${offer.durationSeconds / 60} мин`
    return <span className="crm-terms"><strong>{price}</strong><span>{duration}</span>
        {offer.offerState.active === false && <span>Неактивно</span>}
        {offer.offerState.online === false && <span>Онлайн-запись выключена</span>}
        {offer.warnings.some(value => value.endsWith('_NOT_OBSERVED')) && <span>Часть условий не подтверждена последней загрузкой</span>}
    </span>
}
