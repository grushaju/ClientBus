package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageProcessingServiceIntegrationTest
        extends AbstractIntegrationTest {

    @Autowired
    private MessageProcessingService messageProcessingService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MessageService messageService;

    @Test
    void processInbound_createsClientAccountConversationAndMessage() {

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
                                "Telegram inbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-001",
                                "company_channel",
                                "+79990000000",
                                "Company Telegram"
                        )
                );

        UUID channelAccountId =
                channelAccount.getId();

        String clientExternalId =
                "telegram-client-" + UUID.randomUUID();

        String externalMessageId =
                "telegram-message-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        clientExternalId,
                        "client_username",
                        "+79991112233",
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Hello from Telegram",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-30T14:00:00Z"
                        )
                );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(result);
        assertNotNull(result.id());

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        assertNotNull(clientAccount.getId());

        assertEquals(
                ChannelType.TELEGRAM,
                clientAccount.getChannelType()
        );

        assertEquals(
                clientExternalId,
                clientAccount.getExternalId()
        );

        assertEquals(
                "client_username",
                clientAccount.getUsername()
        );

        assertEquals(
                "+79991112233",
                clientAccount.getPhone()
        );

        assertEquals(
                "Test Client",
                clientAccount.getDisplayName()
        );

        assertNull(
                clientAccount.getClient()
        );

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccountId,
                                clientAccount.getId()
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        assertEquals(
                workspace.getId(),
                conversation.getWorkspace().getId()
        );

        assertEquals(
                channelAccountId,
                conversation.getChannelAccount().getId()
        );

        assertEquals(
                clientAccount.getId(),
                conversation.getClientAccount().getId()
        );

        MessageEntity message =
                messageRepository
                        .findById(result.id())
                        .orElseThrow();

        assertEquals(
                conversation.getId(),
                message.getConversation().getId()
        );

        assertEquals(
                MessageType.TEXT,
                message.getType()
        );

        assertEquals(
                "Hello from Telegram",
                message.getContent()
        );

        assertEquals(
                externalMessageId,
                message.getExternalId()
        );
    }

    @Test
    void processInbound_existingClientAccount_reusesIt() {

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
                                "Telegram inbound"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-002",
                                "company_channel",
                                "+79990000001",
                                "Company Telegram"
                        )
                );

        String clientExternalId =
                "telegram-existing-client-" + UUID.randomUUID();

        ClientAccountEntity existingClientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                );

        UUID existingClientAccountId =
                existingClientAccount.getId();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccount.getId(),
                        clientExternalId,
                        "new_username",
                        "+79991112244",
                        "New Display Name",
                        "telegram-message-" + UUID.randomUUID(),
                        MessageType.TEXT,
                        "Second message",
                        null,
                        Instant.parse(
                                "2026-08-30T14:01:00Z"
                        )
                );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(result);

        ClientAccountEntity loaded =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        assertEquals(
                existingClientAccountId,
                loaded.getId()
        );

        assertEquals(
                clientExternalId,
                loaded.getExternalId()
        );

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                existingClientAccountId
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        assertEquals(
                workspace.getId(),
                conversation.getWorkspace().getId()
        );
    }

    @Test
    void processInbound_existingMessage_isIdempotent() {

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
                                "Telegram idempotency"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-003",
                                "company_channel",
                                "+79990000002",
                                "Company Telegram"
                        )
                );

        String clientExternalId =
                "telegram-idempotent-client-" + UUID.randomUUID();

        String externalMessageId =
                "telegram-idempotent-message-" + UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccount.getId(),
                        clientExternalId,
                        "client_username",
                        null,
                        "Test Client",
                        externalMessageId,
                        MessageType.TEXT,
                        "Idempotent message",
                        null,
                        Instant.parse(
                                "2026-08-30T14:02:00Z"
                        )
                );

        MessageDto first =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        MessageDto second =
                messageProcessingService.processInbound(
                        request,
                        List.of()
                );

        assertNotNull(first);
        assertNotNull(second);

        assertEquals(
                first.id(),
                second.id()
        );

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                clientAccount.getId()
                        )
                        .orElseThrow();

        assertNotNull(conversation.getId());

        MessageEntity message =
                messageRepository
                        .findById(first.id())
                        .orElseThrow();

        assertEquals(
                conversation.getId(),
                message.getConversation().getId()
        );

        assertEquals(
                externalMessageId,
                message.getExternalId()
        );
    }

    @Test
    void processInbound_missingChannelAccount_throwsException() {

        UUID unknownChannelAccountId =
                UUID.randomUUID();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        unknownChannelAccountId,
                        "telegram-client-" + UUID.randomUUID(),
                        "client_username",
                        null,
                        "Test Client",
                        "telegram-message-" + UUID.randomUUID(),
                        MessageType.TEXT,
                        "Message for unknown channel",
                        null,
                        Instant.now()
                );

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () ->
                                messageProcessingService.processInbound(
                                        request,
                                        List.of()
                                )
                );

        assertEquals(
                "ChannelAccount not found: "
                        + unknownChannelAccountId,
                exception.getMessage()
        );

        assertEquals(
                0,
                clientAccountRepository.count()
        );

        assertEquals(
                0,
                conversationRepository.count()
        );

        assertEquals(
                0,
                messageRepository.count()
        );
    }

    @Test
    void outboundMessage_fullStateMachine_persistsAllTransitions() {

        MessageEntity message =
                createOutboundMessage();

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                reload(message.getId()).getDeliveryStatus()
        );

        // PENDING -> SENT
        messageService.markSent(
                message.getId(),
                "telegram-external-message-001"
        );

        MessageEntity sent =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.SENT,
                sent.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-001",
                sent.getExternalId()
        );

        // SENT -> DELIVERED
        messageService.markDelivered(
                message.getId()
        );

        MessageEntity delivered =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                delivered.getDeliveryStatus()
        );

        assertNotNull(
                delivered.getDeliveredAt()
        );

        // DELIVERED -> READ
        messageService.markRead(
                message.getId()
        );

        MessageEntity read =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                read.getDeliveryStatus()
        );

        assertNotNull(
                read.getReadAt()
        );

        assertNotNull(
                read.getDeliveredAt()
        );
    }

    @Test
    void outboundMessage_sentToFailed_persistsFailedState() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-002"
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                reload(message.getId()).getDeliveryStatus()
        );

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity failed =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failed.getDeliveryStatus()
        );

        assertEquals(
                "telegram-external-message-002",
                failed.getExternalId()
        );
    }

    @Test
    void outboundMessage_duplicateDelivered_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-003"
        );

        messageService.markDelivered(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        Instant firstDeliveredAt =
                first.getDeliveredAt();

        assertNotNull(firstDeliveredAt);

        messageService.markDelivered(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                second.getDeliveryStatus()
        );

        assertEquals(
                firstDeliveredAt,
                second.getDeliveredAt()
        );
    }

    @Test
    void outboundMessage_duplicateRead_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markSent(
                message.getId(),
                "telegram-external-message-004"
        );

        messageService.markDelivered(
                message.getId()
        );

        messageService.markRead(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        Instant firstReadAt =
                first.getReadAt();

        assertNotNull(firstReadAt);

        messageService.markRead(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                second.getDeliveryStatus()
        );

        assertEquals(
                firstReadAt,
                second.getReadAt()
        );
    }

    @Test
    void outboundMessage_duplicateFailed_isIdempotent() {

        MessageEntity message =
                createOutboundMessage();

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity first =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                first.getDeliveryStatus()
        );

        messageService.markDeliveryFailed(
                message.getId()
        );

        MessageEntity second =
                reload(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                second.getDeliveryStatus()
        );
    }

    private MessageEntity createOutboundMessage() {

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
                                "Telegram state machine"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-state-machine-channel-"
                                        + UUID.randomUUID(),
                                "state_machine_channel",
                                "+79990000003",
                                "State Machine Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "telegram-state-machine-client-"
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

        message.setContent(
                "State machine integration test"
        );

        message.setSentAt(
                Instant.parse(
                        "2026-08-30T15:00:00Z"
                )
        );

        /*
         * markSent() требует PROCESSED.
         *
         * В этом тесте мы тестируем именно
         * delivery state machine, поэтому
         * processing lifecycle не является
         * предметом теста.
         */
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        return messageRepository.saveAndFlush(
                message
        );
    }

    private MessageEntity reload(UUID messageId) {

        entityManager.flush();
        entityManager.clear();

        return messageRepository
                .findById(messageId)
                .orElseThrow();
    }
}
