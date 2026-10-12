package kit.penny.clientbus.common.dto.conversation;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;

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

        Instant updatedAt,

        boolean historyStartReached,

        ChannelConnectionStatus channelStatus,

        boolean externalOperationsAvailable
) {
}