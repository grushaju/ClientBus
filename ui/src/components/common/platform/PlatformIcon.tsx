import type { ReactNode } from 'react'
import { getPlatformDefinition, getPlatformName } from './platform'

interface PlatformIconProps {
    type: string
    size?: number
}

function PlatformIcon({
                          type,
                          size = 18,
                      }: PlatformIconProps) {
    const platform = getPlatformDefinition(type)

    const commonProps = {
        width: size,
        height: size,
        viewBox: '0 0 24 24',
        fill: 'none',
        xmlns: 'http://www.w3.org/2000/svg',
        'aria-hidden': true,
    }

    let icon: ReactNode

    switch (platform?.key) {
        case 'TELEGRAM':
        case 'TELEGRAM_BOT':
            icon = (
                <svg {...commonProps}>
                    <path
                        d="M21.5 4.5 18.2 20c-.25 1.1-.9 1.35-1.82.84l-5.05-3.72-2.44 2.35c-.27.27-.5.5-1.02.5l.36-5.14 9.36-8.46c.41-.36-.09-.56-.64-.2L5.38 13.2.45 11.66c-1.07-.34-1.09-1.08.22-1.6L19.93 2.5c.91-.34 1.7.2 1.57 2Z"
                        fill="#229ED9"
                    />
                </svg>
            )
            break

        case 'WHATSAPP':
        case 'WHATSAPP_BUSINESS':
            icon = (
                <svg {...commonProps}>
                    <path
                        d="M12 2.5a9.5 9.5 0 0 0-8.2 14.3L2.5 21.5l4.85-1.27A9.5 9.5 0 1 0 12 2.5Z"
                        fill="#25D366"
                    />
                    <path
                        d="M8.2 7.2c.2-.22.42-.23.68-.23.17 0 .34 0 .49.01.18.01.38.07.48.32.13.3.46 1.1.5 1.18.04.1.07.21.01.34-.05.13-.08.2-.16.31-.08.1-.17.23-.24.31-.08.08-.16.17-.07.34.09.17.39.64.84 1.04.58.52 1.07.68 1.24.76.17.08.27.07.37-.04.1-.11.42-.49.53-.66.11-.17.22-.14.37-.08.15.06.96.45 1.12.53.17.08.28.12.32.19.04.07.04.4-.1.78-.14.38-.82.74-1.13.78-.29.04-.66.06-1.06-.07-.24-.08-.55-.18-.95-.35-1.68-.72-2.78-2.4-2.86-2.52-.08-.11-.68-.91-.68-1.73 0-.82.43-1.22.59-1.38Z"
                        fill="#FFFFFF"
                    />
                </svg>
            )
            break

        case 'MAX':
        case 'MAX_BOT':
            icon = (
                <svg {...commonProps}>
                    <rect
                        x="3"
                        y="3"
                        width="18"
                        height="18"
                        rx="5"
                        fill="#0077FF"
                    />
                    <path
                        d="M7 16V8l5 4.1L17 8v8"
                        stroke="#FFFFFF"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    />
                </svg>
            )
            break

        case 'VK':
        case 'VK_BOT':
            icon = (
                <svg {...commonProps}>
                    <rect
                        x="3"
                        y="3"
                        width="18"
                        height="18"
                        rx="5"
                        fill="#0077FF"
                    />
                    <path
                        d="M7 9.2c.25 2.55 1.55 5.1 4.55 5.35v-2.1c1.1.11 1.9.9 2.23 2.1H16c-.16-.86-.78-1.83-1.6-2.38.66-.62 1.31-1.5 1.5-2.97h-2.05c-.28 1.03-.93 1.91-2.3 2.12V9.2H9.6v3.65C8.23 12.42 7.76 10.96 7.6 9.2H7Z"
                        fill="#FFFFFF"
                    />
                </svg>
            )
            break

        case 'AVITO':
            icon = (
                <svg {...commonProps}>
                    <circle
                        cx="12"
                        cy="12"
                        r="9"
                        fill="#00AAFF"
                    />
                    <path
                        d="M8.5 13.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2Zm7-3c.83 0 1.5-.67 1.5-1.5S16.33 7.5 15.5 7.5 14 8.17 14 9s.67 1.5 1.5 1.5ZM15.5 17c.83 0 1.5-.67 1.5-1.5S16.33 14 15.5 14s-1.5.67-1.5 1.5.67 1.5 1.5 1.5ZM11 17.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2Z"
                        fill="#FFFFFF"
                    />
                </svg>
            )
            break

        case 'DIKIDI':
            icon = (
                <svg {...commonProps}>
                    <circle
                        cx="12"
                        cy="12"
                        r="9"
                        fill="#6C4DFF"
                    />
                    <path
                        d="M9 7.5v9M9 8h3.2a4 4 0 0 1 0 8H9"
                        stroke="#FFFFFF"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    />
                </svg>
            )
            break

        default:
            icon = (
                <svg {...commonProps}>
                    <circle
                        cx="12"
                        cy="12"
                        r="9"
                        fill="#94A3B8"
                    />
                    <path
                        d="M8 12h8M12 8v8"
                        stroke="#FFFFFF"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                    />
                </svg>
            )
    }

    return (
        <span
            className="platform-icon"
            title={getPlatformName(type)}
            style={{
                width: size,
                height: size,
            }}
        >
            {icon}
        </span>
    )
}

export default PlatformIcon