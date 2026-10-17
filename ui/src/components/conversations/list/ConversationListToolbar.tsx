import type { RefObject } from 'react'

export default function ConversationListToolbar({ title, workspaceName, isEmployee, onNewConversation, navigationId, navigationOpen, navigationButtonRef, onToggleNavigation }: {
    title: string; workspaceName: string; isEmployee: boolean; onNewConversation: () => void
    navigationId: string; navigationOpen: boolean; onToggleNavigation: () => void
    navigationButtonRef: RefObject<HTMLButtonElement>
}) {
    return (
        <div className="conversation-list-header-top">
            <div>
                <button ref={navigationButtonRef} type="button" className="ui-button ui-button-secondary inbox-navigation-toggle"
                    aria-expanded={navigationOpen} aria-controls={navigationId} onClick={onToggleNavigation}>
                    Меню Inbox
                </button>
                <h2 title={title}>{title}</h2>

                <span className="conversation-list-workspace">
                    {workspaceName}
                </span>
            </div>

            {isEmployee && (
                <button
                    type="button"
                    className="ui-button ui-button-primary"
                    onClick={() =>
                        onNewConversation()
                    }
                >
                    + Новый диалог
                </button>
            )}
        </div>

    )
}
