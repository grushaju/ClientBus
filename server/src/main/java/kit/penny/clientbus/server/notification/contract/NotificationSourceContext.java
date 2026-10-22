package kit.penny.clientbus.server.notification.contract;

import kit.penny.clientbus.common.notification.EventOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trusted BE context. Retries preserve firstObservedAt; HTTP clients cannot set origin/actor. */
public record NotificationSourceContext(
        EventOrigin origin, Instant firstObservedAt, UUID sourceEventId, UUID actorEmployeeId
) {
    public NotificationSourceContext {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(firstObservedAt);
        Objects.requireNonNull(sourceEventId);
    }
}
