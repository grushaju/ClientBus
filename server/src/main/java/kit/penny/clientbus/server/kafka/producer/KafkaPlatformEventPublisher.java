package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaPlatformEventPublisher
        implements IPlatformEventPublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPlatformEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            PlatformMessageEvent event,
            UUID correlationId
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Event must not be null"
            );
        }

        if (event.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "ChannelAccountId must not be null"
            );
        }

        if (event.externalId() == null
                || event.externalId().isBlank()) {

            throw new IllegalArgumentException(
                    "ExternalId must not be blank"
            );
        }

        if (event.type() == null) {
            throw new IllegalArgumentException(
                    "Event type must not be null"
            );
        }

        if (correlationId == null) {
            throw new IllegalArgumentException(
                    "CorrelationId must not be null"
            );
        }

        PlatformMessageKafkaEvent payload =
                new PlatformMessageKafkaEvent(
                        event.channelAccountId(),
                        event.externalId(),
                        event.type(),
                        event.metadata()
                );

        KafkaEvent<PlatformMessageKafkaEvent> kafkaEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE_EVENT,
                        SCHEMA_VERSION,
                        event.occurredAt() != null
                                ? event.occurredAt()
                                : Instant.now(),
                        correlationId,
                        payload
                );

        kafkaTemplate.send(
                KafkaTopicNames.platformEvents(),
                event.channelAccountId().toString(),
                kafkaEvent
        );
    }
}