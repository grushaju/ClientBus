package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaPlatformConversationConsumer {

    private final IMessageProcessingService
            messageProcessingService;

    public KafkaPlatformConversationConsumer(
            IMessageProcessingService messageProcessingService
    ) {
        this.messageProcessingService =
                messageProcessingService;
    }

    @KafkaListener(
            id = "kafkaPlatformConversationConsumer",
            groupId =
                    "${spring.kafka.consumer.platform-conversation-group-id}",
            topics =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).platformConversations()}"
    )
    public void consume(
            KafkaEvent<PlatformConversationRequest> event
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka platform conversation event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.PLATFORM_CONVERSATION) {

            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka platform conversation payload must not be null"
            );
        }

        messageProcessingService
                .processPlatformConversation(
                        event.payload()
                );
    }
}