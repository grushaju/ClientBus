package kit.penny.clientbus.server.kafka.consumer;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.config.KafkaEventJsonDeserializer;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
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
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboundMessageFailureIntegrationTest
        extends AbstractIntegrationTest {

    private static final String TOPIC =
            KafkaTopicNames.outbound(ChannelType.TELEGRAM);

    private static final String DLQ_TOPIC =
            KafkaTopicNames.dlq(TOPIC);

    private static final String CONSUMER_GROUP =
            "clientbus-outbound-failure-test";

    private static final String DLQ_CONSUMER_GROUP =
            "clientbus-outbound-failure-dlq-test";

    private static final String EXTERNAL_MESSAGE_ID =
            "telegram-external-message-recovered";

    private static final String BOOTSTRAP_SERVERS =
            "localhost:9092";

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

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @MockitoBean
    private ChannelConnectorRegistry channelConnectorRegistry;

    @MockitoBean
    private IChannelConnector channelConnector;

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

        reset(
                channelConnectorRegistry,
                channelConnector
        );

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        createTopicsIfNeeded();
    }

    @Test
    void connectorFailsTwiceThenSucceeds_retriesAndMarksSent()
            throws Exception {

        QueuedOutboundMessage queuedMessage =
                createQueuedOutboundMessage();

        UUID messageId =
                queuedMessage.messageId();

        UUID channelAccountId =
                queuedMessage.channelAccountId();

        String clientExternalId =
                queuedMessage.clientExternalId();

        Map<TopicPartition, Long> dlqOffsetsBefore =
                getEndOffsets(DLQ_TOPIC);

        when(
                channelConnector.send(any())
        )
                .thenThrow(
                        new IllegalStateException(
                                "Temporary Telegram failure #1"
                        )
                )
                .thenThrow(
                        new IllegalStateException(
                                "Temporary Telegram failure #2"
                        )
                )
                .thenReturn(
                        new ConnectorSendResult(
                                EXTERNAL_MESSAGE_ID
                        )
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent(
                        messageId,
                        channelAccountId,
                        clientExternalId
                );

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

        verify(
                channelConnector,
                timeout(15_000)
                        .times(3)
        ).send(any());

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
                EXTERNAL_MESSAGE_ID,
                sentMessage.getExternalId()
        );

        assertNotNull(
                sentMessage.getSentAt()
        );

        assertTrue(
                awaitNewDlqRecords(
                        DLQ_TOPIC,
                        dlqOffsetsBefore,
                        2_000
                ).isEmpty()
        );
    }

    @Test
    void connectorAlwaysFails_retriesThenMarksFailedAndPublishesToDlq()
            throws Exception {

        QueuedOutboundMessage queuedMessage =
                createQueuedOutboundMessage();

        UUID messageId =
                queuedMessage.messageId();

        UUID channelAccountId =
                queuedMessage.channelAccountId();

        String clientExternalId =
                queuedMessage.clientExternalId();

        Map<TopicPartition, Long> dlqOffsetsBefore =
                getEndOffsets(DLQ_TOPIC);

        doThrow(
                new IllegalStateException(
                        "Permanent Telegram failure"
                )
        ).when(channelConnector)
                .send(any());

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent(
                        messageId,
                        channelAccountId,
                        clientExternalId
                );

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

        verify(
                channelConnector,
                timeout(15_000)
                        .times(4)
        ).send(any());

        MessageEntity failedMessage =
                awaitMessageStatus(
                        messageId,
                        MessageDeliveryStatus.FAILED
                );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                failedMessage.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                failedMessage.getDeliveryStatus()
        );

        assertEquals(
                null,
                failedMessage.getExternalId()
        );

        List<ConsumerRecord<String, Object>> dlqRecords =
                awaitNewDlqRecords(
                        DLQ_TOPIC,
                        dlqOffsetsBefore,
                        15_000
                );

        assertEquals(
                1,
                dlqRecords.size()
        );

        ConsumerRecord<String, Object> dlqRecord =
                dlqRecords.get(0);

        assertEquals(
                DLQ_TOPIC,
                dlqRecord.topic()
        );

        assertEquals(
                channelAccountId.toString(),
                dlqRecord.key()
        );

        assertTrue(
                dlqRecord.value()
                        instanceof KafkaEvent<?>
        );

        KafkaEvent<?> dlqEvent =
                (KafkaEvent<?>) dlqRecord.value();

        assertEquals(
                KafkaEventType.OUTBOUND_MESSAGE,
                dlqEvent.eventType()
        );

        assertTrue(
                dlqEvent.payload()
                        instanceof OutboundMessageKafkaCommand
        );

        OutboundMessageKafkaCommand dlqCommand =
                (OutboundMessageKafkaCommand)
                        dlqEvent.payload();

        assertEquals(
                messageId,
                dlqCommand.messageId()
        );
    }

    private KafkaEvent<OutboundMessageKafkaCommand>
    createOutboundEvent(
            UUID messageId,
            UUID channelAccountId,
            String clientExternalId
    ) {
        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        clientExternalId,
                        MessageType.TEXT,
                        "Outbound failure test message",
                        List.of()
                );

        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.OUTBOUND_MESSAGE,
                1,
                java.time.Instant.now(),
                UUID.randomUUID(),
                command
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
                                "Failure Test Telegram Channel"
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
                                "telegram-failure-client-"
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
                "Outbound failure test message"
        );

        message.setMetadata(null);

        message.setSentAt(null);

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

        MessageEntity message;

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

        entityManager.clear();

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

    private void createTopicsIfNeeded()
            throws Exception {

        try (
                AdminClient adminClient =
                        AdminClient.create(
                                Map.of(
                                        "bootstrap.servers",
                                        BOOTSTRAP_SERVERS
                                )
                        )
        ) {

            List<String> existingTopics =
                    new ArrayList<>(
                            adminClient
                                    .listTopics()
                                    .names()
                                    .get()
                    );

            List<NewTopic> topics =
                    new ArrayList<>();

            if (!existingTopics.contains(TOPIC)) {
                topics.add(
                        new NewTopic(
                                TOPIC,
                                3,
                                (short) 1
                        )
                );
            }

            if (!existingTopics.contains(DLQ_TOPIC)) {
                topics.add(
                        new NewTopic(
                                DLQ_TOPIC,
                                1,
                                (short) 1
                        )
                );
            }

            if (!topics.isEmpty()) {
                adminClient
                        .createTopics(topics)
                        .all()
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
        }
    }

    /**
     * Returns the current end offset of every partition.
     *
     * The returned offsets form a boundary:
     * records with an offset below this value existed
     * before the test action.
     */
    private Map<TopicPartition, Long> getEndOffsets(
            String topic
    ) {

        try (
                KafkaConsumer<String, Object> consumer =
                        createKafkaConsumer(
                                DLQ_CONSUMER_GROUP
                        )
        ) {

            List<TopicPartition> partitions =
                    consumer.partitionsFor(topic)
                            .stream()
                            .map(
                                    partitionInfo ->
                                            new TopicPartition(
                                                    topic,
                                                    partitionInfo.partition()
                                            )
                            )
                            .toList();

            consumer.assign(partitions);

            return consumer.endOffsets(partitions);
        }
    }

    /**
     * Reads only DLQ records written after the supplied
     * partition offsets.
     *
     * This deliberately does not use consumer group offsets
     * and does not use seekToBeginning().
     */
    private List<ConsumerRecord<String, Object>>
    awaitNewDlqRecords(
            String topic,
            Map<TopicPartition, Long> offsetsBefore,
            long timeoutMillis
    ) {

        long deadline =
                System.currentTimeMillis()
                        + timeoutMillis;

        try (
                KafkaConsumer<String, Object> consumer =
                        createKafkaConsumer(
                                DLQ_CONSUMER_GROUP
                        )
        ) {

            List<TopicPartition> partitions =
                    consumer.partitionsFor(topic)
                            .stream()
                            .map(
                                    partitionInfo ->
                                            new TopicPartition(
                                                    topic,
                                                    partitionInfo.partition()
                                            )
                            )
                            .toList();

            consumer.assign(partitions);

            for (TopicPartition partition : partitions) {

                Long offset =
                        offsetsBefore.get(partition);

                if (offset == null) {
                    throw new IllegalStateException(
                            "Missing offset for partition: "
                                    + partition
                    );
                }

                consumer.seek(
                        partition,
                        offset
                );
            }

            List<ConsumerRecord<String, Object>> records =
                    new ArrayList<>();

            while (
                    System.currentTimeMillis()
                            < deadline
            ) {

                var polled =
                        consumer.poll(
                                Duration.ofMillis(250)
                        );

                for (ConsumerRecord<String, Object> record :
                        polled) {

                    Long offsetBefore =
                            offsetsBefore.get(
                                    new TopicPartition(
                                            record.topic(),
                                            record.partition()
                                    )
                            );

                    if (offsetBefore != null
                            && record.offset() >= offsetBefore) {

                        records.add(record);
                    }
                }

                if (!records.isEmpty()) {
                    return records;
                }
            }

            return records;
        }
    }

    private KafkaConsumer<String, Object>
    createKafkaConsumer(
            String groupId
    ) {

        Properties properties =
                new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS
        );

        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId
        );

        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                KafkaEventJsonDeserializer.class
        );

        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );

        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        return new KafkaConsumer<>(
                properties
        );
    }

    private record QueuedOutboundMessage(
            UUID messageId,
            UUID channelAccountId,
            String clientExternalId
    ) {
    }
}