package kit.penny.clientbus.server.notification.contract;

import kit.penny.clientbus.common.dto.notification.HistorySyncRequest;
import kit.penny.clientbus.common.notification.EventOrigin;
import java.time.Instant;
import java.util.UUID;

/** Versioned replacement contract; existing consumers are not switched in Stage 11.2. */
public record HistorySyncCommand(
        UUID operationId, UUID initiatorEmployeeId, UUID organizationId,
        UUID workspaceId, UUID conversationId, UUID channelAccountId,
        HistorySyncRequest request, EventOrigin origin, Instant deadlineAt
) {
}
