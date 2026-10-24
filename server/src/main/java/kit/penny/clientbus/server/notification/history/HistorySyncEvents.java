package kit.penny.clientbus.server.notification.history;

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

@Service
public class HistorySyncEvents {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;
    public HistorySyncEvents(NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(UUID organization, UUID recipient, UUID operation) {
        var event = new UiInvalidation(UUID.randomUUID(), 1, "history-operation.invalidated", Instant.now(),
                organization, null, new UiResourceRef("history-operation", operation),
                UiInvalidation.Audience.PERSONAL, Set.of(recipient), EventOrigin.HISTORY_IMPORT, null, operation);
        jdbc.update("""
                insert into dbo.notificationoutbox
                  (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,nextattemptat)
                values (:id,:event,:org,:topic,:type,1,cast(:payload as jsonb),'PENDING',0,0,clock_timestamp())
                """, Map.of("id", UUID.randomUUID(), "event", event.eventId(), "org", organization,
                "topic", UiKafkaSubscriber.TOPIC, "type", event.type(), "payload", mapper.writeValueAsString(event)));
    }
}
