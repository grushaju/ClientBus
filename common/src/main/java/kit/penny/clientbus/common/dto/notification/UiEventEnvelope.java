package kit.penny.clientbus.common.dto.notification;

import java.time.Instant;
import java.util.UUID;

/** Public SSE payload. No organization, audience, actor, provider or message data. */
public record UiEventEnvelope(
        UUID eventId, int schemaVersion, String type, Instant occurredAt,
        UUID workspaceId, UiResourceRef resource, UUID correlationId,
        StreamReadyDto stream
) {
}
