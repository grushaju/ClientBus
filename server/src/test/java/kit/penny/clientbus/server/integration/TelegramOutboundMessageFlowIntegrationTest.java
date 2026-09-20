package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramMessageSendListener;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.service.MessageProcessingService;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
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

    private long telegramChatId;

    private static final long TELEGRAM_MESSAGE_ID =
            987654321L;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private MessageProcessingService messageProcessingService;

    @Autowired
    private MessageService messageService;

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

    @Autowired
    private MessageAttachmentRepository messageAttachmentRepository;

    @Autowired
    private IAttachmentStorage attachmentStorage;

    @DynamicPropertySource
    static void kafkaProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.kafka.consumer.outbound-group-id",
                () -> CONSUMER_GROUP
        );
    }

    @BeforeEach
    void setUp() {

        telegramChatId  =
                100000000L
                        + Math.floorMod(
                        UUID.randomUUID().getLeastSignificantBits(),
                        900000000L
                );

        TdApi.Message telegramMessage =
                new TdApi.Message(
                        TELEGRAM_MESSAGE_ID,
                        null,
                        null,
                        telegramChatId,
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
                        0L,
                        0L,
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
                                        telegramChatId
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

        var container =
                kafkaListenerEndpointRegistry.getListenerContainer(
                        "kafkaOutboundMessageConsumer"
                );

        assertNotNull(container);
        assertTrue(container.isRunning());

        System.out.println(
                "OUTBOUND CONSUMER RUNNING = "
                        + container.isRunning()
        );

        System.out.println(
                "OUTBOUND CONSUMER GROUP = "
                        + container.getContainerProperties().getGroupId()
        );

        System.out.println(
                "OUTBOUND ASSIGNMENTS = "
                        + container.getAssignedPartitions()
        );

        System.out.println(
                "OUTBOUND ASSIGNMENTS BY CLIENT = "
                        + container.getAssignmentsByClientId()
        );


        var result =
                messageProcessingService.processOutbound(
                        request,
                        List.of()
                );
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                     var container2 =
                            kafkaListenerEndpointRegistry.getListenerContainer(
                                    "kafkaOutboundMessageConsumer"
                            );

                    assertNotNull(container2);
                    assertFalse(
                            container2.getAssignedPartitions().isEmpty()
                    );
                });

         container =
                kafkaListenerEndpointRegistry.getListenerContainer(
                        "kafkaOutboundMessageConsumer"
                );

        assertNotNull(container);
        assertTrue(container.isRunning());

        System.out.println(
                "OUTBOUND CONSUMER RUNNING = "
                        + container.isRunning()
        );

        System.out.println(
                "OUTBOUND CONSUMER GROUP = "
                        + container.getContainerProperties().getGroupId()
        );

        System.out.println(
                "OUTBOUND ASSIGNMENTS = "
                        + container.getAssignedPartitions()
        );

        System.out.println(
                "OUTBOUND ASSIGNMENTS BY CLIENT = "
                        + container.getAssignmentsByClientId()
        );

        assertNotNull(result);
        assertNotNull(result.id());

        UUID messageId =
                result.id();

        MessageEntity message =
                messageService.getMessageEntityForProcessing(messageId);

        System.out.println(
                "AFTER PROCESS OUTBOUND: "
                        + "processing=" + message.getProcessingStatus()
                        + ", delivery=" + message.getDeliveryStatus()
                        + ", externalId=" + message.getExternalId()
        );

        ArgumentCaptor<TdApi.Function> functionCaptor =
                ArgumentCaptor.forClass(
                        TdApi.Function.class
                );

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
                telegramChatId,
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

        /*
         * SendMessage() accepted the outbound request,
         * but this is NOT yet SENT.
         *
         * The returned Telegram message ID is temporarily
         * registered as externalId while delivery is PENDING.
         */
        MessageEntity pendingMessage =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                TELEGRAM_MESSAGE_ID
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                pendingMessage.getExternalId()
        );

        /*
         * Simulate the asynchronous Telegram confirmation.
         *
         * oldMessageId is the temporary/local Telegram message ID
         * returned by SendMessage().
         *
         * message.id is the final Telegram message ID.
         */
        TelegramMessageSendListener.Succeeded listener =
                new TelegramMessageSendListener.Succeeded(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        listener.handleNotification(
                new TdApi.UpdateMessageSendSucceeded(
                        createTelegramMessage(
                                TELEGRAM_MESSAGE_ID,
                                telegramChatId
                        ),
                        TELEGRAM_MESSAGE_ID
                )
        );

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
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

        assertNotNull(
                sentMessage.getProcessedAt()
        );
    }

    @Test
    void processOutbound_fullTelegramAsyncFlow_marksFailedAfterTelegramSendFailure()
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
                                "Telegram outbound failure E2E"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-failure-e2e",
                                "company_channel",
                                "+79990000001",
                                "Company Telegram Failure"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                String.valueOf(
                                        telegramChatId
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
                        "Hello from Telegram failure E2E test",
                        null,
                        null
                );

        var container =
                kafkaListenerEndpointRegistry.getListenerContainer(
                        "kafkaOutboundMessageConsumer"
                );

        assertNotNull(container);
        assertTrue(container.isRunning());

        System.out.println(
                "OUTBOUND CONSUMER RUNNING = "
                        + container.isRunning()
        );
        System.out.println(
                "OUTBOUND CONSUMER GROUP = "
                        + container.getContainerProperties().getGroupId()
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

        ArgumentCaptor<TdApi.Function> functionCaptor =
                ArgumentCaptor.forClass(
                        TdApi.Function.class
                );

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
                telegramChatId,
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
                "Hello from Telegram failure E2E test",
                text.text.text
        );

        /*
         * SendMessage() accepted the outbound request,
         * but this is NOT yet FAILED.
         *
         * The Telegram message ID is temporarily registered
         * as externalId while delivery is still PENDING.
         */
        MessageEntity pendingMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.PENDING
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        MessageEntity pendingMessageWithExternalId =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                TELEGRAM_MESSAGE_ID
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessageWithExternalId.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessageWithExternalId.getDeliveryStatus()
        );

        /*
         * Simulate asynchronous Telegram send failure.
         */
        TelegramMessageSendListener.Failed listener =
                new TelegramMessageSendListener.Failed(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        TdApi.Error telegramError =
                new TdApi.Error(
                        400,
                        "Telegram send failed"
                );

        listener.handleNotification(
                new TdApi.UpdateMessageSendFailed(
                        createTelegramMessage(
                                TELEGRAM_MESSAGE_ID,
                                telegramChatId
                        ),
                        TELEGRAM_MESSAGE_ID,
                        telegramError
                )
        );

        MessageEntity failedMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.FAILED
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                failedMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failedMessage.getDeliveryStatus()
        );

        /*
         * externalId is intentionally preserved after FAILED.
         *
         * It identifies the Telegram send attempt and is also
         * required to correlate the asynchronous failure update.
         */
        assertEquals(
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                failedMessage.getExternalId()
        );

        assertNotNull(
                failedMessage.getProcessedAt()
        );

        /*
         * A failed delivery must not receive sentAt.
         */
        assertNull(
                failedMessage.getSentAt()
        );
    }

    @Test
    void processOutbound_fullTelegramAsyncImageFlow_sendsThroughRealTelegramConnectorAndMarksSent()
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
                                "Telegram image outbound E2E"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-image-e2e",
                                "image_channel",
                                "+79990000001",
                                "Image Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                String.valueOf(
                                        telegramChatId
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
                        MessageType.IMAGE,
                        "Image from Telegram outbound E2E test",
                        null,
                        null
                );

        var attachment =
                TestDataFactory.attachmentContent();

        var container =
                kafkaListenerEndpointRegistry.getListenerContainer(
                        "kafkaOutboundMessageConsumer"
                );

        assertNotNull(container);
        assertTrue(container.isRunning());

        System.out.println(
                "OUTBOUND CONSUMER RUNNING = "
                        + container.isRunning()
        );
        System.out.println(
                "OUTBOUND CONSUMER GROUP = "
                        + container.getContainerProperties().getGroupId()
        );

        var result =
                messageProcessingService.processOutbound(
                        request,
                        List.of(attachment)
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
                telegramChatId,
                sendMessage.chatId
        );

        assertInstanceOf(
                TdApi.InputMessagePhoto.class,
                sendMessage.inputMessageContent
        );

        TdApi.InputMessagePhoto photo =
                (TdApi.InputMessagePhoto)
                        sendMessage.inputMessageContent;

        assertNotNull(
                photo.photo
        );

        assertInstanceOf(
                TdApi.InputFileLocal.class,
                photo.photo.photo
        );

        TdApi.InputFileLocal inputFile =
                (TdApi.InputFileLocal)
                        photo.photo.photo;

        assertNotNull(
                inputFile.path
        );

        assertEquals(
                "Image from Telegram outbound E2E test",
                photo.caption.text
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

        MessageEntity pendingMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.PENDING
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        MessageEntity pendingMessageWithExternalId =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                TELEGRAM_MESSAGE_ID
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessageWithExternalId.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessageWithExternalId.getDeliveryStatus()
        );

        assertNotNull(
                inputFile.path
        );

        java.nio.file.Path temporaryFile =
                java.nio.file.Path.of(
                        inputFile.path
                );

        assertTrue(
                java.nio.file.Files.exists(
                        temporaryFile
                ),
                "Telegram temporary image file must exist before send succeeds"
        );

        TelegramMessageSendListener.Succeeded listener =
                new TelegramMessageSendListener.Succeeded(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        listener.handleNotification(
                new TdApi.UpdateMessageSendSucceeded(
                        createTelegramMessage(
                                TELEGRAM_MESSAGE_ID,
                                telegramChatId
                        ),
                        TELEGRAM_MESSAGE_ID
                )
        );

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
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

        assertNotNull(
                sentMessage.getProcessedAt()
        );

        org.junit.jupiter.api.Assertions.assertFalse(
                await()
                        .atMost(
                                java.time.Duration.ofSeconds(5)
                        )
                        .until(
                                () -> java.nio.file.Files.exists(
                                        temporaryFile
                                ),
                                org.hamcrest.Matchers.is(false)
                        ),
                "Telegram temporary image file must be deleted after send succeeds"
        );
    }

    @Test
    void processOutbound_fullTelegramAsyncImageFlow_marksFailedAfterTelegramSendFailure()
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
                                "Telegram image failure E2E"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-image-failure-e2e",
                                "image_failure_channel",
                                "+79990000002",
                                "Image Failure Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                String.valueOf(
                                        telegramChatId
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
                        MessageType.IMAGE,
                        "Image failure from Telegram outbound E2E test",
                        null,
                        null
                );

        var attachment =
                TestDataFactory.attachmentContent();

        var result =
                messageProcessingService.processOutbound(
                        request,
                        List.of(attachment)
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
                telegramChatId,
                sendMessage.chatId
        );

        assertInstanceOf(
                TdApi.InputMessagePhoto.class,
                sendMessage.inputMessageContent
        );

        TdApi.InputMessagePhoto photo =
                (TdApi.InputMessagePhoto)
                        sendMessage.inputMessageContent;

        assertInstanceOf(
                TdApi.InputFileLocal.class,
                photo.photo.photo
        );

        TdApi.InputFileLocal inputFile =
                (TdApi.InputFileLocal)
                        photo.photo.photo;

        java.nio.file.Path temporaryFile =
                java.nio.file.Path.of(
                        inputFile.path
                );

        assertEquals(
                "Image failure from Telegram outbound E2E test",
                photo.caption.text
        );

        /*
         * SendMessage() accepted the outbound request,
         * but this is NOT yet SENT.
         *
         * The returned Telegram message ID is temporarily
         * registered as externalId while delivery is PENDING.
         */
        MessageEntity pendingMessage =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                TELEGRAM_MESSAGE_ID
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                pendingMessage.getExternalId()
        );

        assertNotNull(
                inputFile.path
        );

        assertTrue(
                java.nio.file.Files.exists(
                        temporaryFile
                ),
                "Telegram temporary image file must exist before send failure"
        );

        List<MessageAttachmentEntity> attachments =
                messageAttachmentRepository
                        .findAllByMessageId(messageId);

        assertEquals(
                1,
                attachments.size()
        );

        MessageAttachmentEntity messageAttachment =
                attachments.getFirst();

        assertEquals(
                MessageAttachmentType.IMAGE,
                messageAttachment.getType()
        );

        assertEquals(
                "test-image.jpg",
                messageAttachment.getFileName()
        );

        assertEquals(
                "image/jpeg",
                messageAttachment.getContentType()
        );

        assertNotNull(
                messageAttachment.getStorageKey()
        );

        assertEquals(
                1,
                messageAttachmentRepository
                        .countByMessageId(messageId)
        );

        String storageKey =
                messageAttachment.getStorageKey();

        assertTrue(
                attachmentStorage.exists(
                        storageKey
                ),
                "Persistent attachment must remain after Telegram send failure"
        );

        TelegramMessageSendListener.Failed listener =
                new TelegramMessageSendListener.Failed(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        TdApi.Error telegramError =
                new TdApi.Error(
                        400,
                        "Telegram image send failed"
                );

        listener.handleNotification(
                new TdApi.UpdateMessageSendFailed(
                        createTelegramMessage(
                                TELEGRAM_MESSAGE_ID,
                                telegramChatId
                        ),
                        TELEGRAM_MESSAGE_ID,
                        telegramError
                )
        );

        MessageEntity failedMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.FAILED
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                failedMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failedMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                failedMessage.getExternalId()
        );

        assertNotNull(
                failedMessage.getProcessedAt()
        );

        assertNull(
                failedMessage.getSentAt()
        );

        List<MessageAttachmentEntity> failedAttachments =
                messageAttachmentRepository
                        .findAllByMessageId(messageId);

        assertEquals(
                1,
                failedAttachments.size()
        );

        MessageAttachmentEntity failedAttachment =
                failedAttachments.getFirst();

        assertEquals(
                MessageAttachmentType.IMAGE,
                failedAttachment.getType()
        );

        assertEquals(
                "test-image.jpg",
                failedAttachment.getFileName()
        );

        assertEquals(
                "image/jpeg",
                failedAttachment.getContentType()
        );

        assertEquals(
                storageKey,
                failedAttachment.getStorageKey()
        );

        assertTrue(
                attachmentStorage.exists(
                        storageKey
                ),
                "Persistent attachment must remain after Telegram send failure"
        );

        await()
                .atMost(
                        java.time.Duration.ofSeconds(15)
                )
                .until(
                        () -> java.nio.file.Files.exists(
                                temporaryFile
                        ),
                        org.hamcrest.Matchers.is(false)
                );

        assertFalse(
                java.nio.file.Files.exists(
                        temporaryFile
                ),
                "Telegram temporary image file must be deleted after send failure"
        );
    }

    @Test
    void processOutbound_fullTelegramAsyncImageFlow_retriesFailedMessageWithNewTelegramMessageId()
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

        EmployeeEntity employee =
                authenticateEmployee(
                        organization,
                        workspace
                );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(
                                workspace,
                                ChannelType.TELEGRAM,
                                "Telegram image retry E2E"
                        )
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-image-retry-e2e",
                                "image_retry_channel",
                                "+79990000003",
                                "Image Retry Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                String.valueOf(
                                        telegramChatId
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

        conversation.setAssignedEmployee(employee);

        conversation =
                conversationRepository.saveAndFlush(
                        conversation
                );

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversation.getId(),
                        MessageType.IMAGE,
                        "Image retry from Telegram outbound E2E test",
                        null,
                        null
                );

        var attachment =
                TestDataFactory.attachmentContent();

        TdApi.Message firstTelegramMessage =
                createTelegramMessage(
                        TELEGRAM_MESSAGE_ID,
                        telegramChatId
                );

        long retryTelegramMessageId =
                TELEGRAM_MESSAGE_ID + 1;

        TdApi.Message retryTelegramMessage =
                createTelegramMessage(
                        retryTelegramMessageId,
                        telegramChatId
                );

        when(
                telegramClient.send(any())
        ).thenReturn(
                new TdlibResponse<>(
                        firstTelegramMessage,
                        null
                ),
                new TdlibResponse<>(
                        retryTelegramMessage,
                        null
                )
        );

        var result =
                messageProcessingService.processOutbound(
                        request,
                        List.of(attachment)
                );

        assertNotNull(result);
        assertNotNull(result.id());

        UUID messageId =
                result.id();

        MessageEntity pendingMessage =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                TELEGRAM_MESSAGE_ID
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        List<MessageAttachmentEntity> attachments =
                messageAttachmentRepository
                        .findAllByMessageId(messageId);

        assertEquals(
                1,
                attachments.size()
        );

        MessageAttachmentEntity originalAttachment =
                attachments.getFirst();

        String storageKey =
                originalAttachment.getStorageKey();

        assertNotNull(storageKey);

        assertTrue(
                attachmentStorage.exists(storageKey)
        );

        var firstFunctionCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(TdApi.Function.class);

        verify(
                telegramClient,
                timeout(15_000)
                        .times(1)
        ).send(
                firstFunctionCaptor.capture()
        );

        TdApi.SendMessage firstSend =
                (TdApi.SendMessage)
                        firstFunctionCaptor.getValue();

        assertInstanceOf(
                TdApi.InputMessagePhoto.class,
                firstSend.inputMessageContent
        );

        TdApi.InputMessagePhoto firstPhoto =
                (TdApi.InputMessagePhoto)
                        firstSend.inputMessageContent;

        assertInstanceOf(
                TdApi.InputFileLocal.class,
                firstPhoto.photo.photo
        );

        TdApi.InputFileLocal firstInputFile =
                (TdApi.InputFileLocal)
                        firstPhoto.photo.photo;

        java.nio.file.Path firstTemporaryFile =
                java.nio.file.Path.of(
                        firstInputFile.path
                );

        assertTrue(
                java.nio.file.Files.exists(
                        firstTemporaryFile
                )
        );

        TelegramMessageSendListener.Failed failedListener =
                new TelegramMessageSendListener.Failed(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        failedListener.handleNotification(
                new TdApi.UpdateMessageSendFailed(
                        firstTelegramMessage,
                        TELEGRAM_MESSAGE_ID,
                        new TdApi.Error(
                                400,
                                "Telegram image send failed"
                        )
                )
        );

        MessageEntity failedMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.FAILED
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                failedMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failedMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        TELEGRAM_MESSAGE_ID
                ),
                failedMessage.getExternalId()
        );

        assertTrue(
                attachmentStorage.exists(storageKey)
        );

        await().atMost(
                java.time.Duration.ofSeconds(5)
        ).untilAsserted(() ->
                org.junit.jupiter.api.Assertions.assertFalse(
                        java.nio.file.Files.exists(
                                firstTemporaryFile
                        )
                )
        );

        var retriedResult =
                messageProcessingService.retryOutbound(
                        messageId
                );

        assertNotNull(retriedResult);

        assertEquals(
                messageId,
                retriedResult.id()
        );

        MessageEntity retryPendingMessage =
                awaitMessageExternalId(
                        messageId,
                        String.valueOf(
                                retryTelegramMessageId
                        )
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                retryPendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                retryPendingMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        retryTelegramMessageId
                ),
                retryPendingMessage.getExternalId()
        );

        List<MessageAttachmentEntity> retryAttachments =
                messageAttachmentRepository
                        .findAllByMessageId(messageId);

        assertEquals(
                1,
                retryAttachments.size()
        );

        MessageAttachmentEntity retryAttachment =
                retryAttachments.getFirst();

        assertEquals(
                originalAttachment.getId(),
                retryAttachment.getId()
        );

        assertEquals(
                storageKey,
                retryAttachment.getStorageKey()
        );

        assertTrue(
                attachmentStorage.exists(storageKey)
        );

        var allFunctionCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(TdApi.Function.class);

        verify(
                telegramClient,
                timeout(15_000)
                        .times(2)
        ).send(
                allFunctionCaptor.capture()
        );

        TdApi.Function<?> retryFunction =
                allFunctionCaptor.getAllValues()
                        .get(1);

        assertInstanceOf(
                TdApi.SendMessage.class,
                retryFunction
        );

        TdApi.SendMessage retrySend =
                (TdApi.SendMessage)
                        retryFunction;

        assertEquals(
                telegramChatId,
                retrySend.chatId
        );

        assertInstanceOf(
                TdApi.InputMessagePhoto.class,
                retrySend.inputMessageContent
        );

        TdApi.InputMessagePhoto retryPhoto =
                (TdApi.InputMessagePhoto)
                        retrySend.inputMessageContent;

        assertEquals(
                "Image retry from Telegram outbound E2E test",
                retryPhoto.caption.text
        );

        assertInstanceOf(
                TdApi.InputFileLocal.class,
                retryPhoto.photo.photo
        );

        TdApi.InputFileLocal retryInputFile =
                (TdApi.InputFileLocal)
                        retryPhoto.photo.photo;

        java.nio.file.Path retryTemporaryFile =
                java.nio.file.Path.of(
                        retryInputFile.path
                );

        assertTrue(
                java.nio.file.Files.exists(
                        retryTemporaryFile
                )
        );

        assertNotEquals(
                firstTemporaryFile,
                retryTemporaryFile
        );

        TelegramMessageSendListener.Succeeded succeededListener =
                new TelegramMessageSendListener.Succeeded(
                        channelAccount.getId(),
                        messageRepository,
                        messageService
                );

        succeededListener.handleNotification(
                new TdApi.UpdateMessageSendSucceeded(
                        retryTelegramMessage,
                        retryTelegramMessageId
                )
        );

        MessageEntity sentMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.SENT
                );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                sentMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                sentMessage.getDeliveryStatus()
        );

        assertEquals(
                String.valueOf(
                        retryTelegramMessageId
                ),
                sentMessage.getExternalId()
        );

        assertNotNull(
                sentMessage.getSentAt()
        );

        assertNotNull(
                sentMessage.getProcessedAt()
        );

        List<MessageAttachmentEntity> finalAttachments =
                messageAttachmentRepository
                        .findAllByMessageId(messageId);

        assertEquals(
                1,
                finalAttachments.size()
        );

        assertEquals(
                originalAttachment.getId(),
                finalAttachments.getFirst().getId()
        );

        assertEquals(
                storageKey,
                finalAttachments.getFirst().getStorageKey()
        );

        assertTrue(
                attachmentStorage.exists(storageKey)
        );

        await().atMost(
                java.time.Duration.ofSeconds(5)
        ).untilAsserted(() ->
                org.junit.jupiter.api.Assertions.assertFalse(
                        java.nio.file.Files.exists(
                                retryTemporaryFile
                        ),
                        "Retry Telegram temporary image file must be deleted after send succeeds"
                )
        );
    }


    // HELPERS //

    private TdApi.Message createTelegramMessage(
            long messageId,
            long chatId
    ) {
        return new TdApi.Message(
                messageId,
                null,
                null,
                chatId,
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
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                0,
                0
        );
    }

    private EmployeeEntity authenticateEmployee(
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

        return employee;
    }

    private MessageEntity awaitMessageExternalId(
            UUID messageId,
            String expectedExternalId
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

            if (expectedExternalId.equals(
                    message.getExternalId()
            )) {

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
                expectedExternalId,
                message.getExternalId()
        );

        return message;
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