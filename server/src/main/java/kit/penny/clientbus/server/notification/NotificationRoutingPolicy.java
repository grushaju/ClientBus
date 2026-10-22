package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.common.dto.notification.UiEventEnvelope;
import kit.penny.clientbus.common.notification.UiEventTypes;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.*;

/** Re-evaluate immediately before enqueue/send. Socket I/O belongs to the later gateway stage. */
@Service
public class NotificationRoutingPolicy {
    private final AccessPolicy policy;
    public NotificationRoutingPolicy(AccessPolicy policy) { this.policy = policy; }

    public Optional<UiEventEnvelope> forRecipient(AccessSubject subject, UiInvalidation event) {
        if (subject.expired(Instant.now())) return Optional.empty();
        boolean knownType = UiEventTypes.ALL.contains(event.type());
        if (knownType && event.type().startsWith("stream.")) return Optional.empty();
        boolean needsResync = event.schemaVersion() != 1 || !knownType;
        if (!policy.allows(subject, ORGANIZATION, event.organizationId())) return Optional.empty();
        boolean personal = event.audience() == UiInvalidation.Audience.PERSONAL
                || event.audience() == UiInvalidation.Audience.FORMER_SCOPE;
        if (personal && !event.recipientEmployeeIds().contains(subject.employeeId())) return Optional.empty();
        if (event.audience() == UiInvalidation.Audience.FORMER_SCOPE)
            return Optional.of(minimal(event, "access.invalidated"));

        if (event.audience() == UiInvalidation.Audience.WORKSPACE) {
            if (event.workspaceId() == null || !policy.allows(subject, WORKSPACE, event.workspaceId())) return Optional.empty();
            return Optional.of(minimal(event, "stream.resync-required"));
        }
        if (event.audience() == UiInvalidation.Audience.ORGANIZATION)
            return Optional.of(minimal(event, "stream.resync-required"));

        if (event.resource() == null) return personal
                ? Optional.of(minimal(event, "stream.resync-required")) : Optional.empty();
        AccessPolicy.Resource resource = switch (event.resource().kind()) {
            case "conversation" -> CONVERSATION;
            case "notification" -> NOTIFICATION;
            case "history-operation" -> HISTORY_OPERATION;
            case "group" -> PERSONAL_GROUP;
            case "filter" -> SAVED_FILTER;
            case "employee" -> EMPLOYEE;
            default -> null;
        };
        if (event.audience() == UiInvalidation.Audience.CONVERSATION && resource != CONVERSATION) return Optional.empty();
        if (resource == null || !policy.allows(subject, resource, event.resource().id()))
            return personal ? Optional.of(minimal(event, "access.invalidated")) : Optional.empty();
        if (needsResync) return Optional.of(minimal(event, "stream.resync-required"));
        // Do not copy unverified workspace/correlation fields from an asynchronous event.
        return Optional.of(new UiEventEnvelope(event.eventId(), 1, event.type(), event.occurredAt(),
                null, event.resource(), null, null));
    }

    private UiEventEnvelope minimal(UiInvalidation event, String type) {
        return new UiEventEnvelope(event.eventId(), 1, type, event.occurredAt(), null, null, null, null);
    }
}
