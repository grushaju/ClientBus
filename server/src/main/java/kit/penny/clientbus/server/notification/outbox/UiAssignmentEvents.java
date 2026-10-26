package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.OutboxStatus;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.notification.NotificationOutboxEntity;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** Assignment can revoke access even when an unassigned conversation had no previous owner. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class UiAssignmentEvents {
    private static final int MAX_RECIPIENTS = 500;
    private final EntityManager em;
    private final AccessPolicy access;
    private final NotificationProperties config;
    private final JsonMapper mapper;

    public UiAssignmentEvents(EntityManager em, AccessPolicy access, NotificationProperties config, JsonMapper mapper) {
        this.em = em; this.access = access; this.config = config; this.mapper = mapper;
    }

    /** Called before changing the managed assignment, while the conversation row is locked. */
    public Set<UUID> previousAudience(ConversationEntity conversation) {
        return config.sseEnabled() ? access.audience(AccessPolicy.Resource.CONVERSATION, conversation.getId()) : Set.of();
    }

    /** Data and cleanup signals commit together; routing rechecks all current access before delivery. */
    public void changed(ConversationEntity conversation, Set<UUID> previousAudience) {
        if (!config.sseEnabled()) return;
        em.flush(); // The authoritative JDBC policy must see the new assignment in this transaction.
        var previous = new ArrayList<>(previousAudience);
        for (int start = 0; start < previous.size(); start += MAX_RECIPIENTS) {
            var candidates = previous.subList(start, Math.min(start + MAX_RECIPIENTS, previous.size()));
            var lost = new HashSet<>(candidates);
            lost.removeAll(access.allowedRecipients(candidates, AccessPolicy.Resource.CONVERSATION, conversation.getId()));
            if (!lost.isEmpty()) write(conversation, "access.invalidated", UiInvalidation.Audience.FORMER_SCOPE,
                    new UiResourceRef("conversation", conversation.getId()), lost);
        }
        // Unassign makes the conversation visible to other members too. No private target ID is
        // exposed: WORKSPACE routing emits only the existing minimal resync control.
        write(conversation, "inbox.invalidated", UiInvalidation.Audience.WORKSPACE,
                new UiResourceRef("workspace", conversation.getWorkspace().getId()), Set.of());
    }

    private void write(ConversationEntity conversation, String type, UiInvalidation.Audience audience,
                       UiResourceRef resource, Set<UUID> recipients) {
        var workspace = conversation.getWorkspace();
        var event = new UiInvalidation(UUID.randomUUID(), 1, type, Instant.now(), workspace.getOrganization().getId(),
                workspace.getId(), resource, audience, recipients, EventOrigin.LIVE, null, null);
        @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(event, Map.class);
        if (mapper.writeValueAsBytes(payload).length > config.maxOutboxBytes())
            throw new IllegalArgumentException("UI outbox payload too large");
        var row = new NotificationOutboxEntity();
        row.setEventId(event.eventId()); row.setOrganizationId(event.organizationId());
        row.setDestination(UiKafkaSubscriber.TOPIC); row.setEventType(type); row.setSchemaVersion(1);
        row.setPayload(payload); row.setStatus(OutboxStatus.PENDING); row.setNextAttemptAt(Instant.now());
        em.persist(row);
    }
}
