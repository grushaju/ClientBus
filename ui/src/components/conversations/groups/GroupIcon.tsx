import { resolveGroupIcon } from './groupIcons'

export default function GroupIcon({ icon, className = 'conversation-group-icon' }: { icon: string | null | undefined; className?: string }) {
    return <img className={className} src={`/assets/group/${resolveGroupIcon(icon)}`} alt="" aria-hidden="true" />
}
