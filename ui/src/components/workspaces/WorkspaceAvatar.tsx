
import { useEffect, useState } from 'react'

import { getWorkspaceAvatarContent } from '../../api/workspaceApi'

interface WorkspaceAvatarProps {
    workspaceId: string
    avatarUrl: string | null
    name: string
    size?: number
    className?: string
}

export default function WorkspaceAvatar({
                                            workspaceId,
                                            avatarUrl,
                                            name,
                                            size = 40,
                                            className = '',
                                        }: WorkspaceAvatarProps) {
    const [imageSrc, setImageSrc] = useState<string | null>(null)

    useEffect(() => {
        if (!avatarUrl) {
            setImageSrc(null)
            return
        }

        let active = true
        let objectUrl: string | null = null

        setImageSrc(null)

        void getWorkspaceAvatarContent(workspaceId)
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
    }, [workspaceId, avatarUrl])

    const initials = name
        .trim()
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map(part => part[0])
        .join('')
        .toUpperCase() || '?'

    return (
        <div
            className={`workspace-avatar ${className}`.trim()}
            style={{ width: size, height: size }}
            role="img"
            aria-label={`Аватар рабочего пространства ${name}`}
        >
            {imageSrc ? (
                <img src={imageSrc} alt="" />
            ) : (
                <span>{initials}</span>
            )}
        </div>
    )
}
