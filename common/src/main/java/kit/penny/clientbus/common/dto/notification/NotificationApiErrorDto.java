package kit.penny.clientbus.common.dto.notification;

import java.util.UUID;

public record NotificationApiErrorDto(
        String code, UUID correlationId
) {
}
