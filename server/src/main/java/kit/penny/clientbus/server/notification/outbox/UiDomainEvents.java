package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.common.notification.UiEventTypes;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.OutboxStatus;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.notification.NotificationOutboxEntity;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** Explicit domain hooks. Events and the change share a transaction; SSE never carries domain data. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class UiDomainEvents {
    private final EntityManager em;
    private final JsonMapper mapper;
    private final NotificationProperties config;
    private final AccessPolicy access;

    public UiDomainEvents(EntityManager em, JsonMapper mapper, NotificationProperties config, AccessPolicy access) {
        this.em = em; this.mapper = mapper; this.config = config; this.access = access;
    }

    public void workspace(WorkspaceEntity workspace, String type) {
        write(workspace.getOrganization().getId(), workspace.getId(), type,
                UiInvalidation.Audience.WORKSPACE, new UiResourceRef("workspace", workspace.getId()), Set.of());
    }

    public void organization(UUID organizationId, String type) {
        write(organizationId, null, type, UiInvalidation.Audience.ORGANIZATION,
                new UiResourceRef("organization", organizationId), Set.of());
    }

    /** A stable self resource also works after deleting a personal group/filter. */
    public void personal(UUID organizationId, UUID employeeId, String type) {
        write(organizationId, null, type, UiInvalidation.Audience.PERSONAL,
                new UiResourceRef("employee", employeeId), Set.of(employeeId));
    }

    public void identity(UUID organizationId, UUID employeeId) {
        personal(organizationId, employeeId, "identity.invalidated");
    }

    /** Capture before deletion: the deleted workspace cannot authorize its own cleanup event. */
    public void workspaceRemoved(WorkspaceEntity workspace) {
        if (!config.sseEnabled()) return;
        var recipients = new ArrayList<>(access.audience(AccessPolicy.Resource.WORKSPACE, workspace.getId()));
        for (int offset = 0; offset < recipients.size(); offset += 500) {
            write(workspace.getOrganization().getId(), null, "access.invalidated",
                    UiInvalidation.Audience.FORMER_SCOPE, new UiResourceRef("workspace", workspace.getId()),
                    Set.copyOf(recipients.subList(offset, Math.min(offset + 500, recipients.size()))));
        }
        organization(workspace.getOrganization().getId(), "workspace.invalidated");
    }

    /** Global identities can be used in several organizations; resolve scopes without a connector owner. */
    public void clientAccount(ClientAccountEntity account) {
        if (!config.sseEnabled()) return;
        List<WorkspaceEntity> workspaces = em.createQuery("select distinct c.workspace from ConversationEntity c "
                        + "where c.clientAccount.id = :id", WorkspaceEntity.class)
                .setParameter("id", account.getId()).getResultList();
        for (var workspace : workspaces) workspace(workspace, "clients.invalidated");
        if (account.getClient() != null) organization(account.getClient().getOrganization().getId(), "clients.invalidated");
    }

    private void write(UUID organizationId, UUID workspaceId, String type, UiInvalidation.Audience audience,
                       UiResourceRef resource, Set<UUID> recipients) {
        if (!config.sseEnabled()) return;
        if (!UiEventTypes.ALL.contains(type) || type.startsWith("stream."))
            throw new IllegalArgumentException("Unknown domain invalidation type");
        var event = new UiInvalidation(UUID.randomUUID(), 1, type, Instant.now(), organizationId, workspaceId,
                resource, audience, recipients, EventOrigin.LIVE, null, null);
        @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(event, Map.class);
        if (mapper.writeValueAsBytes(payload).length > config.maxOutboxBytes())
            throw new IllegalArgumentException("UI outbox payload too large");
        var row = new NotificationOutboxEntity();
        row.setEventId(event.eventId()); row.setOrganizationId(organizationId);
        row.setDestination(UiKafkaSubscriber.TOPIC); row.setEventType(type); row.setSchemaVersion(1);
        row.setPayload(payload); row.setStatus(OutboxStatus.PENDING); row.setNextAttemptAt(Instant.now());
        em.persist(row);
    }
}
