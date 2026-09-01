package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.kafka.producer.IPlatformEventPublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KafkaOutboundMessageConsumer {

    private final ChannelConnectorRegistry channelConnectorRegistry;
    private final IAttachmentStorage attachmentStorage;
    private final IPlatformEventPublisher platformEventPublisher;

    public KafkaOutboundMessageConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            IAttachmentStorage attachmentStorage,
            IPlatformEventPublisher platformEventPublisher
    ) {
        this.channelConnectorRegistry = channelConnectorRegistry;
        this.attachmentStorage = attachmentStorage;
        this.platformEventPublisher = platformEventPublisher;
    }

    @KafkaListener(
            id = "kafkaOutboundMessageConsumer",
            groupId = "${clientbus.kafka.consumer.outbound-group-id}",
            topicPattern = "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).outboundPattern()}"
    )
    public void consume(
            KafkaEvent<OutboundMessageKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        validateEvent(event);

        ChannelType channelType =
                KafkaTopicNames.outboundChannelType(topic);

        OutboundMessageKafkaCommand command =
                event.payload();

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(channelType);

        ChannelSendRequest request =
                new ChannelSendRequest(
                        command.messageId(),
                        command.channelAccountId(),
                        command.recipientExternalId(),
                        command.type(),
                        command.content(),
                        loadAttachments(command)
                );

        ConnectorSendResult result =
                connector.send(request);

        if (result == null) {
            throw new IllegalStateException(
                    "Connector returned null send result"
            );
        }

        if (result.externalId() == null
                || result.externalId().isBlank()) {
            throw new IllegalStateException(
                    "Connector returned blank externalId"
            );
        }

        PlatformMessageEvent platformEvent =
                new PlatformMessageEvent(
                        command.channelAccountId(),
                        result.externalId(),
                        PlatformMessageEventType.SENT,
                        null,
                        null
                );

        platformEventPublisher.publish(
                platformEvent,
                event.correlationId()
        );
    }

    private List<ChannelAttachment> loadAttachments(
            OutboundMessageKafkaCommand command
    ) {
        if (command.attachments() == null
                || command.attachments().isEmpty()) {
            return List.of();
        }

        return command.attachments()
                .stream()
                .map(attachment ->
                        new ChannelAttachment(
                                attachment.type(),
                                attachment.fileName(),
                                attachment.contentType(),
                                attachment.size(),
                                attachmentStorage.load(
                                        attachment.storageKey()
                                )
                        )
                )
                .toList();
    }

    private void validateEvent(
            KafkaEvent<OutboundMessageKafkaCommand> event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka outbound event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.OUTBOUND_MESSAGE) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.correlationId() == null) {
            throw new IllegalArgumentException(
                    "Kafka outbound event correlationId "
                            + "must not be null"
            );
        }

        OutboundMessageKafkaCommand command =
                event.payload();

        if (command == null) {
            throw new IllegalArgumentException(
                    "Kafka outbound event payload "
                            + "must not be null"
            );
        }

        if (command.messageId() == null) {
            throw new IllegalArgumentException(
                    "Outbound messageId must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "Outbound channelAccountId must not be null"
            );
        }

        if (command.recipientExternalId() == null
                || command.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "Outbound recipientExternalId "
                            + "must not be blank"
            );
        }
    }
}