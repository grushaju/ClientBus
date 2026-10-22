package kit.penny.clientbus.common.dto.notification;

import java.util.UUID;
import kit.penny.clientbus.common.notification.NotificationTargetKind;

public record NotificationTargetDto(
        NotificationTargetKind kind, UUID id
) {
}
