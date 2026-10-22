import { searchInbox } from '../../../api/conversationApi'
import { getClientAccountsByIds } from '../../../api/clientAccountApi'
import { getWorkspaceChannels } from '../../../api/channelApi'
import type { ConversationDto, ConversationSearchRequest } from '../../../api/types/conversation'

// Keep the existing load-all-pages behavior behind the data boundary.
export async function loadInboxConversations(
    workspaceId: string,
    request: ConversationSearchRequest,
    isCurrent: () => boolean,
    signal?: AbortSignal,
) {
    const conversations: ConversationDto[] = []
    let page = 0
    while (isCurrent()) {
        const result = await searchInbox(workspaceId, { ...request, page, size: 10 }, signal)
        if (!isCurrent()) return null
        conversations.push(...result.content)
        if (!result.hasNext) break
        if (result.page + 1 <= page) throw new Error('Некорректная пагинация списка диалогов.')
        page = result.page + 1
    }
    if (!isCurrent()) return null
    const channels = await getWorkspaceChannels(workspaceId, signal)
    if (!isCurrent()) return null
    const ids = [...new Set(conversations.map(conversation => conversation.clientAccountId))]
    const accounts = ids.length ? await getClientAccountsByIds(ids, signal) : []
    if (!isCurrent()) return null
    const channelByAccount = new Map(channels.filter(channel => channel.account).map(channel => [channel.account!.id, channel]))
    const accountById = new Map(accounts.map(account => [account.id, account]))
    return {
        channels,
        // The search endpoint owns ordering as well as membership of the result.
        items: conversations.map(conversation => ({
            conversation,
            clientAccount: accountById.get(conversation.clientAccountId) ?? null,
            channel: channelByAccount.get(conversation.channelAccountId) ?? null,
        })),
    }
}
