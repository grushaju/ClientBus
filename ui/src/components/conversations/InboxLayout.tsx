import type { ReactNode } from 'react'

interface InboxLayoutProps {
    list: ReactNode
    content: ReactNode
    showListOnMobile: boolean
    hasConversation: boolean
    onShowList: () => void
}

function InboxLayout({
                         list,
                         content,
                         showListOnMobile,
                         hasConversation,
                         onShowList,
                     }: InboxLayoutProps) {
    return (
        <div className="inbox-layout" data-mobile-list={showListOnMobile} data-has-conversation={hasConversation}>
            <div className="inbox-browser">
                {list}
            </div>

            <section className="inbox-content">
                {hasConversation && <div className="inbox-mobile-toolbar">
                    <button type="button" className="ui-button ui-button-secondary" onClick={onShowList}>
                        К списку диалогов
                    </button>
                </div>}
                {content}
            </section>
        </div>
    )
}

export default InboxLayout
