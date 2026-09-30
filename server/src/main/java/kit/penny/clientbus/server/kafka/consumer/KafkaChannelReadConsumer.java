package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
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

    public KafkaChannelReadConsumer(
            ChannelConnectorRegistry channelConnectorRegistry
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;
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

        log.info(
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

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(
                        channelType
                );

        connector.handle(
                new MarkMessagesReadCommand(
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.lastReadExternalId()
                )
        );
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