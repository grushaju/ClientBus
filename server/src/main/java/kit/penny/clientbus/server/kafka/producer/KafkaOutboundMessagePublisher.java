package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaOutboundMessagePublisher
        implements IOutboundMessagePublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaOutboundMessagePublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            ChannelType channelType,
            OutboundMessageKafkaCommand command
    ) {
        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        if (command == null) {
            throw new IllegalArgumentException(
                    "Command must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "ChannelAccountId must not be null"
            );
        }

        KafkaEvent<OutboundMessageKafkaCommand> kafkaEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        command
                );

        kafkaTemplate.send(
                KafkaTopicNames.outbound(channelType),
                command.channelAccountId().toString(),
                kafkaEvent
        );
    }
}
