package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KafkaSyncConversationHistoryConsumer {

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    private final IPlatformMessagePublisher
            platformMessagePublisher;

    public KafkaSyncConversationHistoryConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            IPlatformMessagePublisher platformMessagePublisher
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;

        this.platformMessagePublisher =
                platformMessagePublisher;
    }

    @KafkaListener(
            id = "kafkaSyncConversationHistoryConsumer",
            groupId =
                    "${spring.kafka.consumer.connector-command-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).channelCommandPattern()}"
    )
    public void consume(
            KafkaEvent<SyncConversationHistoryKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka sync conversation history event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.SYNC_CONVERSATION_HISTORY) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync conversation history payload must not be null"
            );
        }

        ChannelType channelType =
                KafkaTopicNames.channelCommandChannelType(
                        topic
                );

        SyncConversationHistoryKafkaCommand payload =
                event.payload();

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        payload.channelAccountId(),
                        payload.conversationExternalId(),
                        payload.beforeExternalId(),
                        payload.limit()
                );

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(
                        channelType
                );

        List<PlatformMessageRequest> messages =
                connector.handle(command);

        if (messages == null
                || messages.isEmpty()) {
            return;
        }

        for (PlatformMessageRequest message : messages) {
            if (message == null) {
                continue;
            }

            platformMessagePublisher.publish(
                    message
            );
        }
    }
}