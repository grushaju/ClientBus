package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;
import java.util.UUID;

/** alertsAfter comes from PostgreSQL clock_timestamp(), not the client's clock. */
public record StreamReadyDto(UUID streamId, Instant alertsAfter, int heartbeatSeconds) {
}
