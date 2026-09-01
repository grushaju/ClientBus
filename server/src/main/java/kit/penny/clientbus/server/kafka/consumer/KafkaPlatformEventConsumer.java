package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaPlatformEventConsumer
        implements IPlatformEventConsumer {

    private final IMessageProcessingService messageProcessingService;

    public KafkaPlatformEventConsumer(
            IMessageProcessingService messageProcessingService
    ) {
        this.messageProcessingService =
                messageProcessingService;
    }

    @Override
    @KafkaListener(
            id = "kafkaPlatformEventConsumer",
            groupId = "${spring.kafka.consumer.platform-events-group-id}",
            topics = "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).platformEvents()}"
    )
    public void consume(
            KafkaEvent<PlatformMessageKafkaEvent> event
    ) {
        validateEvent(event);

        PlatformMessageKafkaEvent payload =
                event.payload();

        PlatformMessageEvent platformEvent =
                new PlatformMessageEvent(
                        payload.channelAccountId(),
                        payload.externalId(),
                        payload.type(),
                        event.occurredAt(),
                        payload.metadata()
                );

        messageProcessingService.processPlatformEvent(
                platformEvent
        );
    }

    private void validateEvent(
            KafkaEvent<PlatformMessageKafkaEvent> event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka platform event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.PLATFORM_MESSAGE_EVENT) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka platform event payload must not be null"
            );
        }
    }
}