package kit.penny.clientbus.server.kafka.producer;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
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
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboundMessagePublisherIntegrationTest
        extends AbstractIntegrationTest {

    private static final long ASYNC_TIMEOUT_MILLIS = 15_000;

    @Autowired
    private KafkaOutboundMessagePublisher publisher;

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

    @BeforeEach
    void setUp() {

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                channelConnector.send(
                        any()
                )
        ).thenReturn(
                new ConnectorSendResult(
                        "telegram-external-message-123"
                )
        );
    }

    @Test
    void publish_sendsOutboundMessageThroughKafkaAndMarksItSent() {

        QueuedOutboundMessage queuedMessage =
                createQueuedOutboundMessage();

        UUID messageId =
                queuedMessage.messageId();

        UUID channelAccountId =
                queuedMessage.channelAccountId();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        queuedMessage.clientExternalId(),
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                );

        publisher.publish(
                ChannelType.TELEGRAM,
                command
        );

        verify(
                channelConnector,
                timeout(ASYNC_TIMEOUT_MILLIS)
                        .times(1)
        ).send(
                any()
        );

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertThat(sentMessage.getProcessingStatus())
                .isEqualTo(
                        MessageProcessingStatus.QUEUED
                );

        assertThat(sentMessage.getDeliveryStatus())
                .isEqualTo(
                        MessageDeliveryStatus.SENT
                );

        assertThat(sentMessage.getExternalId())
                .isEqualTo(
                        "telegram-external-message-123"
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
    ) {

        long deadline =
                System.currentTimeMillis()
                        + ASYNC_TIMEOUT_MILLIS;

        while (
                System.currentTimeMillis()
                        < deadline
        ) {

            entityManager.clear();

            MessageEntity message =
                    messageRepository
                            .findById(messageId)
                            .orElseThrow();

            if (
                    message.getDeliveryStatus()
                            == expectedStatus
            ) {
                return message;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new AssertionError(
                        "Interrupted while waiting for message status",
                        e
                );
            }
        }

        entityManager.clear();

        MessageEntity message =
                messageRepository
                        .findById(messageId)
                        .orElseThrow();

        assertThat(message.getDeliveryStatus())
                .as(
                        "Message delivery status after asynchronous processing"
                )
                .isEqualTo(expectedStatus);

        return message;
    }

    private record QueuedOutboundMessage(
            UUID messageId,
            UUID channelAccountId,
            String clientExternalId
    ) {
    }
}