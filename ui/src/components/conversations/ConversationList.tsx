import type { ReactNode } from 'react'

interface Props {
    toolbar: ReactNode
    navigation: ReactNode
    filters: ReactNode
    savedFilters: ReactNode
    content: ReactNode
}

// Preserve the current DOM and CSS. Navigation can later move to its own column.
export default function ConversationList({ toolbar, navigation, filters, savedFilters, content }: Props) {
    return (
        <div className="conversation-list">
            <div className="conversation-list-header">
                {toolbar}
                {navigation}
                {filters}
                {savedFilters}
            </div>
            {content}
        </div>
    )
}
