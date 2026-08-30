package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class KafkaInboundEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private IMessageProcessingService messageProcessingService;

    @Test
    void consume_receivesInboundEventAndProcessesPayload()
            throws Exception {

        UUID channelAccountId =
                UUID.randomUUID();

        InboundMessageRequest message =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        "+79990000000",
                        "Client",
                        "external-" + UUID.randomUUID(),
                        MessageType.TEXT,
                        "Hello Kafka Consumer",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-28T10:00:00Z"
                        )
                );

        PlatformInboundAttachment attachment =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/photo.jpg",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        PlatformInboundMessageEvent payload =
                new PlatformInboundMessageEvent(
                        message,
                        List.of(attachment)
                );

        KafkaEvent<PlatformInboundMessageEvent> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
                );

        kafkaTemplate
                .send(
                        KafkaTopicNames.inbound(),
                        channelAccountId.toString(),
                        event
                )
                .get(
                        10,
                        TimeUnit.SECONDS
                );

        verify(
                messageProcessingService,
                timeout(10_000)
        ).processInbound(
                eq(payload)
        );
    }
}