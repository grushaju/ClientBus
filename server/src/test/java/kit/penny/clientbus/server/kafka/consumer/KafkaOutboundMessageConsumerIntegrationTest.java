package kit.penny.clientbus.server.kafka.consumer;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
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
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.service.MessageService;
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

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

    @MockitoBean
    private IAttachmentStorage attachmentStorage;

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
    void consume_sendsOutboundMessageThroughConnectorAndMarksItSent()
            throws Exception {

        QueuedOutboundMessage queuedMessage =
                createQueuedOutboundMessage();

        UUID messageId =
                queuedMessage.messageId();

        UUID channelAccountId =
                queuedMessage.channelAccountId();

        String clientExternalId =
                queuedMessage.clientExternalId();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        clientExternalId,
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
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

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                sentMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                sentMessage.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-123",
                sentMessage.getExternalId()
        );
    }

    @Test
    void consume_loadsOutboundAttachmentsFromStorage()
            throws Exception {

        QueuedOutboundMessage queuedMessage =
                createQueuedOutboundMessage();

        UUID messageId =
                queuedMessage.messageId();

        UUID channelAccountId =
                queuedMessage.channelAccountId();

        String clientExternalId =
                queuedMessage.clientExternalId();

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
                        clientExternalId,
                        MessageType.TEXT,
                        "Message with attachment",
                        List.of(attachment)
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.OUTBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
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

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                sentMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                sentMessage.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-123",
                sentMessage.getExternalId()
        );
    }

    private QueuedOutboundMessage createQueuedOutboundMessage() {

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
                                "Test Telegram Channel"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "telegram-client-"
                                        + UUID.randomUUID()
                        )
                );

        ConversationEntity conversation =
                conversationRepository.saveAndFlush(
                        TestDataFactory.conversation(
                                workspace,
                                channelAccount,
                                clientAccount
                        )
                );

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setExternalId(null);

        message.setContent(
                "Outbound test message"
        );

        message.setMetadata(null);

        message.setSentAt(
                Instant.now()
        );

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message =
                messageRepository.saveAndFlush(
                        message
                );

        UUID messageId =
                message.getId();

        UUID channelAccountId =
                channelAccount.getId();

        String clientExternalId =
                clientAccount.getExternalId();

        messageService.startProcessing(
                messageId
        );

        messageService.markProcessed(
                messageId
        );

        messageService.markQueued(
                messageId
        );

        entityManager.clear();

        return new QueuedOutboundMessage(
                messageId,
                channelAccountId,
                clientExternalId
        );
    }

    private MessageEntity awaitMessageStatus(
            UUID messageId,
            MessageDeliveryStatus expectedStatus
    ) throws InterruptedException {

        long deadline =
                System.currentTimeMillis()
                        + 15_000;

        MessageEntity message = null;

        do {

            entityManager.clear();

            message =
                    messageRepository
                            .findById(messageId)
                            .orElseThrow();

            if (message.getDeliveryStatus()
                    == expectedStatus) {

                return message;
            }

            Thread.sleep(100);

        } while (
                System.currentTimeMillis()
                        < deadline
        );

        assertNotNull(message);

        assertEquals(
                expectedStatus,
                message.getDeliveryStatus()
        );

        return message;
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

    private record QueuedOutboundMessage(
            UUID messageId,
            UUID channelAccountId,
            String clientExternalId
    ) {
    }
}