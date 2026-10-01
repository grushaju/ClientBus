package kit.penny.clientbus.server.kafka.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.kafka.*;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.charset.StandardCharsets;

public class KafkaEventJsonDeserializer
        implements Deserializer<KafkaEvent<?>> {

    private final ObjectMapper objectMapper;

    public KafkaEventJsonDeserializer() {
        this.objectMapper =
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .build();
    }

    @Override
    public KafkaEvent<?> deserialize(
            String topic,
            byte[] data
    ) {

        if (data == null) {
            return null;
        }

        try {

            JsonNode root =
                    objectMapper.readTree(
                            new String(
                                    data,
                                    StandardCharsets.UTF_8
                            )
                    );

            KafkaEventType eventType =
                    KafkaEventType.valueOf(
                            root.get("eventType")
                                    .asText()
                    );

            JsonNode payloadNode =
                    root.get("payload");

            Object payload =
                    deserializePayload(
                            eventType,
                            payloadNode
                    );

            return new KafkaEvent<>(
                    objectMapper.treeToValue(
                            root.get("eventId"),
                            java.util.UUID.class
                    ),
                    eventType,
                    root.get("schemaVersion").asInt(),
                    objectMapper.treeToValue(
                            root.get("occurredAt"),
                            java.time.Instant.class
                    ),
                    objectMapper.treeToValue(
                            root.get("correlationId"),
                            java.util.UUID.class
                    ),
                    payload
            );

        } catch (Exception e) {

            throw new SerializationException(
                    "Failed to deserialize KafkaEvent from topic "
                            + topic,
                    e
            );
        }
    }

    private Object deserializePayload(
            KafkaEventType eventType,
            JsonNode payload
    ) {

        if (payload == null || payload.isNull()) {
            return null;
        }

        return switch (eventType) {

            case PLATFORM_MESSAGE ->
                    objectMapper.convertValue(
                            payload,
                            PlatformMessageRequest.class
                    );

            case PLATFORM_MESSAGE_EVENT ->
                    objectMapper.convertValue(
                            payload,
                            PlatformMessageKafkaEvent.class
                    );

            case OUTBOUND_MESSAGE ->
                    objectMapper.convertValue(
                            payload,
                            OutboundMessageKafkaCommand.class
                    );

            case CHANNEL_READ ->
                    objectMapper.convertValue(
                            payload,
                            ChannelReadKafkaCommand.class
                    );

            case PLATFORM_CONVERSATION ->
                    objectMapper.convertValue(
                            payload,
                            PlatformConversationRequest.class
                    );

            case SYNC_RECENT_CHATS ->
                    objectMapper.convertValue(
                            payload,
                            SyncRecentChatsKafkaCommand.class
                    );

            case SYNC_CONVERSATION_HISTORY ->
                    objectMapper.convertValue(
                            payload,
                            SyncConversationHistoryCommand.class
                    );
        };
    }

    @Override
    public void close() {
        // Nothing to close.
    }
}