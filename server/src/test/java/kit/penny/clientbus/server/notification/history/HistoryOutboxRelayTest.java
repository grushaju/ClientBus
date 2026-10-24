package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.common.dto.notification.HistorySyncRequest;
import kit.penny.clientbus.common.kafka.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import kit.penny.clientbus.server.notification.outbox.UiOutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryOutboxRelayTest {
    @Mock HistorySyncService history;
    @Mock UiOutboxStore store;
    @Mock HistoryCommandPublisher publisher;
    HistoryOutboxRelay relay;
    UiOutboxStore.Claim claim;

    @BeforeEach void setup() {
        var config = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        UUID operation = UUID.randomUUID(), eventId = UUID.randomUUID(), organization = UUID.randomUUID();
        var command = new HistorySyncCommand(operation, UUID.randomUUID(), organization, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new HistorySyncRequest(null, 100),
                EventOrigin.HISTORY_IMPORT, Instant.now().plusSeconds(120));
        var event = new KafkaEvent<>(eventId, KafkaEventType.HISTORY_SYNC_OPERATION, 1, Instant.now(), operation, command);
        claim = new UiOutboxStore.Claim(UUID.randomUUID(), eventId, organization, HistorySyncConfiguration.TOPIC,
                "HISTORY_SYNC_OPERATION", 1, mapper.writeValueAsString(event), operation, 1, 1);
        relay = new HistoryOutboxRelay(store, history, publisher, mapper, config, new SimpleMeterRegistry());
    }

    @Test void databaseTimeoutDuringCanonicalValidationRetriesTheSameCommand() throws Exception {
        when(history.matches(any())).thenThrow(new QueryTimeoutException("Temporary DB timeout")).thenReturn(true);
        when(history.canPublish(claim.operationId())).thenReturn(true);

        relay.deliver(claim);
        verify(store).failed(claim, false, "TRANSIENT_DATABASE");
        verify(history, never()).publicationFailed(any(), anyString());
        verifyNoInteractions(publisher);

        relay.deliver(claim);
        verify(publisher).publish(claim.operationId().toString(), claim.payload());
        verify(store).published(claim);
        verify(history, never()).publicationFailed(any(), anyString());
    }

    @Test void databaseFailureDuringActivityCheckDoesNotQuarantine() {
        when(history.matches(any())).thenReturn(true);
        when(history.canPublish(claim.operationId())).thenThrow(new DataAccessResourceFailureException("DB unavailable"));

        relay.deliver(claim);

        verify(store).failed(claim, false, "TRANSIENT_DATABASE");
        verify(history, never()).publicationFailed(any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test void aValidEnvelopeWithDifferentPersistedParametersIsQuarantined() {
        when(history.matches(any())).thenReturn(false);

        relay.deliver(claim);

        verify(history).publicationFailed(claim, "INVALID_PAYLOAD");
        verify(history, never()).canPublish(any());
        verifyNoInteractions(store, publisher);
    }
}
