package kit.penny.clientbus.common.dto.conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationDto(
        UUID id,

        UUID workspaceId,

        UUID channelAccountId,

        UUID clientAccountId,

        UUID assignedEmployeeId,

        Instant lastMessageAt,

        String lastMessagePreview,

        int unreadCount,

        Instant createdAt,

        Instant updatedAt
) {
}