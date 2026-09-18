import {
    useParams
} from 'react-router-dom'

import InboxLayout
    from '../components/inbox/InboxLayout'

import ConversationList
    from '../components/inbox/ConversationList'

import ConversationView
    from '../components/inbox/ConversationView'

function InboxPage() {
    const {
        conversationId
    } = useParams()

    return (
        <InboxLayout
            list={
                <ConversationList />
            }
            content={
                conversationId
                    ? (
                        <ConversationView />
                    )
                    : (
                        <div className="conversation-empty">
                            <h2>
                                Inbox
                            </h2>

                            <p>
                                Выберите диалог
                                из списка
                            </p>
                        </div>
                    )
            }
        />
    )
}

export default InboxPage