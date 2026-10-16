import {
    useParams,
} from 'react-router-dom'

import InboxLayout
    from '../components/conversations/InboxLayout'

import ConversationList
    from '../components/conversations/ConversationList'

import ConversationView
    from '../components/conversations/ConversationView'

function InboxPage() {
    const {
        conversationId,
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
                        <div className="inbox-empty">
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