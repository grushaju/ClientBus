package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformConversationPublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import kit.penny.clientbus.server.service.RecentChatsSynchronizationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class KafkaSyncRecentChatsConsumer {

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    private final RecentChatsSynchronizationService
            recentChatsSynchronizationService;

    private final RecentChatsSyncCoordinator
            recentChatsSyncCoordinator;

    public KafkaSyncRecentChatsConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            RecentChatsSynchronizationService
                    recentChatsSynchronizationService,
            RecentChatsSyncCoordinator
                    recentChatsSyncCoordinator
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;

        this.recentChatsSynchronizationService =
                recentChatsSynchronizationService;

        this.recentChatsSyncCoordinator =
                recentChatsSyncCoordinator;
    }

    @KafkaListener(
            id = "kafkaSyncRecentChatsConsumer",
            groupId =
                    "${spring.kafka.consumer.connector-command-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).channelCommandPattern()}"
    )
    public void consume(
            KafkaEvent<SyncRecentChatsKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.SYNC_RECENT_CHATS) {

            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats payload must not be null"
            );
        }

        UUID channelAccountId =
                event.payload().channelAccountId();

        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats channelAccountId must not be null"
            );
        }

        UUID syncRunId = event.eventId();

        if (syncRunId == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats eventId must not be null"
            );
        }

        if (!recentChatsSyncCoordinator.isExecutable(
                channelAccountId,
                syncRunId
        )) {
            recentChatsSyncCoordinator.complete(
                    channelAccountId,
                    syncRunId
            );

            return;
        }

        ChannelType channelType =
                KafkaTopicNames.channelCommandChannelType(
                        topic
                );

        SyncRecentChatsKafkaCommand payload =
                event.payload();

        SyncRecentChatsCommand command =
                new SyncRecentChatsCommand(
                        payload.channelAccountId()
                );

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(
                        channelType
                );

        List<PlatformConversationRequest> conversations =
                connector.handle(command);

        recentChatsSynchronizationService.process(
                channelType,
                conversations
        );

        recentChatsSyncCoordinator.complete(
                channelAccountId,
                syncRunId
        );
    }
}