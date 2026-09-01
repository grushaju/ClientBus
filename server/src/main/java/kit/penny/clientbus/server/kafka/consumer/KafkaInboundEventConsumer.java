package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaInboundEventConsumer
        implements IInboundEventConsumer {

    private final IMessageProcessingService messageProcessingService;

    public KafkaInboundEventConsumer(
            IMessageProcessingService messageProcessingService
    ) {
        this.messageProcessingService =
                messageProcessingService;
    }

    @Override
    @KafkaListener(
            id = "kafkaInboundEventConsumer",
            groupId = "${spring.kafka.consumer.inbound-group-id}",
            topics = "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).inbound()}"
    )
    public void consume(
            KafkaEvent<PlatformInboundMessageEvent> event
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka inbound event must not be null"
            );
        }

        if (
                event.eventType()
                        != KafkaEventType.INBOUND_MESSAGE
        ) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka inbound event payload must not be null"
            );
        }

        messageProcessingService.processInbound(
                event.payload()
        );
    }
}