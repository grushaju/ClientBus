package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KafkaInboundEventConsumerTest {

    @Mock
    private IMessageProcessingService messageProcessingService;

    private KafkaInboundEventConsumer consumer;

    @BeforeEach
    void setUp() {

        consumer =
                new KafkaInboundEventConsumer(
                        messageProcessingService
                );
    }

    @Test
    void consume_validInboundEvent_processesPayload() {

        PlatformInboundMessageEvent payload =
                org.mockito.Mockito.mock(
                        PlatformInboundMessageEvent.class
                );

        KafkaEvent<PlatformInboundMessageEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        assertDoesNotThrow(
                () ->
                        consumer.consume(event)
        );

        verify(
                messageProcessingService
        ).processInbound(
                payload
        );
    }

    @Test
    void consume_nullEvent_throwsException() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        consumer.consume(null)
        );

        verifyNoInteractions(
                messageProcessingService
        );
    }

    @Test
    void consume_nullPayload_throwsException() {

        KafkaEvent<PlatformInboundMessageEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        consumer.consume(event)
        );

        verifyNoInteractions(
                messageProcessingService
        );
    }

    @Test
    void consume_unsupportedEventType_throwsException() {

        PlatformInboundMessageEvent payload =
                org.mockito.Mockito.mock(
                        PlatformInboundMessageEvent.class
                );

        KafkaEvent<PlatformInboundMessageEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        consumer.consume(event)
        );

        verifyNoInteractions(
                messageProcessingService
        );
    }

    @Test
    void consume_processingServiceThrowsException_propagatesException() {

        PlatformInboundMessageEvent payload =
                org.mockito.Mockito.mock(
                        PlatformInboundMessageEvent.class
                );

        KafkaEvent<PlatformInboundMessageEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        RuntimeException exception =
                new RuntimeException(
                        "Processing failed"
                );

        doThrow(exception)
                .when(messageProcessingService)
                .processInbound(payload);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                consumer.consume(event)
                );

        assertSame(
                exception,
                thrown
        );

        verify(
                messageProcessingService
        ).processInbound(
                payload
        );
    }
}