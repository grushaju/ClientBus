package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.*;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.notification.NotificationOutboxEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

@Service
public class UiOutboxWriter {
    private final EntityManager em;
    private final JsonMapper mapper;
    private final NotificationProperties config;
    public UiOutboxWriter(EntityManager em, JsonMapper mapper, NotificationProperties config) {
        this.em=em;this.mapper=mapper;this.config=config;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void messageSaved(ConversationEntity conversation, EventOrigin origin) {
        if (!config.sseEnabled()) return;
        Objects.requireNonNull(origin,"Message origin required");
        var workspace=conversation.getWorkspace();
        for (String type:List.of("messages.invalidated","inbox.invalidated")) {
            var event=new UiInvalidation(UUID.randomUUID(),1,type,Instant.now(),workspace.getOrganization().getId(),
                    workspace.getId(),new UiResourceRef("conversation",conversation.getId()),
                    UiInvalidation.Audience.CONVERSATION,Set.of(),origin,null,null);
            var row=new NotificationOutboxEntity();
            row.setEventId(event.eventId());row.setOrganizationId(event.organizationId());
            row.setDestination(UiKafkaSubscriber.TOPIC);row.setEventType(type);row.setSchemaVersion(1);
            @SuppressWarnings("unchecked") Map<String,Object> payload=mapper.convertValue(event,Map.class);
            if (mapper.writeValueAsBytes(payload).length>config.maxOutboxBytes()) throw new IllegalArgumentException("UI outbox payload too large");
            row.setPayload(payload);row.setStatus(OutboxStatus.PENDING);row.setNextAttemptAt(Instant.now());
            em.persist(row);
        }
    }
}
