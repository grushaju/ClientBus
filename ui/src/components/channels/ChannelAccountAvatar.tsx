import {
    useEffect,
    useState,
} from 'react'

import {
    apiFetch,
} from '../../api/apiClient'

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
    const [imageUrl, setImageUrl] =
        useState<string | null>(null)

    useEffect(() => {
        let cancelled = false
        let objectUrl: string | null = null

        setImageUrl(null)

        if (!avatarUrl) {
            return () => {
                cancelled = true
            }
        }

        apiFetch(
            `/api/channels/${channelId}/account/avatar`,
        )
            .then(response => response.blob())
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

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [
        channelId,
        avatarUrl,
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