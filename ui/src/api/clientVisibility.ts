import { getClientAccountsByClient } from './clientAccountApi'
import { getWorkspaceConversations } from './conversationApi'
import type { ClientAccountSummary } from './types/conversation'

interface GetVisibleClientAccountsOptions {
    clientId: string
    workspaceIds: string[]
    isSuperAdmin: boolean
    currentAccountId?: string
}

export async function getVisibleClientAccounts({
                                                   clientId,
                                                   workspaceIds,
                                                   isSuperAdmin,
                                                   currentAccountId,
                                               }: GetVisibleClientAccountsOptions): Promise<ClientAccountSummary[]> {
    const accounts = await getClientAccountsByClient(clientId)

    if (isSuperAdmin) {
        return accounts
    }

    if (workspaceIds.length === 0) {
        return accounts.filter(
            account => account.id === currentAccountId,
        )
    }

    const conversations = (
        await Promise.all(
            workspaceIds.map(
                workspaceId =>
                    getWorkspaceConversations(workspaceId),
            ),
        )
    ).flat()

    const visibleAccountIds = new Set(
        conversations.map(
            conversation => conversation.clientAccountId,
        ),
    )

    return accounts.filter(
        account =>
            visibleAccountIds.has(account.id) ||
            account.id === currentAccountId,
    )
}