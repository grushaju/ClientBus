package kit.penny.clientbus.common.dto.notification;

import java.util.List;

public record NotificationPageDto(
        List<NotificationDto> items, String nextCursor
) {
}
