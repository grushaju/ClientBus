package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaInboundEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaInboundEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher =
                new KafkaInboundEventPublisher(
                        kafkaTemplate
                );
    }

    @Test
    void publish_sendsEventToInboundTopicWithChannelAccountAsKey() {

        UUID channelAccountId =
                UUID.randomUUID();

        InboundMessageRequest message =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        "+79990000000",
                        "Client",
                        "external-123",
                        MessageType.TEXT,
                        "Hello",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-27T10:00:00Z"
                        )
                );

        PlatformInboundAttachment attachment =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/photo.jpg",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        message,
                        List.of(attachment)
                );

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(Object.class)
                )
        ).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        publisher.publish(event);

        ArgumentCaptor<String> topicCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<Object> valueCaptor =
                ArgumentCaptor.forClass(Object.class);

        verify(kafkaTemplate)
                .send(
                        topicCaptor.capture(),
                        keyCaptor.capture(),
                        valueCaptor.capture()
                );

        assertEquals(
                KafkaTopicNames.inbound(),
                topicCaptor.getValue()
        );

        assertEquals(
                channelAccountId.toString(),
                keyCaptor.getValue()
        );

        assertInstanceOf(
                KafkaEvent.class,
                valueCaptor.getValue()
        );

        KafkaEvent<?> kafkaEvent =
                (KafkaEvent<?>) valueCaptor.getValue();

        assertNotNull(
                kafkaEvent.eventId()
        );

        assertEquals(
                KafkaEventType.INBOUND_MESSAGE,
                kafkaEvent.eventType()
        );

        assertEquals(
                1,
                kafkaEvent.schemaVersion()
        );

        assertNotNull(
                kafkaEvent.occurredAt()
        );

        assertNotNull(
                kafkaEvent.correlationId()
        );

        assertSame(
                event,
                kafkaEvent.payload()
        );
    }

    @Test
    void publish_nullEvent_throwsException() {

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> publisher.publish(null)
                );

        assertEquals(
                "Event must not be null",
                exception.getMessage()
        );

        verifyNoInteractions(
                kafkaTemplate
        );
    }

    @Test
    void publish_sendsExactlyOnce() {

        UUID channelAccountId =
                UUID.randomUUID();

        InboundMessageRequest message =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        null,
                        null,
                        "Client",
                        "external-123",
                        MessageType.TEXT,
                        "Hello",
                        null,
                        null
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        message,
                        List.of()
                );

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(Object.class)
                )
        ).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        publisher.publish(event);

        verify(
                kafkaTemplate,
                times(1)
        ).send(
                any(String.class),
                any(String.class),
                any(Object.class)
        );
    }
}