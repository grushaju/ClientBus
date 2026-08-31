package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
class KafkaInboundEventConsumerIntegrationTest
        extends AbstractIntegrationTest {

    private static final String TOPIC =
            "clientbus.inbound";

    private static final String CONSUMER_GROUP =
            "clientbus-inbound-test-"
                    + UUID.randomUUID();

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoSpyBean
    private IMessageProcessingService messageProcessingService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

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

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(
                                organization
                        )
                );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.TELEGRAM,
                                "Integration Test Telegram"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "integration-telegram-123",
                                "integration_test_channel",
                                "+79990000000",
                                "Integration Test Telegram"
                        )
                );

        UUID channelAccountId =
                channelAccount.getId();

        String externalId =
                "external-" + UUID.randomUUID();

        InboundMessageRequest message =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        "+79990000001",
                        "Test Client",
                        externalId,
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

        KafkaEvent<PlatformInboundMessageEvent> kafkaEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        payload
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

        verify(
                messageProcessingService,
                timeout(15_000)
        ).processInbound(
                argThat(
                        received ->
                                received != null
                                        && received.message() != null
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
                                        .clientUsername()
                                        .equals(
                                                "client"
                                        )
                                        && received.message()
                                        .clientPhone()
                                        .equals(
                                                "+79990000001"
                                        )
                                        && received.message()
                                        .clientDisplayName()
                                        .equals(
                                                "Test Client"
                                        )
                                        && received.message()
                                        .externalId()
                                        .equals(
                                                externalId
                                        )
                                        && received.message()
                                        .type()
                                        == MessageType.TEXT
                                        && received.message()
                                        .content()
                                        .equals(
                                                "Hello Kafka Consumer"
                                        )
                                        && received.attachments()
                                        .size()
                                        == 1
                                        && received.attachments()
                                        .getFirst()
                                        .type()
                                        == MessageAttachmentType.IMAGE
                                        && received.attachments()
                                        .getFirst()
                                        .storageKey()
                                        .equals(
                                                "storage/photo.jpg"
                                        )
                                        && received.attachments()
                                        .getFirst()
                                        .fileName()
                                        .equals(
                                                "photo.jpg"
                                        )
                                        && received.attachments()
                                        .getFirst()
                                        .contentType()
                                        .equals(
                                                "image/jpeg"
                                        )
                                        && received.attachments()
                                        .getFirst()
                                        .size()
                                        == 1024
                )
        );
    }
}