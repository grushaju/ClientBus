import type { ConversationListTab, InboxAccess } from './inboxSelection'

export default function ConversationViewTabs({ access, tab, onSelect }: {
    access: InboxAccess; tab: ConversationListTab; onSelect: (tab: ConversationListTab) => void
}) {
    const { isEmployee, isSuperAdmin, canViewAll } = access
    return (<>
        {(isEmployee || isSuperAdmin) && (
            <div className="conversation-list-tabs">
                {isEmployee && (
                    <button
                        type="button"
                        className={
                            tab === 'mine' ? 'active' : ''
                        }
                        aria-pressed={tab === 'mine'}
                        onClick={() =>
                            onSelect('mine')
                        }
                    >
                        <img src="/assets/group/my.svg" alt="" aria-hidden="true" />
                        Мои
                    </button>
                )}

                <button
                    type="button"
                    className={
                        tab === 'unassigned'
                            ? 'active'
                            : ''
                    }
                    aria-pressed={tab === 'unassigned'}
                    onClick={() =>
                        onSelect('unassigned')
                    }
                >
                    <img src="/assets/group/free.svg" alt="" aria-hidden="true" />
                    Другие
                </button>

                {canViewAll && (
                    <button
                        type="button"
                        className={
                            tab === 'all' ? 'active' : ''
                        }
                        aria-pressed={tab === 'all'}
                        onClick={() =>
                            onSelect('all')
                        }
                    >
                        <img src="/assets/group/all.svg" alt="" aria-hidden="true" />
                        Все
                    </button>
                )}
            </div>
        )}

    </>)
}
