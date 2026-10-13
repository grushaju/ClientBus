package kit.penny.clientbus.common.dto.conversation;

import java.util.List;

/**
 * Страница результатов поиска Inbox.
 */
public record ConversationSearchResponse(

        List<ConversationDto> content,

        int page,

        int size,

        long totalElements,

        int totalPages,

        boolean hasNext

) {
}