import type { ReactNode } from 'react'

interface Props {
    toolbar: ReactNode
    notice?: ReactNode
    filters: ReactNode
    content: ReactNode
}

export default function ConversationList({ toolbar, notice, filters, content }: Props) {
    return (
        <div className="conversation-list">
            <div className="conversation-list-header">
                {toolbar}
                {notice}
                {filters}
            </div>
            {content}
        </div>
    )
}
