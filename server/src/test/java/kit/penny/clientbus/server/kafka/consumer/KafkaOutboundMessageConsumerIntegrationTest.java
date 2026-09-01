package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformOutboundAttachment;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.producer.IPlatformEventPublisher;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboundMessageConsumerIntegrationTest
        extends AbstractIntegrationTest {

    private static final String TOPIC =
            "clientbus.outbound.telegram";

    private static final String CONSUMER_GROUP =
            "clientbus-outbound-test-"
                    + UUID.randomUUID();

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

    @MockitoBean
    private IAttachmentStorage attachmentStorage;

    @MockitoBean
    private IPlatformEventPublisher platformEventPublisher;

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
                "clientbus.kafka.consumer.outbound-group-id",
                () -> CONSUMER_GROUP
        );
    }

    @BeforeEach
    void setUp() throws Exception {

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                channelConnector.send(
                        any(ChannelSendRequest.class)
                )
        ).thenReturn(
                new ConnectorSendResult(
                        "telegram-external-message-123"
                )
        );

        createTopicIfNeeded();
    }

    @Test
    void consume_sendsOutboundMessageThroughConnector()
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
                                workspace
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        UUID messageId =
                UUID.randomUUID();

        UUID channelAccountId =
                channelAccount.getId();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "telegram-user-123",
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                );

        UUID correlationId =
                UUID.randomUUID();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        correlationId,
                        command
                );

        var sendResult =
                kafkaTemplate
                        .send(
                                TOPIC,
                                channelAccountId.toString(),
                                event
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
                channelConnector,
                timeout(15_000)
                        .times(1)
        ).send(
                any(ChannelSendRequest.class)
        );

        verify(
                platformEventPublisher,
                timeout(15_000)
                        .times(1)
        ).publish(
                any(),
                eq(correlationId)
        );
    }

    @Test
    void consume_loadsOutboundAttachmentsFromStorage()
            throws Exception {

        UUID messageId =
                UUID.randomUUID();

        UUID channelAccountId =
                UUID.randomUUID();

        PlatformOutboundAttachment attachment =
                new PlatformOutboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/photo.jpg",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "telegram-user-456",
                        MessageType.TEXT,
                        "Message with attachment",
                        List.of(attachment)
                );

        UUID correlationId =
                UUID.randomUUID();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        correlationId,
                        command
                );

        when(
                attachmentStorage.load(
                        "storage/photo.jpg"
                )
        ).thenReturn(
                new ByteArrayInputStream(
                        new byte[]{10, 20, 30}
                )
        );

        kafkaTemplate
                .send(
                        TOPIC,
                        channelAccountId.toString(),
                        event
                )
                .get(
                        10,
                        TimeUnit.SECONDS
                );

        verify(
                attachmentStorage,
                timeout(15_000)
                        .times(1)
        ).load(
                "storage/photo.jpg"
        );

        verify(
                channelConnector,
                timeout(15_000)
                        .times(1)
        ).send(
                any(ChannelSendRequest.class)
        );
    }

    private void createTopicIfNeeded()
            throws Exception {

        try (
                AdminClient adminClient =
                        AdminClient.create(
                                Map.of(
                                        "bootstrap.servers",
                                        "localhost:9092"
                                )
                        )
        ) {

            if (!adminClient
                    .listTopics()
                    .names()
                    .get()
                    .contains(TOPIC)) {

                adminClient
                        .createTopics(
                                List.of(
                                        new NewTopic(
                                                TOPIC,
                                                1,
                                                (short) 1
                                        )
                                )
                        )
                        .all()
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
        }
    }
}