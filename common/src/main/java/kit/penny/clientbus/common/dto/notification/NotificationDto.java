package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kit.penny.clientbus.common.notification.NotificationType;

public record NotificationDto(
        UUID id, NotificationType type, UUID workspaceId,
        NotificationTargetDto target, Map<String, String> templateArgs,
        Instant createdAt, Instant readAt, Instant alertExpiresAt, boolean alertActive
) {
}
