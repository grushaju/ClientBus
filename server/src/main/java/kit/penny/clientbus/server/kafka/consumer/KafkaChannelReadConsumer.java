package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.ChannelAvailabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class KafkaChannelReadConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(
                    KafkaChannelReadConsumer.class
            );

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    private final ChannelAvailabilityService channelAvailabilityService;

    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaChannelReadConsumer(
            ChannelConnectorRegistry channelConnectorRegistry,
            ChannelAvailabilityService channelAvailabilityService,
            org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;
        this.channelAvailabilityService =
                channelAvailabilityService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            id = "kafkaChannelReadConsumer",
            groupId = "${spring.kafka.consumer.channel-read-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).channelReadPattern()}"
    )
    public void consume(
            KafkaEvent<ChannelReadKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        validateEvent(event);

        ChannelType channelType =
                KafkaTopicNames.channelReadChannelType(
                        topic
                );

        ChannelReadKafkaCommand kafkaCommand =
                event.payload();

        log.debug(
                "Processing channel read: " +
                        "channelAccountId={}, " +
                        "channelType={}, " +
                        "recipientExternalId={}, " +
                        "lastReadExternalId={}",
                kafkaCommand.channelAccountId(),
                channelType,
                kafkaCommand.recipientExternalId(),
                kafkaCommand.lastReadExternalId()
        );

        channelAvailabilityService.requireConnected(
                kafkaCommand.channelAccountId()
        );

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(
                        channelType
                );

        var next = connector.continueRead(
                new MarkMessagesReadCommand(
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.lastReadExternalId(),
                        kafkaCommand.dialogExternalId(),
                        kafkaCommand.beforeExternalId()
                )
        );
        if (next != null) {
            var continuation = new ChannelReadKafkaCommand(next.channelAccountId(), next.recipientExternalId(),
                    next.lastReadExternalId(), next.dialogExternalId(), next.beforeExternalId());
            kafkaTemplate.send(topic, next.channelAccountId().toString(), new KafkaEvent<>(
                    java.util.UUID.randomUUID(), KafkaEventType.CHANNEL_READ, 1, java.time.Instant.now(),
                    event.correlationId(), continuation)).join();
        }
    }

    private void validateEvent(
            KafkaEvent<ChannelReadKafkaCommand> event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka channel read event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.CHANNEL_READ) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.correlationId() == null) {
            throw new IllegalArgumentException(
                    "Kafka channel read event correlationId "
                            + "must not be null"
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka channel read event payload "
                            + "must not be null"
            );
        }

        ChannelReadKafkaCommand command =
                event.payload();

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "ChannelAccountId must not be null"
            );
        }

        if (command.recipientExternalId() == null
                || command.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "RecipientExternalId must not be blank"
            );
        }

        if (command.lastReadExternalId() == null
                || command.lastReadExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "LastReadExternalId must not be blank"
            );
        }
    }
}
