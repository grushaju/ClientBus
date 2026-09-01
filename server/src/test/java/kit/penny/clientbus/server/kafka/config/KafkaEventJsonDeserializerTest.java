package kit.penny.clientbus.server.kafka.config;

import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaEventJsonDeserializerTest {

    private KafkaEventJsonSerializer serializer;
    private KafkaEventJsonDeserializer deserializer;

    @BeforeEach
    void setUp() {
        serializer = new KafkaEventJsonSerializer();
        deserializer = new KafkaEventJsonDeserializer();
    }

    @Test
    void shouldDeserializeOutboundMessage() {

        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Instant occurredAt =
                Instant.parse("2026-09-01T10:00:00Z");

        OutboundMessageKafkaCommand payload =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-123",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        eventId,
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        occurredAt,
                        correlationId,
                        payload
                );

        byte[] data =
                serializer.serialize(
                        "clientbus.outbound.telegram",
                        event
                );

        KafkaEvent<?> result =
                deserializer.deserialize(
                        "clientbus.outbound.telegram",
                        data
                );

        assertThat(result.eventId())
                .isEqualTo(eventId);

        assertThat(result.eventType())
                .isEqualTo(KafkaEventType.OUTBOUND_MESSAGE);

        assertThat(result.schemaVersion())
                .isEqualTo(1);

        assertThat(result.occurredAt())
                .isEqualTo(occurredAt);

        assertThat(result.correlationId())
                .isEqualTo(correlationId);

        assertThat(result.payload())
                .isInstanceOf(
                        OutboundMessageKafkaCommand.class
                );

        OutboundMessageKafkaCommand resultPayload =
                (OutboundMessageKafkaCommand)
                        result.payload();

        assertThat(resultPayload.messageId())
                .isEqualTo(messageId);

        assertThat(resultPayload.channelAccountId())
                .isEqualTo(channelAccountId);

        assertThat(resultPayload.recipientExternalId())
                .isEqualTo("recipient-123");

        assertThat(resultPayload.type())
                .isEqualTo(MessageType.TEXT);

        assertThat(resultPayload.content())
                .isEqualTo("Hello");

        assertThat(resultPayload.attachments())
                .isEmpty();
    }

    @Test
    void shouldDeserializePlatformMessageEvent() {

        UUID eventId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Instant occurredAt =
                Instant.parse("2026-09-01T10:00:00Z");

        PlatformMessageKafkaEvent payload =
                new PlatformMessageKafkaEvent(
                        channelAccountId,
                        "external-123",
                        PlatformMessageEventType.SENT,
                        "metadata"
                );

        KafkaEvent<PlatformMessageKafkaEvent> event =
                new KafkaEvent<>(
                        eventId,
                        KafkaEventType.PLATFORM_MESSAGE_EVENT,
                        1,
                        occurredAt,
                        correlationId,
                        payload
                );

        byte[] data =
                serializer.serialize(
                        "clientbus.platform-events",
                        event
                );

        KafkaEvent<?> result =
                deserializer.deserialize(
                        "clientbus.platform-events",
                        data
                );

        assertThat(result.eventId())
                .isEqualTo(eventId);

        assertThat(result.eventType())
                .isEqualTo(
                        KafkaEventType.PLATFORM_MESSAGE_EVENT
                );

        assertThat(result.schemaVersion())
                .isEqualTo(1);

        assertThat(result.occurredAt())
                .isEqualTo(occurredAt);

        assertThat(result.correlationId())
                .isEqualTo(correlationId);

        assertThat(result.payload())
                .isInstanceOf(
                        PlatformMessageKafkaEvent.class
                );

        PlatformMessageKafkaEvent resultPayload =
                (PlatformMessageKafkaEvent)
                        result.payload();

        assertThat(resultPayload.channelAccountId())
                .isEqualTo(channelAccountId);

        assertThat(resultPayload.externalId())
                .isEqualTo("external-123");

        assertThat(resultPayload.type())
                .isEqualTo(
                        PlatformMessageEventType.SENT
                );

        assertThat(resultPayload.metadata())
                .isEqualTo("metadata");
    }

    @Test
    void shouldReturnNullForNullData() {

        assertThat(
                deserializer.deserialize(
                        "clientbus.outbound.telegram",
                        null
                )
        ).isNull();
    }

    @Test
    void shouldRejectInvalidJson() {

        byte[] data =
                "{invalid-json}"
                        .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                deserializer.deserialize(
                        "clientbus.outbound.telegram",
                        data
                )
        )
                .isInstanceOf(SerializationException.class)
                .hasMessageStartingWith(
                        "Failed to deserialize KafkaEvent"
                );
    }

    @Test
    void shouldRejectUnknownEventType() {

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "UNKNOWN_EVENT",
                  "schemaVersion": 1,
                  "occurredAt": "2026-09-01T10:00:00Z",
                  "correlationId": "%s",
                  "payload": {}
                }
                """.formatted(
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        assertThatThrownBy(() ->
                deserializer.deserialize(
                        "clientbus.outbound.telegram",
                        json.getBytes(StandardCharsets.UTF_8)
                )
        )
                .isInstanceOf(SerializationException.class)
                .hasMessageStartingWith(
                        "Failed to deserialize KafkaEvent"
                );
    }
}