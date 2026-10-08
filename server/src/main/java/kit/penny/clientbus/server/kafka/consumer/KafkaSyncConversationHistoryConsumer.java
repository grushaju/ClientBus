package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.MessageDto;
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
import kit.penny.clientbus.server.service.IMessageProcessingService;
import kit.penny.clientbus.server.service.MessageService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class KafkaSyncConversationHistoryConsumer {

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    private final IPlatformMessagePublisher
            platformMessagePublisher;

    private final ConversationService
            conversationService;

    private final IMessageProcessingService messageProcessingService;

    private final MessageService messageService;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaSyncConversationHistoryConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            IPlatformMessagePublisher platformMessagePublisher,
            ConversationService conversationService,
            IMessageProcessingService messageProcessingService,
            MessageService messageService,
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;

        this.platformMessagePublisher =
                platformMessagePublisher;

        this.conversationService =
                conversationService;

        this.messageProcessingService =
                messageProcessingService;

        this.messageService =
                messageService;

        this.kafkaTemplate =
                kafkaTemplate;
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

        if (event.correlationId() != null) {
            processSynchronousHistory(
                    event.correlationId(),
                    command,
                    result
            );
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

    private void processSynchronousHistory(
            UUID correlationId,
            SyncConversationHistoryCommand command,
            SyncConversationHistoryResult result
    ) {
        List<MessageDto> messages =
                new ArrayList<>();

        if (result.messages() != null) {

            for (PlatformMessageRequest message :
                    result.messages()) {

                if (message == null) {
                    continue;
                }

                messages.add(
                        messageProcessingService
                                .processPlatformMessage(message)
                );
            }
        }

        if (result.historyStartReached()) {

            conversationService.markHistoryStartReached(
                    command.channelAccountId(),
                    command.conversationExternalId()
            );
        }

        SyncConversationHistoryKafkaCommand.Result response =
                new SyncConversationHistoryKafkaCommand.Result(
                        List.copyOf(messages),
                        result.historyStartReached()
                );

        KafkaEvent<
                SyncConversationHistoryKafkaCommand.Result
                > event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.SYNC_CONVERSATION_HISTORY_RESULT,
                        1,
                        java.time.Instant.now(),
                        correlationId,
                        response
                );

        kafkaTemplate
                .send(
                        KafkaTopicNames.conversationHistoryResult(),
                        correlationId.toString(),
                        event
                )
                .join();
    }

    @KafkaListener(
            id = "kafkaSyncConversationHistoryResultConsumer",
            groupId =
                    "${spring.kafka.consumer.history-sync-result-group-id}",
            topics =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).conversationHistoryResult()}"
    )
    public void consumeResult(
            KafkaEvent<
                    SyncConversationHistoryKafkaCommand.Result
                    > event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka sync conversation history result event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.SYNC_CONVERSATION_HISTORY_RESULT) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.correlationId() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync conversation history result correlationId must not be null"
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync conversation history result payload must not be null"
            );
        }

        messageService.completeHistorySync(
                event.correlationId(),
                event.payload()
        );
    }

}