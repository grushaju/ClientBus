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
        'aria-hidden': true as const,
    }

    const iconFiles: Record<string, string> = {
        TELEGRAM: 'telegram.svg',
        TELEGRAM_BOT: 'telegram.svg',
        WHATSAPP: 'whatsapp.svg',
        WHATSAPP_BUSINESS: 'whatsapp.svg',
        MAX: 'max.svg',
        MAX_BOT: 'max.svg',
        VK: 'vk.svg',
        VK_BOT: 'vk.svg',
        AVITO: 'avito.svg',
        DIKIDI: 'dikidi.svg',
    }

    const iconFile = platform ? iconFiles[platform.key] : undefined

    return (
        <span
            className="platform-icon"
            title={getPlatformName(type)}
            style={{
                width: size,
                height: size,
            }}
        >
            {iconFile ? (
                <img
                    src={`/assets/channel/${iconFile}`}
                    alt=""
                    width={size}
                    height={size}
                    style={{
                        display: 'block',
                        width: size,
                        height: size,
                        objectFit: 'contain',
                    }}
                />
            ) : (
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
            )}
        </span>
    )
}

export default PlatformIcon