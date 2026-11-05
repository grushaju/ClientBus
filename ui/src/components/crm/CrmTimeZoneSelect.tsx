const commonZones = new Map([
    ['UTC', 'UTC'],
    ['Europe/Kaliningrad', 'Калининград'],
    ['Europe/Moscow', 'Москва'],
    ['Europe/Samara', 'Самара'],
    ['Asia/Yekaterinburg', 'Екатеринбург'],
    ['Asia/Omsk', 'Омск'],
    ['Asia/Novosibirsk', 'Новосибирск'],
    ['Asia/Krasnoyarsk', 'Красноярск'],
    ['Asia/Irkutsk', 'Иркутск'],
    ['Asia/Yakutsk', 'Якутск'],
    ['Asia/Vladivostok', 'Владивосток'],
    ['Asia/Magadan', 'Магадан'],
    ['Asia/Kamchatka', 'Петропавловск-Камчатский'],
])
const intl = Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] }
const otherZones = (intl.supportedValuesOf?.('timeZone') ?? []).filter(zone => !commonZones.has(zone)).sort()

interface Props {
    label: string
    value: string
    disabled?: boolean
    onChange: (value: string) => void
}

export default function CrmTimeZoneSelect({ label, value, disabled, onChange }: Props) {
    return <select aria-label={label} value={value} disabled={disabled} onChange={event => onChange(event.target.value)}>
        <option value="">Выберите часовой пояс</option>
        {value && !commonZones.has(value) && !otherZones.includes(value) && <option value={value}>{value}</option>}
        <optgroup label="Часто используемые">
            {Array.from(commonZones, ([zone, name]) => <option key={zone} value={zone}>{name === zone ? name : `${name} (${zone})`}</option>)}
        </optgroup>
        {otherZones.length > 0 && <optgroup label="Другие часовые пояса">
            {otherZones.map(zone => <option key={zone} value={zone}>{zone.replace(/_/g, ' ')}</option>)}
        </optgroup>}
    </select>
}
