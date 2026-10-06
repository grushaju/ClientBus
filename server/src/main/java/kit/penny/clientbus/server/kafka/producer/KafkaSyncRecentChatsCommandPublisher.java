package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaSyncRecentChatsCommandPublisher
        implements ISyncRecentChatsCommandPublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object>
            kafkaTemplate;

    public KafkaSyncRecentChatsCommandPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate =
                kafkaTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(
            ChannelType channelType,
            SyncRecentChatsCommand command,
            UUID syncRunId
    ) {

        if (channelType == null) {
            throw new IllegalArgumentException(
                    "channelType must not be null"
            );
        }

        if (command == null) {
            throw new IllegalArgumentException(
                    "SyncRecentChatsCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (syncRunId == null) {
            throw new IllegalArgumentException(
                    "syncRunId must not be null"
            );
        }

        SyncRecentChatsKafkaCommand payload =
                new SyncRecentChatsKafkaCommand(
                        command.channelAccountId()
                );

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        syncRunId,
                        KafkaEventType.SYNC_RECENT_CHATS,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        return kafkaTemplate
                .send(
                        KafkaTopicNames.channelCommand(
                                channelType
                        ),
                        command.channelAccountId().toString(),
                        event
                )
                .thenApply(
                        ignored -> null
                );
    }
}