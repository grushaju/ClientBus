
import {
    useEffect,
    useState,
} from 'react'

import {
    getClientAccountAvatarContent,
} from '../../api/clientAccountApi'
import { useUiLifetime } from '../../api/events/useUiLifetime'

interface ClientAccountAvatarProps {
    accountId: string
    avatarUrl: string | null
    displayName: string | null
    size?: number
}

export default function ClientAccountAvatar({
                                                accountId,
                                                avatarUrl,
                                                displayName,
                                                size = 36,
                                            }: ClientAccountAvatarProps) {
    const lifetime = useUiLifetime('client-account-avatar:' + accountId)
    const [imageUrl, setImageUrl] =
        useState<string | null>(null)

    useEffect(() => {
        let cancelled = false
        const controller = new AbortController()
        let objectUrl: string | null = null

        setImageUrl(null)

        if (!avatarUrl) {
            return () => {
                cancelled = true
            }
        }

        lifetime.read(signal => getClientAccountAvatarContent(accountId, signal), controller.signal)
            .then(blob => {
                const url = URL.createObjectURL(blob)

                if (cancelled) {
                    URL.revokeObjectURL(url)
                    return
                }

                objectUrl = url
                setImageUrl(url)
            })
            .catch(() => {
                if (!cancelled) {
                    setImageUrl(null)
                }
            })

        return () => {
            cancelled = true
            controller.abort()

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [accountId, avatarUrl, lifetime])

    const initial =
        displayName?.trim().charAt(0).toUpperCase() || '•'

    const style: React.CSSProperties = {
        width: size,
        height: size,
        minWidth: size,
        borderRadius: '50%',
        overflow: 'hidden',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
    }

    return (
        <div
            className="client-account-avatar"
            style={style}
        >
            {imageUrl ? (
                <img
                    src={imageUrl}
                    alt=""
                    onError={() => setImageUrl(null)}
                    style={{
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                    }}
                />
            ) : (
                <span>{initial}</span>
            )}
        </div>
    )
}

