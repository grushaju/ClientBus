package kit.penny.clientbus.server.connector.telegram.client;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageSenderType;
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
import kit.penny.clientbus.server.service.MessageService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TelegramMessageReadListenerIntegrationTest
        extends AbstractIntegrationTest {

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

    @Autowired
    private MessageService messageService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void updateChatReadOutbox_marksTelegramMessagesReadUpToWatermark() {

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
                                "Telegram READ test"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-read-test-channel",
                                "telegram_test_channel",
                                "+79990000000",
                                "Telegram READ Test"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "123"
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

        MessageEntity message101 =
                createSentMessage(
                        conversation,
                        "101"
                );

        MessageEntity message102 =
                createSentMessage(
                        conversation,
                        "102"
                );

        MessageEntity message105 =
                createSentMessage(
                        conversation,
                        "105"
                );

        MessageEntity message106 =
                createSentMessage(
                        conversation,
                        "106"
                );

        MessageEntity alreadyRead =
                createSentMessage(
                        conversation,
                        "104"
                );

        messageService.markRead(
                alreadyRead.getId()
        );

        messageRepository.flush();

        TelegramMessageReadListener listener =
                new TelegramMessageReadListener(
                        channelAccount.getId(),
                        conversationRepository,
                        messageService
                );

        TdApi.UpdateChatReadOutbox update =
                new TdApi.UpdateChatReadOutbox(
                        123,
                        105
                );

        listener.handleNotification(update);

        entityManager.flush();
        entityManager.clear();

        MessageEntity reloaded101 =
                messageRepository
                        .findById(message101.getId())
                        .orElseThrow();

        MessageEntity reloaded102 =
                messageRepository
                        .findById(message102.getId())
                        .orElseThrow();

        MessageEntity reloaded105 =
                messageRepository
                        .findById(message105.getId())
                        .orElseThrow();

        MessageEntity reloaded106 =
                messageRepository
                        .findById(message106.getId())
                        .orElseThrow();

        MessageEntity reloadedAlreadyRead =
                messageRepository
                        .findById(alreadyRead.getId())
                        .orElseThrow();

        assertEquals(
                MessageDeliveryStatus.READ,
                reloaded101.getDeliveryStatus()
        );

        assertNotNull(
                reloaded101.getReadAt()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                reloaded102.getDeliveryStatus()
        );

        assertNotNull(
                reloaded102.getReadAt()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                reloaded105.getDeliveryStatus()
        );

        assertNotNull(
                reloaded105.getReadAt()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                reloaded106.getDeliveryStatus()
        );

        assertNull(
                reloaded106.getReadAt()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                reloadedAlreadyRead.getDeliveryStatus()
        );

        assertNotNull(
                reloadedAlreadyRead.getReadAt()
        );
    }

    @Test
    void updateChatReadOutbox_unknownChatDoesNotChangeMessages() {

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
                                "Telegram READ unknown chat"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-read-test-channel-2",
                                "telegram_test_channel_2",
                                "+79990000001",
                                "Telegram READ Test 2"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "123"
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
                createSentMessage(
                        conversation,
                        "101"
                );

        messageRepository.flush();

        TelegramMessageReadListener listener =
                new TelegramMessageReadListener(
                        channelAccount.getId(),
                        conversationRepository,
                        messageService
                );

        TdApi.UpdateChatReadOutbox update =
                new TdApi.UpdateChatReadOutbox(
                        999,
                        105
                );

        listener.handleNotification(update);

        entityManager.flush();
        entityManager.clear();

        MessageEntity reloaded =
                messageRepository
                        .findById(message.getId())
                        .orElseThrow();

        assertEquals(
                MessageDeliveryStatus.SENT,
                reloaded.getDeliveryStatus()
        );

        assertNull(
                reloaded.getReadAt()
        );
    }

    @Test
    void updateChatReadOutbox_doesNotMarkMessagesAboveWatermark() {

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
                                "Telegram READ watermark"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-read-test-channel-3",
                                "telegram_test_channel_3",
                                "+79990000002",
                                "Telegram READ Test 3"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "123"
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

        MessageEntity message106 =
                createSentMessage(
                        conversation,
                        "106"
                );

        MessageEntity message200 =
                createSentMessage(
                        conversation,
                        "200"
                );

        messageRepository.flush();

        TelegramMessageReadListener listener =
                new TelegramMessageReadListener(
                        channelAccount.getId(),
                        conversationRepository,
                        messageService
                );

        TdApi.UpdateChatReadOutbox update =
                new TdApi.UpdateChatReadOutbox(
                        123,
                        105
                );

        listener.handleNotification(update);

        entityManager.flush();
        entityManager.clear();

        MessageEntity reloaded106 =
                messageRepository
                        .findById(message106.getId())
                        .orElseThrow();

        MessageEntity reloaded200 =
                messageRepository
                        .findById(message200.getId())
                        .orElseThrow();

        assertEquals(
                MessageDeliveryStatus.SENT,
                reloaded106.getDeliveryStatus()
        );

        assertNull(
                reloaded106.getReadAt()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                reloaded200.getDeliveryStatus()
        );

        assertNull(
                reloaded200.getReadAt()
        );
    }

    private MessageEntity createSentMessage(
            ConversationEntity conversation,
            String externalId
    ) {
        MessageEntity message =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setExternalId(externalId);
        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        return messageRepository.saveAndFlush(message);
    }
}
