import { useEffect, useId, useRef, useState } from 'react'

export interface IconOption { value: string; label: string; icon: string }

export default function IconOptionPicker({ label, placeholder, value, options, onChange, disabled = false, inline = false, iconsOnly = false, searchable = true }: {
    label: string; placeholder: string; value: string | null; options: IconOption[]
    onChange: (value: string) => void; disabled?: boolean; inline?: boolean; iconsOnly?: boolean; searchable?: boolean
}) {
    const [open, setOpen] = useState(false)
    const [query, setQuery] = useState('')
    const root = useRef<HTMLDivElement>(null)
    const trigger = useRef<HTMLButtonElement>(null)
    const search = useRef<HTMLInputElement>(null)
    const id = useId()
    const selected = options.find(option => option.value === value)
    useEffect(() => {
        if (!open) return
        if (iconsOnly || !searchable) root.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"], .inbox-icon-picker-options button')?.focus()
        else search.current?.focus()
        const document = root.current?.ownerDocument
        if (!document) return
        const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false) }
        document.addEventListener('pointerdown', outside)
        return () => document.removeEventListener('pointerdown', outside)
    }, [open])
    useEffect(() => { if (disabled) setOpen(false) }, [disabled])
    return <div ref={root} className={`inbox-icon-picker${inline ? ' inbox-icon-picker-inline' : ''}`} data-inbox-escape-layer={open ? 'true' : undefined}
        onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false) }}
        onKeyDown={event => { if (open && event.key === 'Escape') {
            event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus()
        } }}>
        <button ref={trigger} type="button" className="ui-button ui-button-secondary inbox-icon-picker-trigger"
            aria-label={label} aria-expanded={open} aria-controls={id} disabled={disabled}
            onClick={() => { setQuery(''); setOpen(current => !current) }}>
            <img src={selected?.icon ?? '/assets/group/users.svg'} alt="" aria-hidden="true" />
            {!iconsOnly && <span>{selected?.label ?? placeholder}</span>}<span aria-hidden="true">▾</span>
        </button>
        {open && <div id={id} className="inbox-icon-picker-panel" role="group" aria-label={label}>
            {!iconsOnly && searchable && <input ref={search} className="ui-input" aria-label={`Поиск: ${label}`} placeholder="Поиск по имени"
                value={query} onChange={event => setQuery(event.target.value)} />}
            <div className={`inbox-icon-picker-options${iconsOnly ? ' inbox-icon-options-grid' : ''}`}>
                {options.filter(option => option.label.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())).map(option =>
                    <button key={option.value} type="button" className="ui-button ui-button-secondary"
                        aria-label={option.label} aria-pressed={value === option.value} onClick={() => { setOpen(false); onChange(option.value); trigger.current?.focus() }}>
                        <img src={option.icon} alt="" aria-hidden="true" />{!iconsOnly && <span>{option.label}</span>}
                    </button>)}
            </div>
        </div>}
    </div>
}
