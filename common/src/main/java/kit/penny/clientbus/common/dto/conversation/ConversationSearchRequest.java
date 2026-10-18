package kit.penny.clientbus.common.dto.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Условия поиска диалогов Inbox.
 *
 * workspaceId передаётся в URL endpoint, а не в теле запроса.
 * Идентификатор сотрудника намеренно отсутствует:
 * MINE и GROUP всегда определяются относительно текущего пользователя.
 */
public record ConversationSearchRequest(

        ConversationView view,

        UUID groupId,

        String search,

        List<UUID> channelAccountIds,

        List<UUID> clientAccountIds,

        ConversationClientLinkFilter clientLink,

        ConversationUnreadFilter unread,

        Instant activityFrom,

        Instant activityTo,

        ConversationSort sort,

        Integer page,

        Integer size,

        List<UUID> clientIds

) {
    public ConversationSearchRequest(ConversationView view, UUID groupId, String search,
            List<UUID> channelAccountIds, List<UUID> clientAccountIds,
            ConversationClientLinkFilter clientLink, ConversationUnreadFilter unread,
            Instant activityFrom, Instant activityTo, ConversationSort sort, Integer page, Integer size) {
        this(view, groupId, search, channelAccountIds, clientAccountIds, clientLink, unread,
                activityFrom, activityTo, sort, page, size, null);
    }
}
