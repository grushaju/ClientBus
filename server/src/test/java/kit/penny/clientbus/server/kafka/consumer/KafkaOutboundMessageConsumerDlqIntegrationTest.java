package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.service.MessageService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboundMessageConsumerDlqIntegrationTest
        extends AbstractIntegrationTest {

    private static final String OUTBOUND_TOPIC =
            "clientbus.outbound.telegram";

    private static final String DLQ_TOPIC =
            "clientbus.outbound.telegram.dlq";

    private static final String DLQ_DLQ_TOPIC =
            "clientbus.outbound.telegram.dlq.dlq";

    private static final String CONSUMER_GROUP =
            "clientbus-outbound-dlq-test-"
                    + UUID.randomUUID();

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageService messageService;

    @MockitoBean
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

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

        createTopicIfNeeded(OUTBOUND_TOPIC);
        createTopicIfNeeded(DLQ_TOPIC);
    }

    @Test
    void consume_doesNotConsumeOutboundDlqTopic()
            throws Exception {

        UUID messageId =
                UUID.randomUUID();

        UUID channelAccountId =
                UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "telegram-user-dlq-test",
                        MessageType.TEXT,
                        "DLQ message",
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

        kafkaTemplate
                .send(
                        DLQ_TOPIC,
                        channelAccountId.toString(),
                        event
                )
                .get(
                        10,
                        TimeUnit.SECONDS
                );

        Thread.sleep(3_000);

        verify(
                channelConnector,
                never()
        ).send(
                any()
        );

        assertFalse(
                messageRepository
                        .findById(messageId)
                        .isPresent()
        );
    }

    private void createTopicIfNeeded(
            String topic
    ) throws Exception {

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
                    .contains(topic)) {

                adminClient
                        .createTopics(
                                List.of(
                                        new NewTopic(
                                                topic,
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