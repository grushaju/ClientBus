package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;
import java.util.UUID;
import kit.penny.clientbus.common.notification.HistoryOperationStatus;

public record HistorySyncOperationDto(
        UUID id, UUID conversationId, HistoryOperationStatus status,
        Instant createdAt, Instant updatedAt, Instant deadlineAt,
        HistorySyncResultDto result, String errorCode
) {
}
