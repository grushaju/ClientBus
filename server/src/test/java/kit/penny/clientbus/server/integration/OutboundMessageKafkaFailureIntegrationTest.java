package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.command.SendMessageCommand;
import kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OutboundMessageKafkaFailureIntegrationTest
        extends AbstractIntegrationTest {

    private static final String CONSUMER_GROUP =
            "clientbus-outbound-kafka-failure-test-"
                    + UUID.randomUUID();

    @Autowired
    private MessageProcessingService messageProcessingService;

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry
            kafkaListenerEndpointRegistry;

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

        ensureOutboundTopics();
        restartOutboundConsumerContainer();

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        doThrow(
                new ConnectorSendRejectedException(
                        "Telegram connector failure"
                )
        ).when(channelConnector)
                .handle((SendMessageCommand)any());
    }

    /*
     * Контейнер подписан на topicPattern: если на старте контекста топика ещё нет,
     * переподписка произойдёт только после долгого metadata-интервала. Пересоздаём
     * потребителя после того, как топик гарантированно существует.
     */
    private void restartOutboundConsumerContainer() {

        org.springframework.kafka.listener.MessageListenerContainer container =
                kafkaListenerEndpointRegistry.getListenerContainer(
                        "kafkaOutboundMessageConsumer"
                );

        if (container == null) {
            return;
        }

        if (container.isRunning()) {
            container.stop();
        }

        container.start();
    }

    /*
     * Класс не зависел от авто-создания топика брокером (одна партия по умолчанию):
     * готовый 3-партиционный топик совпадает с соседними outbound-классами.
     */
    private void ensureOutboundTopics() throws Exception {

        String topic =
                kit.penny.clientbus.server.kafka.routing.KafkaTopicNames
                        .outbound(ChannelType.TELEGRAM);

        String dlqTopic =
                kit.penny.clientbus.server.kafka.routing.KafkaTopicNames
                        .dlq(topic);

        try (
                org.apache.kafka.clients.admin.AdminClient adminClient =
                        org.apache.kafka.clients.admin.AdminClient.create(
                                java.util.Map.of(
                                        "bootstrap.servers",
                                        getKafkaBootstrapServers()
                                )
                        )
        ) {
            java.util.Set<String> existingTopics =
                    adminClient
                            .listTopics()
                            .names()
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);

            java.util.List<org.apache.kafka.clients.admin.NewTopic> topics =
                    new java.util.ArrayList<>();

            if (!existingTopics.contains(topic)) {
                topics.add(
                        new org.apache.kafka.clients.admin.NewTopic(
                                topic,
                                3,
                                (short) 1
                        )
                );
            }

            if (!existingTopics.contains(dlqTopic)) {
                topics.add(
                        new org.apache.kafka.clients.admin.NewTopic(
                                dlqTopic,
                                1,
                                (short) 1
                        )
                );
            }

            if (!topics.isEmpty()) {
                adminClient
                        .createTopics(topics)
                        .all()
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void processOutbound_connectorFailure_retriesAndMarksMessageFailed()
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
                                "Telegram Kafka failure"
                        )
                );

        channel.setStatus(ChannelConnectionStatus.CONNECTED);
        channel = channelRepository.saveAndFlush(channel);

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(
                                channel,
                                "telegram-company-failure",
                                "company_channel",
                                "+79990000001",
                                "Company Telegram"
                        )
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                ChannelType.TELEGRAM,
                                "telegram-client-failure-"
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
                        "Kafka failure test",
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
                timeout(20_000)
                        .times(4)
        ).handle((SendMessageCommand)any());

        MessageEntity failedMessage =
                awaitDeliveryStatus(
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
    }

    private void authenticateEmployee(
            OrganizationEntity organization,
            WorkspaceEntity workspace
    ) {

        UserEntity user =
                userRepository.saveAndFlush(
                        TestDataFactory.user(
                                "kf-fail-user-"
                                        + UUID.randomUUID(),
                                "kafka-failure-"
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
                                "Kafka",
                                "Failure",
                                "+79990009998"
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

    private MessageEntity awaitDeliveryStatus(
            UUID messageId,
            MessageDeliveryStatus expectedStatus
    ) throws InterruptedException {

        long deadline =
                System.currentTimeMillis()
                        + 25_000;

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