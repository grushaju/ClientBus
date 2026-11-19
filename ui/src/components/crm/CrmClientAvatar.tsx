import { useEffect, useState } from 'react'
import { getCrmClientAvatarContent } from '../../api/crmApi'
import { useUiLifetime } from '../../api/events/useUiLifetime'

/**
 * Аватар карточки клиента CRM: байты отдаёт хост из локального зеркала файла DIKIDI, сырая ссылка
 * провайдера в браузер не попадает. Нет зеркала (или оно не докачалось) — инициал: аватар украшает
 * карточку и никогда ничего не подтверждает.
 */
export function CrmClientAvatar({ id, avatarUrl, displayName, size = 32 }: {
    id: string; avatarUrl: string | null; displayName: string | null; size?: number
}) {
    const lifetime = useUiLifetime('crm-client-avatar:' + id)
    const [image, setImage] = useState<string | null>(null)
    useEffect(() => {
        let revoked: string | null = null, cancelled = false
        const controller = new AbortController()
        setImage(null)
        if (!avatarUrl) return () => { cancelled = true }
        lifetime.read(signal => getCrmClientAvatarContent(id, signal), controller.signal)
            .then(blob => {
                const url = URL.createObjectURL(blob)
                if (cancelled) { URL.revokeObjectURL(url); return }
                revoked = url
                setImage(url)
            })
            .catch(() => { if (!cancelled) setImage(null) })
        return () => { cancelled = true; controller.abort(); if (revoked) URL.revokeObjectURL(revoked) }
    }, [id, avatarUrl, lifetime])
    return <span className="crm-client-avatar" style={{ width: size, height: size, minWidth: size }}>
        {image ? <img src={image} alt="" onError={() => setImage(null)} />
            : <span>{displayName?.trim().charAt(0).toUpperCase() || '•'}</span>}
    </span>
}
