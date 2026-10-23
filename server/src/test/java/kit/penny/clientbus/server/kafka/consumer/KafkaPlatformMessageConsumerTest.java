package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaPlatformMessageConsumerTest {
    private final IMessageProcessingService service = mock(IMessageProcessingService.class);
    private final KafkaPlatformMessageConsumer consumer = new KafkaPlatformMessageConsumer(service);
    private final PlatformMessageRequest request = new PlatformMessageRequest(UUID.randomUUID(), "client", null,
            null, "Client", "sender", "external", MessageType.TEXT, "Hello", null, Instant.now(), List.of(), EventOrigin.LIVE);

    @Test void legacyEnvelopeWithoutEventIdOrObservationTimeCannotAcquireLiveSourceContext() {
        consumer.consume(new KafkaEvent<>(null, KafkaEventType.PLATFORM_MESSAGE, 1,
                Instant.now(), UUID.randomUUID(), request));
        consumer.consume(new KafkaEvent<>(UUID.randomUUID(), KafkaEventType.PLATFORM_MESSAGE, 1,
                null, UUID.randomUUID(), request));

        verify(service, times(2)).processPlatformMessage(request, null);
        verifyNoMoreInteractions(service);
    }

    @Test void validEnvelopePreservesItsOriginalObservationTimeAndEventIdentity() {
        UUID eventId = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");
        consumer.consume(new KafkaEvent<>(eventId, KafkaEventType.PLATFORM_MESSAGE, 1,
                observedAt, UUID.randomUUID(), request));

        var source = ArgumentCaptor.forClass(NotificationSourceContext.class);
        verify(service).processPlatformMessage(eq(request), source.capture());
        assertEquals(EventOrigin.LIVE, source.getValue().origin());
        assertEquals(observedAt, source.getValue().firstObservedAt());
        assertEquals(eventId, source.getValue().sourceEventId());
    }
}
