package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.telegram.startup.TelegramClientStartupService;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboundMessageConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaOutboundMessageConsumer.class);

    private final ChannelConnectorRegistry channelConnectorRegistry;
    private final OutboundMessageKafkaCommandMapper commandMapper;
    private final MessageService messageService;

    public KafkaOutboundMessageConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            OutboundMessageKafkaCommandMapper commandMapper,
            MessageService messageService
    ) {
        this.channelConnectorRegistry = channelConnectorRegistry;
        this.commandMapper = commandMapper;
        this.messageService = messageService;
    }

    @KafkaListener(
            id = "kafkaOutboundMessageConsumer",
            groupId = "${spring.kafka.consumer.outbound-group-id}",
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

        log.info(
                "Processing outbound message: messageId={},  " +
                        "channelType={}, topic={}, type={}, contentPresent={}, " +
                        "attachmentCount={}",
                command.messageId(),
                channelType,
                topic,
                command.type(),
                command.content() != null && !command.content().isBlank(),
                command.attachments() != null
                        ? command.attachments().size()
                        : 0
        );

        if (isAlreadyAcceptedForDelivery(command.messageId())) {
            log.info(
                    "Skipping outbound message: already accepted for delivery, " +
                            "messageId={}",
                    command.messageId()
            );
            return;
        }

        IChannelConnector connector;

        try {
            connector =
                    channelConnectorRegistry.getConnector(channelType);
        } catch (Exception e) {
            log.error(
                    "Failed to resolve channel connector: messageId={}, " +
                            "channelType={}, topic={}",
                    command.messageId(),
                    channelType,
                    topic,
                    e
            );
            throw e;
        }

        log.debug(
                "Resolved outbound connector: messageId={}, channelType={}, " +
                        "connector={}",
                command.messageId(),
                channelType,
                connector.getClass().getSimpleName()
        );

        ChannelSendRequest request;

        try {
            request =
                    commandMapper.toRequest(command);
        } catch (Exception e) {
            log.error(
                    "Failed to map outbound command to connector request: " +
                            "messageId={}, channelType={}",
                    command.messageId(),
                    channelType,
                    e
            );
            throw e;
        }

        log.debug(
                "Outbound connector request prepared: messageId={}, " +
                        "channelType={}, connector={}",
                command.messageId(),
                channelType,
                connector.getClass().getSimpleName()
        );

        ConnectorSendResult result;

        try {
            result =
                    connector.send(request);
        } catch (Exception e) {
            log.error(
                    "Connector threw exception while sending outbound message: " +
                            "messageId={}, channelType={}, " +
                            "connector={}",
                    command.messageId(),
                    channelType,
                    connector.getClass().getSimpleName(),
                    e
            );
            throw e;
        }

        log.info(
                "Outbound connector send completed: messageId={}, " +
                        "channelType={}, connector={}, " +
                        "result={}",
                command.messageId(),
                channelType,
                connector.getClass().getSimpleName(),
                result
        );
    }
    private boolean isAlreadyAcceptedForDelivery(
            java.util.UUID messageId
    ) {
        MessageEntity message =
                messageService.getMessageEntityForProcessing(messageId);

        return message.getProcessingStatus()
                == MessageProcessingStatus.QUEUED
                && message.getDeliveryStatus()
                == MessageDeliveryStatus.PENDING
                && message.getExternalId() != null
                && !message.getExternalId().isBlank();
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
