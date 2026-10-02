package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
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
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OutboundMessageAsyncFlowIntegrationTest
        extends AbstractIntegrationTest {

    private static final String TOPIC =
            KafkaTopicNames.outbound(ChannelType.TELEGRAM);

    private static final String CONSUMER_GROUP =
            "clientbus-outbound-e2e-test-"
                    + UUID.randomUUID();

    private static final String EXTERNAL_MESSAGE_ID =
            "telegram-external-message-e2e";

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
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

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
    void setUp() throws Exception {

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                channelConnector.handle((SendMessageCommand)any())
        ).thenReturn(
                new ConnectorSendResult(
                        EXTERNAL_MESSAGE_ID
                )
        );

        createTopicsIfNeeded();
        startOutboundConsumer();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void processOutbound_fullAsyncFlow_sendsThroughKafkaAndRegistersPendingDelivery()
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
                                "telegram-client-e2e-"
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

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversation.getId(),
                        MessageType.TEXT,
                        "Hello from outbound E2E test",
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

        verify(
                channelConnector,
                timeout(15_000)
                        .times(1)
        ).handle((SendMessageCommand)any());

        MessageEntity pendingMessage =
                awaitPendingMessageWithExternalId(
                        messageId
                );

        assertEquals(
                MessageProcessingStatus.PROCESSING,
                pendingMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                pendingMessage.getDeliveryStatus()
        );

        assertEquals(
                EXTERNAL_MESSAGE_ID,
                pendingMessage.getExternalId()
        );

        assertNotNull(
                pendingMessage.getCreatedAt()
        );
    }

    private void authenticateEmployee(
            OrganizationEntity organization,
            WorkspaceEntity workspace
    ) {

        UserEntity user =
                userRepository.saveAndFlush(
                        TestDataFactory.user(
                                "e2e-user-"
                                        + UUID.randomUUID(),
                                "e2e-"
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

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        )
                );
    }

    private MessageEntity awaitPendingMessageWithExternalId(
            UUID messageId
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

            if (message.getProcessingStatus()
                    == MessageProcessingStatus.QUEUED
                    && message.getDeliveryStatus()
                    == MessageDeliveryStatus.PENDING
                    && message.getExternalId() != null
                    && !message.getExternalId().isBlank()) {

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
                MessageProcessingStatus.PROCESSING,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                message.getDeliveryStatus()
        );

        assertNotNull(
                message.getExternalId()
        );

        return message;
    }

    private  void createTopicsIfNeeded() throws Exception {
        try (
                AdminClient adminClient =
                        AdminClient.create(
                                Map.of(
                                        "bootstrap.servers",
                                        getKafkaBootstrapServers()
                                )
                        )
        ) {
            Set<String> existingTopics =
                    adminClient
                            .listTopics()
                            .names()
                            .get();

            if (!existingTopics.contains(TOPIC)) {
                adminClient
                        .createTopics(
                                List.of(
                                        new NewTopic(
                                                TOPIC,
                                                3,
                                                (short) 1
                                        )
                                )
                        )
                        .all()
                        .get(10, TimeUnit.SECONDS);
            }
        }
    }

    private void startOutboundConsumer() {
        MessageListenerContainer container =
                kafkaListenerEndpointRegistry
                        .getListenerContainer(
                                "kafkaOutboundMessageConsumer"
                        );

        assertNotNull(container);

        if (container.isRunning()) {
            container.stop();
        }

        await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> !container.isRunning());

        container.start();

        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertFalse(
                                container.getAssignedPartitions().isEmpty()
                        )
                );
    }
}
