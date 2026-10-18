import IconOptionPicker from '../IconOptionPicker'
import { GROUP_ICON_OPTIONS } from './groupIcons'

export default function GroupIconSelect({ value, onChange, disabled = false }: {
    value: string
    onChange: (value: string) => void
    disabled?: boolean
}) {
    return <div className="conversation-list-group-icon-field">
        <IconOptionPicker label="Значок группы" placeholder="Значок" value={value} disabled={disabled} iconsOnly
            options={GROUP_ICON_OPTIONS.map(option => ({ value: option.value, label: option.label, icon: `/assets/group/${option.value}` }))}
            onChange={onChange} />
    </div>
}
