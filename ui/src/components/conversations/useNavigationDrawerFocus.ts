import { useEffect, type RefObject } from 'react'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
    + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function useNavigationDrawerFocus(
    panelRef: RefObject<HTMLElement>, open: boolean, onClose: () => void,
    returnFocus: () => HTMLElement | null,
) {
    useEffect(() => {
        const panel = panelRef.current
        if (!open || !panel) return
        const document = panel.ownerDocument
        const opener = document.activeElement as HTMLElement | null
        const visible = (element: HTMLElement) => element.isConnected && element.getClientRects().length > 0
        const controls = () => Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))
            .filter(element => element.tabIndex >= 0 && !element.hasAttribute('disabled') && visible(element))
        const focusFirst = () => (controls()[0] ?? panel).focus()

        // Disable the list, conversation and app chrome while this drawer is
        // modal. Preserve attributes owned by any other modal or caller.
        const outside: Element[] = []
        let branch: Element = panel
        while (branch.parentElement) {
            const parent = branch.parentElement
            for (const sibling of Array.from(parent.children)) {
                if (sibling === branch || sibling.hasAttribute('data-inbox-backdrop') || sibling.hasAttribute('inert')) continue
                sibling.setAttribute('inert', '')
                outside.push(sibling)
            }
            if (parent === document.body) break
            branch = parent
        }

        const keyDown = (event: KeyboardEvent) => {
            if (event.isComposing) return
            if (event.key === 'Escape') {
                if ((event.target as HTMLElement | null)?.closest?.('[data-inbox-escape-layer="true"]')) return
                event.preventDefault()
                event.stopPropagation()
                onClose()
            } else if (event.key === 'Tab') {
                const items = controls()
                const first = items[0], last = items[items.length - 1]
                if (!first) {
                    event.preventDefault()
                    panel.focus()
                } else if (!panel.contains(document.activeElement)
                    || (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
                    event.preventDefault()
                    const target = event.shiftKey ? last : first
                    target.focus()
                }
            }
        }
        const focusIn = (event: FocusEvent) => {
            if (!panel.contains(event.target as Node)) focusFirst()
        }
        const capture = { capture: true }
        document.addEventListener('keydown', keyDown, capture)
        document.addEventListener('focusin', focusIn, capture)
        focusFirst()
        return () => {
            document.removeEventListener('keydown', keyDown, capture)
            document.removeEventListener('focusin', focusIn, capture)
            for (const element of outside) element.removeAttribute('inert')
            const target = returnFocus() ?? opener
            if (target && visible(target)) target.focus()
            else if (visible(panel)) focusFirst()
        }
    }, [panelRef, open, onClose, returnFocus])
}
