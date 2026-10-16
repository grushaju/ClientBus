
package kit.penny.clientbus.common.dto.conversation;

import kit.penny.clientbus.common.enums.SavedFilterType;

import java.time.Instant;
import java.util.UUID;

public record SavedConversationFilterDto(
        UUID id,
        String name,
        SavedFilterType filterType,
        ConversationSearchRequest filterDefinition,
        Instant createdAt,
        Instant updatedAt
) {
}
