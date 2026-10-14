
import { useEffect, useState } from 'react'

import {
    getCurrentEmployeeAvatarContent,
    getEmployeeAvatarContent,
} from '../../api/employeeApi'

interface EmployeeAvatarProps {
    employeeId: string
    avatarUrl: string | null
    firstName?: string | null
    lastName?: string | null
    username?: string | null
    size?: number
    className?: string
    isCurrentEmployee?: boolean
}

function EmployeeAvatar({
                            employeeId,
                            avatarUrl,
                            firstName,
                            lastName,
                            username,
                            size = 40,
                            className = '',
                            isCurrentEmployee = false,
                        }: EmployeeAvatarProps) {
    const [imageSrc, setImageSrc] = useState<string | null>(null)

    useEffect(() => {
        if (!avatarUrl) {
            setImageSrc(null)
            return
        }

        let active = true
        let objectUrl: string | null = null

        setImageSrc(null)

        void (
            isCurrentEmployee
                ? getCurrentEmployeeAvatarContent()
                : getEmployeeAvatarContent(employeeId)
        ).then(blob => {
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
    }, [employeeId, avatarUrl, isCurrentEmployee])

    const initials =
        `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`.toUpperCase()
        || username?.[0]?.toUpperCase()
        || '?'

    return (
        <div
            className={`employee-avatar ${className}`.trim()}
            style={{ width: size, height: size }}
            aria-label={`Аватар ${[firstName, lastName].filter(Boolean).join(' ') || username || 'сотрудника'}`}
        >
            {imageSrc ? (
                <img src={imageSrc} alt="" />
            ) : (
                <span>{initials}</span>
            )}
        </div>
    )
}

export default EmployeeAvatar
