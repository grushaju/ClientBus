package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaInboundEventPublisher
        implements IInboundEventPublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaInboundEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            PlatformInboundMessageEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Event must not be null"
            );
        }

        KafkaEvent<PlatformInboundMessageEvent> kafkaEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        event
                );

        kafkaTemplate.send(
                KafkaTopicNames.inbound(),
                event.message()
                        .channelAccountId()
                        .toString(),
                kafkaEvent
        );
    }
}