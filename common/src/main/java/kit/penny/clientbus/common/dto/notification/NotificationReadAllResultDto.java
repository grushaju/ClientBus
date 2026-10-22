package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;

public record NotificationReadAllResultDto(
        Instant readAt, long updatedCount
) {
}
