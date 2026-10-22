package kit.penny.clientbus.server.notification.contract;

import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Outbox/Kafka contract only. Never serialize this object to SSE. */
public record UiInvalidation(
        UUID eventId, int schemaVersion, String type, Instant occurredAt,
        UUID organizationId, UUID workspaceId, UiResourceRef resource,
        Audience audience, Set<UUID> recipientEmployeeIds,
        EventOrigin origin, UUID actorEmployeeId, UUID correlationId
) {
    public UiInvalidation {
        recipientEmployeeIds = Set.copyOf(recipientEmployeeIds);
    }

    public enum Audience { CONVERSATION, WORKSPACE, ORGANIZATION, PERSONAL, FORMER_SCOPE }
}
