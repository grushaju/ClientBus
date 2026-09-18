import { getPlatformName } from './platform'

interface PlatformNameProps {
    type: string
}

function PlatformName({
                          type,
                      }: PlatformNameProps) {
    return (
        <span className="platform-name">
            {getPlatformName(type)}
        </span>
    )
}

export default PlatformName