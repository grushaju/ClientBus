package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.service.MessageProcessingService;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TelegramOutboundMessageFlowIntegrationTest
        extends AbstractIntegrationTest {

    private static final String CONSUMER_GROUP =
            "clientbus-telegram-outbound-e2e-test-"
                    + UUID.randomUUID();

    private static final long TELEGRAM_CHAT_ID =
            123456789L;

    private static final long TELEGRAM_MESSAGE_ID =
            987654321L;

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @MockitoBean
    private TelegramClientManager telegramClientManager;

    @MockitoBean
    private TelegramClientContext telegramClientContext;

    @MockitoBean
    private TelegramClient telegramClient;

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
    void setUp() {

        TdApi.Message telegramMessage =
                new TdApi.Message(
                        TELEGRAM_MESSAGE_ID,
                        null,
                        null,
                        TELEGRAM_CHAT_ID,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        0,
                        null,
                        0,
                        0,
                        null,
                        0,
                        "",
                        0l,
                        0l,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0
                );

        when(
                telegramClientManager.require(
                        any(UUID.class)
                )
        ).thenReturn(
                telegramClientContext
        );

        when(
                telegramClientContext.telegramClient()
        ).thenReturn(
                telegramClient
        );

        when(
                telegramClient.send(any())
        ).thenReturn(
                new TdlibResponse<>(
                        telegramMessage,
                        null
                )
        );
    }


    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void processOutbound_fullTelegramAsyncFlow_sendsThroughRealTelegramConnectorAndMarksSent()
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

        authenticateEmployee(
                organization,
                workspace
        );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.TELEGRAM,
                                "Telegram outbound E2E"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-e2e",
                                "company_channel",
                                "+79990000000",
                                "Company Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                String.valueOf(
                                        TELEGRAM_CHAT_ID
                                )
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

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversation.getId(),
                        MessageType.TEXT,
                        "Hello from Telegram outbound E2E test",
                        null,
                        null
                );

        var result =
                messageProcessingService.processOutbound(
                        request,
                        List.of()
                );

        assertNotNull(result);
        assertNotNull(result.id());

        UUID messageId =
                result.id();

        var functionCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(TdApi.Function.class);

        verify(
                telegramClient,
                timeout(15_000)
                        .times(1)
        ).send(
                functionCaptor.capture()
        );

        TdApi.Function<?> function =
                functionCaptor.getValue();

        assertInstanceOf(
                TdApi.SendMessage.class,
                function
        );

        TdApi.SendMessage sendMessage =
                (TdApi.SendMessage) function;

        assertEquals(
                TELEGRAM_CHAT_ID,
                sendMessage.chatId
        );

        assertInstanceOf(
                TdApi.InputMessageText.class,
                sendMessage.inputMessageContent
        );

        TdApi.InputMessageText text =
                (TdApi.InputMessageText)
                        sendMessage.inputMessageContent;

        assertEquals(
                "Hello from Telegram outbound E2E test",
                text.text.text
        );

        verify(
                telegramClientManager,
                timeout(15_000)
                        .times(1)
        ).require(
                channelAccount.getId()
        );

        verify(
                telegramClientContext,
                timeout(15_000)
                        .times(1)
        ).telegramClient();

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
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                sentMessage.getExternalId()
        );

        assertNotNull(
                sentMessage.getSentAt()
        );
    }

    private void authenticateEmployee(
            OrganizationEntity organization,
            WorkspaceEntity workspace
    ) {

        UserEntity user =
                userRepository.saveAndFlush(
                        TestDataFactory.user(
                                "tg-e2e-"
                                        + UUID.randomUUID(),
                                "telegram-e2e-"
                                        + UUID.randomUUID()
                                        + "@example.com",
                                "$2a$10$test"
                        )
                );

        EmployeeEntity employee =
                employeeRepository.saveAndFlush(
                        new EmployeeEntity(
                                organization,
                                user,
                                "E2E",
                                "Employee",
                                "+79990009999"
                        )
                );

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(
                        employee,
                        workspace
                )
        );

        UserPrincipal principal =
                new UserPrincipal(user);

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        )
                );
    }

    private MessageEntity awaitMessageStatus(
            UUID messageId,
            MessageDeliveryStatus expectedStatus
    ) throws InterruptedException {

        long deadline =
                System.currentTimeMillis()
                        + 15_000;

        MessageEntity message;

        do {

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

        message =
                messageRepository
                        .findById(messageId)
                        .orElseThrow();

        assertEquals(
                expectedStatus,
                message.getDeliveryStatus()
        );

        return message;
    }
}