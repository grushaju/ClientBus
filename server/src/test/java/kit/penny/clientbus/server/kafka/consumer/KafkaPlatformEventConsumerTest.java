package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class KafkaPlatformEventConsumerTest {

    @Mock
    private IMessageProcessingService messageProcessingService;

    private KafkaPlatformEventConsumer consumer;

    @BeforeEach
    void setUp() {

        consumer =
                new KafkaPlatformEventConsumer(
                        messageProcessingService
                );
    }

    @Test
    void consume_validPlatformEvent_processesMappedEvent() {

        UUID channelAccountId =
                UUID.randomUUID();

        String externalId =
                "external-message-123";

        Instant occurredAt =
                Instant.parse(
                        "2026-01-01T12:00:00Z"
                );

        String metadata =
                "{\"status\":\"delivered\"}";

        PlatformMessageKafkaEvent payload =
                new PlatformMessageKafkaEvent(
                        channelAccountId,
                        externalId,
                        PlatformMessageEventType.DELIVERED,
                        metadata
                );

        KafkaEvent<PlatformMessageKafkaEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE_EVENT,
                        1,
                        occurredAt,
                        UUID.randomUUID(),
                        payload
                );

        assertDoesNotThrow(
                () ->
                        consumer.consume(event)
        );

        ArgumentCaptor<PlatformMessageEvent> captor =
                ArgumentCaptor.forClass(
                        PlatformMessageEvent.class
                );

        verify(
                messageProcessingService
        ).processPlatformEvent(
                captor.capture()
        );

        PlatformMessageEvent processedEvent =
                captor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                channelAccountId,
                processedEvent.channelAccountId()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                externalId,
                processedEvent.externalId()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                PlatformMessageEventType.DELIVERED,
                processedEvent.type()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                occurredAt,
                processedEvent.occurredAt()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                metadata,
                processedEvent.metadata()
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

        KafkaEvent<PlatformMessageKafkaEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE_EVENT,
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

        PlatformMessageKafkaEvent payload =
                new PlatformMessageKafkaEvent(
                        UUID.randomUUID(),
                        "external-message-123",
                        PlatformMessageEventType.DELIVERED,
                        null
                );

        KafkaEvent<PlatformMessageKafkaEvent> event =
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

        PlatformMessageKafkaEvent payload =
                new PlatformMessageKafkaEvent(
                        UUID.randomUUID(),
                        "external-message-123",
                        PlatformMessageEventType.READ,
                        null
                );

        KafkaEvent<PlatformMessageKafkaEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE_EVENT,
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
                .processPlatformEvent(
                        org.mockito.ArgumentMatchers.any(
                                PlatformMessageEvent.class
                        )
                );

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
        ).processPlatformEvent(
                org.mockito.ArgumentMatchers.any(
                        PlatformMessageEvent.class
                )
        );
    }
}