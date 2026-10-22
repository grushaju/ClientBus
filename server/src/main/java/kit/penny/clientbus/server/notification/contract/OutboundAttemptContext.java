package kit.penny.clientbus.server.notification.contract;

import java.util.UUID;
import java.util.Objects;

/** New contract: legacy callbacks without this context must not complete a new retry. */
public record OutboundAttemptContext(UUID messageId, UUID sendAttemptId) {
    public OutboundAttemptContext {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(sendAttemptId);
    }
}
