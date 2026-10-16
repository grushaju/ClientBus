import type { ReactNode } from 'react'

interface InboxLayoutProps {
    list: ReactNode
    content: ReactNode
}

function InboxLayout({
                         list,
                         content
                     }: InboxLayoutProps) {
    return (
        <div className="inbox-layout">
            <aside className="inbox-sidebar">
                {list}
            </aside>

            <section className="inbox-content">
                {content}
            </section>
        </div>
    )
}

export default InboxLayout