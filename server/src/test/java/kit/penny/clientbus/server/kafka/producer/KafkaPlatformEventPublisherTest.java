package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KafkaPlatformEventPublisherTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaPlatformEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaPlatformEventPublisher(kafkaTemplate);
    }

    @Test
    void shouldPublishPlatformEvent() {

        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "external-123",
                        PlatformMessageEventType.SENT,
                        Instant.parse("2026-09-01T10:15:30Z"),
                        "metadata"
                );

        publisher.publish(event, correlationId);

        verify(kafkaTemplate).send(
                eq(KafkaTopicNames.platformEvents()),
                eq(channelAccountId.toString()),
                any(KafkaEvent.class)
        );
    }

    @Test
    void shouldBuildCorrectKafkaEnvelope() {

        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Instant occurredAt =
                Instant.parse("2026-09-01T10:15:30Z");

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "external-456",
                        PlatformMessageEventType.DELIVERED,
                        occurredAt,
                        "metadata"
                );

        publisher.publish(event, correlationId);

        verify(kafkaTemplate).send(
                eq(KafkaTopicNames.platformEvents()),
                eq(channelAccountId.toString()),
                argThat(value -> {

                    if (!(value instanceof KafkaEvent<?> kafkaEvent)) {
                        return false;
                    }

                    if (kafkaEvent.eventType()
                            != KafkaEventType.PLATFORM_MESSAGE_EVENT) {
                        return false;
                    }

                    if (kafkaEvent.schemaVersion() != 1) {
                        return false;
                    }

                    if (!correlationId.equals(
                            kafkaEvent.correlationId())) {
                        return false;
                    }

                    if (!occurredAt.equals(
                            kafkaEvent.occurredAt())) {
                        return false;
                    }

                    if (!(kafkaEvent.payload()
                            instanceof PlatformMessageKafkaEvent payload)) {
                        return false;
                    }

                    return channelAccountId.equals(
                            payload.channelAccountId())
                            && "external-456".equals(
                            payload.externalId())
                            && PlatformMessageEventType.DELIVERED
                            == payload.type()
                            && "metadata".equals(
                            payload.metadata());
                })
        );
    }

    @Test
    void shouldUseCurrentTimeWhenOccurredAtIsNull() {

        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "external-123",
                        PlatformMessageEventType.SENT,
                        null,
                        null
                );

        Instant before = Instant.now();

        publisher.publish(event, correlationId);

        Instant after = Instant.now();

        verify(kafkaTemplate).send(
                anyString(),
                anyString(),
                argThat(value -> {

                    if (!(value instanceof KafkaEvent<?> kafkaEvent)) {
                        return false;
                    }

                    return !kafkaEvent.occurredAt()
                            .isBefore(before)
                            && !kafkaEvent.occurredAt()
                            .isAfter(after);
                })
        );
    }

    @Test
    void shouldRejectNullEvent() {

        assertThatThrownBy(() ->
                publisher.publish(
                        null,
                        UUID.randomUUID()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event must not be null");

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldRejectNullChannelAccountId() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        null,
                        "external-123",
                        PlatformMessageEventType.SENT,
                        Instant.now(),
                        null
                );

        assertThatThrownBy(() ->
                publisher.publish(
                        event,
                        UUID.randomUUID()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "ChannelAccountId must not be null"
                );

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldRejectNullCorrelationId() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        UUID.randomUUID(),
                        "external-123",
                        PlatformMessageEventType.SENT,
                        Instant.now(),
                        null
                );

        assertThatThrownBy(() ->
                publisher.publish(event, null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "CorrelationId must not be null"
                );

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldRejectBlankExternalId() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        UUID.randomUUID(),
                        " ",
                        PlatformMessageEventType.SENT,
                        Instant.now(),
                        null
                );

        assertThatThrownBy(() ->
                publisher.publish(
                        event,
                        UUID.randomUUID()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "ExternalId must not be blank"
                );

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldRejectNullEventType() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        UUID.randomUUID(),
                        "external-123",
                        null,
                        Instant.now(),
                        null
                );

        assertThatThrownBy(() ->
                publisher.publish(
                        event,
                        UUID.randomUUID()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Event type must not be null"
                );

        verifyNoInteractions(kafkaTemplate);
    }
}