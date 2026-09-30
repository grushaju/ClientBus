package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
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
import kit.penny.clientbus.server.service.MessageProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageInboundFlowIntegrationTest
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

    @Test
    void processPlatformMessage_createsMessageForExistingChannelAccount() {

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

        UUID channelAccountId =
                channelAccount.getId();

        String externalMessageId =
                "message-" + UUID.randomUUID();

        PlatformMessageRequest request =
                new PlatformMessageRequest(
                        channelAccountId,
                        "client-external-id",
                        "client_username",
                        "+79990000003",
                        "Test Client",
                        "client-sender-external-id",
                        externalMessageId,
                        MessageType.TEXT,
                        "Hello from integration test",
                        "{\"source\":\"integration-test\"}",
                        Instant.parse(
                                "2026-08-30T10:00:00Z"
                        ),
                        List.of()
                );

        var result =
                messageProcessingService.processPlatformMessage(
                        request
                );

        assertNotNull(result);
        assertNotNull(result.id());

        assertEquals(
                externalMessageId,
                result.externalId()
        );

        assertEquals(
                MessageType.TEXT,
                result.type()
        );

        assertEquals(
                "Hello from integration test",
                result.content()
        );
    }

    @Test
    void processPlatformMessage_sameExternalMessageId_doesNotCreateDuplicate() {

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

        UUID channelAccountId =
                channelAccount.getId();

        String externalMessageId =
                "duplicate-test-" + UUID.randomUUID();

        PlatformMessageRequest request =
                new PlatformMessageRequest(
                        channelAccountId,
                        "client-external-id",
                        "client_username",
                        "+79990000003",
                        "Test Client",
                        "client-sender-external-id",
                        externalMessageId,
                        MessageType.TEXT,
                        "Duplicate test message",
                        "{\"source\":\"integration-test\"}",
                        Instant.parse(
                                "2026-08-30T10:00:00Z"
                        ),
                        List.of()
                );

        var first =
                messageProcessingService.processPlatformMessage(
                        request
                );

        var second =
                messageProcessingService.processPlatformMessage(
                        request
                );

        assertNotNull(first);
        assertNotNull(second);

        assertEquals(
                first.id(),
                second.id()
        );

        assertEquals(
                externalMessageId,
                first.externalId()
        );

        assertEquals(
                externalMessageId,
                second.externalId()
        );
    }

    @Test
    void processPlatformMessage_createsClientAccountConversationAndMessage() {

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

        UUID channelAccountId =
                channelAccount.getId();

        String clientExternalId =
                "telegram-user-" + UUID.randomUUID();

        String externalMessageId =
                "telegram-message-" + UUID.randomUUID();

        Instant sentAt =
                Instant.parse(
                        "2026-08-30T10:00:00Z"
                );

        PlatformMessageRequest request =
                new PlatformMessageRequest(
                        channelAccountId,
                        clientExternalId,
                        "ivan_ivanov",
                        "+79991234567",
                        "Ivan Ivanov",
                        "telegram-sender-" + UUID.randomUUID(),
                        externalMessageId,
                        MessageType.TEXT,
                        "Hello from Telegram",
                        null,
                        sentAt,
                        List.of()
                );

        messageProcessingService.processPlatformMessage(
                request
        );

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                ChannelType.TELEGRAM,
                                clientExternalId
                        )
                        .orElseThrow();

        assertEquals(
                clientExternalId,
                clientAccount.getExternalId()
        );

        assertEquals(
                "ivan_ivanov",
                clientAccount.getUsername()
        );

        assertEquals(
                "+79991234567",
                clientAccount.getPhone()
        );

        assertEquals(
                "Ivan Ivanov",
                clientAccount.getDisplayName()
        );

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccountId,
                                clientAccount.getId()
                        )
                        .orElseThrow();

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
                        .findByConversationIdAndExternalId(
                                conversation.getId(),
                                externalMessageId
                        )
                        .orElseThrow();

        assertEquals(
                externalMessageId,
                message.getExternalId()
        );

        assertEquals(
                MessageType.TEXT,
                message.getType()
        );

        assertEquals(
                MessageDirection.INBOUND,
                message.getDirection()
        );

        assertEquals(
                "Hello from Telegram",
                message.getContent()
        );

        assertEquals(
                sentAt,
                message.getSentAt()
        );

        assertEquals(
                conversation.getId(),
                message.getConversation().getId()
        );
    }
}