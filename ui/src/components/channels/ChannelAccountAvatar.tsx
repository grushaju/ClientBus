import {
    useEffect,
    useState,
} from 'react'

import {
    apiFetch,
} from '../../api/apiClient'
import { useUiLifetime } from '../../api/events/useUiLifetime'

interface ChannelAccountAvatarProps {
    channelId: string
    avatarUrl: string | null
    displayName: string | null
}

export function ChannelAccountAvatar({
                                         channelId,
                                         avatarUrl,
                                         displayName,
                                     }: ChannelAccountAvatarProps) {
    const lifetime = useUiLifetime('channel-account-avatar:' + channelId)
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

        lifetime.read(async signal => {
            const response = await apiFetch(`/api/channels/${channelId}/account/avatar`, { signal })
            return response.blob()
        }, controller.signal)
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
    }, [
        channelId,
        avatarUrl,
        lifetime,
    ])

    const initial =
        displayName?.trim().charAt(0).toUpperCase() || '•'

    return (
        <div className="channel-card-account-avatar">
            {imageUrl ? (
                <img
                    src={imageUrl}
                    alt={
                        displayName
                            ? `Аватар ${displayName}`
                            : 'Аватар аккаунта'
                    }
                    onError={() => setImageUrl(null)}
                />
            ) : (
                <span>{initial}</span>
            )}
        </div>
    )
}
