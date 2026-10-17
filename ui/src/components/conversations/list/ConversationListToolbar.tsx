export default function ConversationListToolbar({ workspaceName, isEmployee, onNewConversation }: {
    workspaceName: string; isEmployee: boolean; onNewConversation: () => void
}) {
    return (
        <div className="conversation-list-header-top">
            <div>
                <h2>Диалоги</h2>

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
