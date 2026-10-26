package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;

public record NotificationClearResultDto(Instant clearedAt, long deletedCount) {}
