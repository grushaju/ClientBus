package kit.penny.clientbus.server.notification.center;

import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Personal invalidations are persisted with their command, independently of transport availability. */
@Service
public class PersonalNotificationEvents {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;

    public PersonalNotificationEvents(NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID organization, UUID recipient, UUID notification, boolean created) {
        String type = created ? "notifications.created" : "notifications.invalidated";
        var event = new UiInvalidation(UUID.randomUUID(), 1, type, Instant.now(), organization, null,
                notification == null ? new UiResourceRef("employee", recipient) : new UiResourceRef("notification", notification),
                UiInvalidation.Audience.PERSONAL, Set.of(recipient), EventOrigin.LIVE, null, null);
        jdbc.update("""
                insert into dbo.notificationoutbox
                    (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,nextattemptat)
                values (:id,:event,:organization,:topic,:type,1,cast(:payload as jsonb),'PENDING',0,0,clock_timestamp())
                """, Map.of("id", UUID.randomUUID(), "event", event.eventId(), "organization", organization,
                "topic", UiKafkaSubscriber.TOPIC, "type", type, "payload", mapper.writeValueAsString(event)));
    }
}
