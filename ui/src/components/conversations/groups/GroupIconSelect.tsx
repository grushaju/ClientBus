import { GROUP_ICON_OPTIONS } from './groupIcons'

export default function GroupIconSelect({
    value,
    onChange,
    disabled = false,
}: {
    value: string
    onChange: (value: string) => void
    disabled?: boolean
}) {
    return (
        <div className="conversation-list-group-icon-field">
            <span className="conversation-list-group-icon-label">
                Значок
            </span>

            <div
                className="conversation-list-group-icon-picker"
                role="group"
                aria-label="Значок группы"
            >
                {GROUP_ICON_OPTIONS.map(option => (
                    <button
                        key={option.value}
                        type="button"
                        className={
                            value === option.value
                                ? 'conversation-list-group-icon-option selected'
                                : 'conversation-list-group-icon-option'
                        }
                        title={option.label}
                        aria-label={option.label}
                        aria-pressed={value === option.value}
                        disabled={disabled}
                        onClick={() => onChange(option.value)}
                    >
                        <img
                            src={`/assets/group/${option.value}`}
                            alt=""
                            aria-hidden="true"
                        />
                    </button>
                ))}
            </div>
        </div>
    )
}
