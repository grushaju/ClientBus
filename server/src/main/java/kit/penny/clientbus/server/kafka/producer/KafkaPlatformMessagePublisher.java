package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaPlatformMessagePublisher
        implements IPlatformMessagePublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPlatformMessagePublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            PlatformMessageRequest message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "Platform message must not be null"
            );
        }

        KafkaEvent<PlatformMessageRequest> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        message
                );

        kafkaTemplate.send(
                KafkaTopicNames.platformMessages(),
                message.channelAccountId().toString(),
                event
        );
    }
}