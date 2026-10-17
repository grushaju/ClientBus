import { useEffect, useState } from 'react'

// Keep these queries aligned with Inbox.css and InboxNavigation.css.
export const INBOX_MOBILE_QUERY = '(max-width: 760px)'
export const INBOX_DRAWER_QUERY = '(max-width: 1100px)'

export function useMediaQuery(query: string): boolean {
    const [matches, setMatches] = useState(() => typeof window !== 'undefined'
        && typeof window.matchMedia === 'function' && window.matchMedia(query).matches)
    useEffect(() => {
        if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
        const media = window.matchMedia(query)
        const update = () => setMatches(media.matches)
        update()
        media.addEventListener('change', update)
        return () => media.removeEventListener('change', update)
    }, [query])
    return matches
}
