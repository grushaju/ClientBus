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
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class KafkaOutboundMessageConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(
                    KafkaOutboundMessageConsumer.class
            );

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
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).outboundPattern()}"
    )
    public void consume(
            KafkaEvent<OutboundMessageKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        validateEvent(event);

        ChannelType channelType =
                KafkaTopicNames.outboundChannelType(topic);

        OutboundMessageKafkaCommand kafkaCommand =
                event.payload();

        log.info(
                "Processing outbound message: messageId={}, " +
                        "channelType={}, topic={}, type={}, contentPresent={}, " +
                        "attachmentCount={}",
                kafkaCommand.messageId(),
                channelType,
                topic,
                kafkaCommand.type(),
                kafkaCommand.content() != null
                        && !kafkaCommand.content().isBlank(),
                kafkaCommand.attachments() != null
                        ? kafkaCommand.attachments().size()
                        : 0
        );

        if (isAlreadyAcceptedForDelivery(
                kafkaCommand.messageId()
        )) {
            log.info(
                    "Skipping outbound message: already accepted for delivery, " +
                            "messageId={}",
                    kafkaCommand.messageId()
            );
            return;
        }

        IChannelConnector connector;

        try {
            connector =
                    channelConnectorRegistry.getConnector(
                            channelType
                    );
        } catch (Exception e) {
            log.error(
                    "Failed to resolve channel connector: messageId={}, " +
                            "channelType={}, topic={}",
                    kafkaCommand.messageId(),
                    channelType,
                    topic,
                    e
            );
            throw e;
        }

        log.debug(
                "Resolved outbound connector: messageId={}, channelType={}, " +
                        "connector={}",
                kafkaCommand.messageId(),
                channelType,
                connector.getClass().getSimpleName()
        );

        SendMessageCommand command;

        try {
            command =
                    commandMapper.toCommand(
                            kafkaCommand
                    );
        } catch (Exception e) {
            log.error(
                    "Failed to map outbound Kafka command to connector command: " +
                            "messageId={}, channelType={}",
                    kafkaCommand.messageId(),
                    channelType,
                    e
            );
            throw e;
        }

        log.debug(
                "Outbound connector command prepared: messageId={}, " +
                        "channelType={}, connector={}",
                kafkaCommand.messageId(),
                channelType,
                connector.getClass().getSimpleName()
        );

        ConnectorSendResult result;

        try {
            result =
                    connector.handle(command);
        } catch (Exception e) {
            log.error(
                    "Connector threw exception while sending outbound message: " +
                            "messageId={}, channelType={}, connector={}",
                    kafkaCommand.messageId(),
                    channelType,
                    connector.getClass().getSimpleName(),
                    e
            );
            throw e;
        }

        if (result == null) {
            throw new IllegalStateException(
                    "Connector returned null send result: messageId="
                            + kafkaCommand.messageId()
            );
        }

        if (result.externalId() == null
                || result.externalId().isBlank()) {
            throw new IllegalStateException(
                    "Connector returned blank externalId: messageId="
                            + kafkaCommand.messageId()
            );
        }

        try {
            messageService.registerPendingExternalId(
                    kafkaCommand.messageId(),
                    result.externalId()
            );
        } catch (Exception e) {
            log.error(
                    "Message service threw exception while registering " +
                            "pending externalId: messageId={}, externalId={}",
                    kafkaCommand.messageId(),
                    result.externalId(),
                    e
            );
            throw e;
        }

        log.info(
                "Outbound connector send completed: messageId={}, " +
                        "channelType={}, connector={}, result={}",
                kafkaCommand.messageId(),
                channelType,
                connector.getClass().getSimpleName(),
                result
        );
    }

    private boolean isAlreadyAcceptedForDelivery(
            UUID messageId
    ) {
        MessageEntity message =
                messageService.getMessageEntityForProcessing(
                        messageId
                );

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