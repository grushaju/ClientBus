package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaPlatformMessageConsumer {

    private final IMessageProcessingService
            messageProcessingService;

    public KafkaPlatformMessageConsumer(
            IMessageProcessingService messageProcessingService
    ) {
        this.messageProcessingService =
                messageProcessingService;
    }

    @KafkaListener(
            id = "kafkaPlatformMessageConsumer",
            groupId =
                    "${spring.kafka.consumer.platform-message-group-id}",
            topics =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).platformMessages()}"
    )
    public void consume(
            KafkaEvent<PlatformMessageRequest> event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka platform message event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.PLATFORM_MESSAGE) {

            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka platform message payload must not be null"
            );
        }

        messageProcessingService.processPlatformMessage(
                event.payload()
        );
    }
}