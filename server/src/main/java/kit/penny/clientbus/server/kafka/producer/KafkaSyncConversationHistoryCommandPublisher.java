package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaSyncConversationHistoryCommandPublisher
        implements ISyncConversationHistoryCommandPublisher {

    private static final int SCHEMA_VERSION = 1;

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 100;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaSyncConversationHistoryCommandPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            ChannelType channelType,
            SyncConversationHistoryCommand command
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "channelType must not be null"
            );
        }

        if (command == null) {
            throw new IllegalArgumentException(
                    "SyncConversationHistoryCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (command.conversationExternalId() == null
                || command.conversationExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "conversationExternalId must not be blank"
            );
        }

        int limit =
                command.limit() <= 0
                        ? DEFAULT_LIMIT
                        : Math.min(
                        command.limit(),
                        MAX_LIMIT
                );

        SyncConversationHistoryKafkaCommand payload =
                new SyncConversationHistoryKafkaCommand(
                        command.channelAccountId(),
                        command.conversationExternalId(),
                        command.beforeExternalId(),
                        limit
                );

        KafkaEvent<SyncConversationHistoryKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.SYNC_CONVERSATION_HISTORY,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        kafkaTemplate.send(
                KafkaTopicNames.conversationHistoryCommand(
                        channelType
                ),
                command.channelAccountId().toString(),
                event
        );
    }
}