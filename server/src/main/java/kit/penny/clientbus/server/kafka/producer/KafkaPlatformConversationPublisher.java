package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaPlatformConversationPublisher
        implements IPlatformConversationPublisher {

    private static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPlatformConversationPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            PlatformConversationRequest conversation
    ) {

        if (conversation == null) {
            throw new IllegalArgumentException(
                    "Platform conversation must not be null"
            );
        }

        KafkaEvent<PlatformConversationRequest> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_CONVERSATION,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        conversation
                );

        kafkaTemplate.send(
                KafkaTopicNames.platformConversations(),
                conversation.channelAccountId().toString(),
                event
        );
    }
}