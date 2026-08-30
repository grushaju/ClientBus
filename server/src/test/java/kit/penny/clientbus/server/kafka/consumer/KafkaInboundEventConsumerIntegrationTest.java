package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class KafkaInboundEventConsumerIntegrationTest {

    private static final String TOPIC =
            "clientbus.inbound";

    private static final String CONSUMER_GROUP =
            "clientbus-inbound-test-"
                    + UUID.randomUUID();

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoSpyBean
    private KafkaInboundEventConsumer kafkaInboundEventConsumer;

    @MockitoSpyBean
    private IMessageProcessingService messageProcessingService;

    @DynamicPropertySource
    static void kafkaProperties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "spring.kafka.consumer.group-id",
                () -> CONSUMER_GROUP
        );
    }


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
                        "external-"
                                + UUID.randomUUID(),
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

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        message,
                        List.of(attachment)
                );

        KafkaEvent<PlatformInboundMessageEvent> kafkaEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        event
                );

        var sendResult =
                kafkaTemplate
                        .send(
                                TOPIC,
                                channelAccountId.toString(),
                                kafkaEvent
                        )
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );

        assertNotNull(sendResult);

        RecordMetadata metadata =
                sendResult.getRecordMetadata();

        assertEquals(
                TOPIC,
                metadata.topic()
        );

        assertEquals(
                channelAccountId.toString(),
                sendResult
                        .getProducerRecord()
                        .key()
        );

//        verify(
//                kafkaInboundEventConsumer,
//                timeout(10_000)
//        ).consume(
//                ArgumentMatchers.any(
//                        KafkaEvent.class
//                )
//        );

        verify(
                messageProcessingService,
                timeout(10_000)
        ).processInbound(
                argThat(
                        received ->
                                received != null
                                        && received.message()
                                        .channelAccountId()
                                        .equals(
                                                channelAccountId
                                        )
                                        && received.message()
                                        .clientExternalId()
                                        .equals(
                                                "client-123"
                                        )
                                        && received.message()
                                        .content()
                                        .equals(
                                                "Hello Kafka Consumer"
                                        )
                                        && received.attachments()
                                        .size()
                                        == 1
                )
        );
    }
}