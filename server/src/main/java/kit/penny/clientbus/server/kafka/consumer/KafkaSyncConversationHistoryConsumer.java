package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.SyncConversationHistoryResult;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.ConversationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class KafkaSyncConversationHistoryConsumer {

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    private final IPlatformMessagePublisher
            platformMessagePublisher;

    private final ConversationService
            conversationService;

    public KafkaSyncConversationHistoryConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            IPlatformMessagePublisher platformMessagePublisher,
            ConversationService conversationService
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;

        this.platformMessagePublisher =
                platformMessagePublisher;

        this.conversationService =
                conversationService;
    }

    @KafkaListener(
            id = "kafkaSyncConversationHistoryConsumer",
            groupId =
                    "${spring.kafka.consumer.connector-command-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).conversationHistoryCommandPattern()}"
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
                KafkaTopicNames.conversationHistoryCommandChannelType(
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

        SyncConversationHistoryResult result =
                connector.handle(command);

        if (result == null) {
            return;
        }

        if (result.messages() != null) {

            for (PlatformMessageRequest message :
                    result.messages()) {

                if (message == null) {
                    continue;
                }

                platformMessagePublisher.publish(
                        message
                );
            }
        }

        if (result.historyStartReached()) {

            conversationService.markHistoryStartReached(
                    command.channelAccountId(),
                    command.conversationExternalId()
            );
        }
    }
}