
import { useEffect, useState } from 'react'

import { getClientAvatarContent } from '../../api/clientApi'

interface ClientAvatarProps {
    clientId: string
    avatarUrl: string | null
    firstName: string
    lastName: string
    size?: number
    className?: string
}

function ClientAvatar({
                          clientId,
                          avatarUrl,
                          firstName,
                          lastName,
                          size = 40,
                          className = '',
                      }: ClientAvatarProps) {
    const [imageSrc, setImageSrc] = useState<string | null>(null)

    useEffect(() => {
        if (!avatarUrl) {
            setImageSrc(null)
            return
        }

        let active = true
        let objectUrl: string | null = null

        setImageSrc(null)

        void getClientAvatarContent(clientId)
            .then(blob => {
                if (!active) {
                    return
                }

                objectUrl = URL.createObjectURL(blob)
                setImageSrc(objectUrl)
            })
            .catch(() => {
                if (active) {
                    setImageSrc(null)
                }
            })

        return () => {
            active = false

            if (objectUrl) {
                URL.revokeObjectURL(objectUrl)
            }
        }
    }, [clientId, avatarUrl])

    const initials =
        `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`.toUpperCase()

    return (
        <div
            className={`client-avatar ${className}`.trim()}
            style={{ width: size, height: size }}
        >
            {imageSrc ? (
                <img
                    src={imageSrc}
                    alt={`Аватар ${firstName} ${lastName}`.trim()}
                />
            ) : (
                initials
            )}
        </div>
    )
}

export default ClientAvatar
