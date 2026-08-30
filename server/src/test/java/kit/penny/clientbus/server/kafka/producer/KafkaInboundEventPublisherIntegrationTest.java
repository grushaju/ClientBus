package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class KafkaInboundEventPublisherIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaInboundEventPublisher publisher;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    private OrganizationEntity organization;
    private WorkspaceEntity workspace;
    private ChannelEntity channel;
    private ChannelAccountEntity channelAccount;




    @BeforeEach
    void setUp() {

        organization =
                organizationRepository.save(
                        TestDataFactory.organization()
                );

        workspace =
                workspaceRepository.save(
                        TestDataFactory.workspace(organization)
                );

        channel =
                channelRepository.save(
                        TestDataFactory.channel(
                                workspace
                        )
                );
        channelAccount =
                channelAccountRepository.save(
                        TestDataFactory.channelAccount(
                                channel
                        )
                );
    }


    @Test
    void publish_serializesAndDeliversInboundEvent() {

        UUID channelAccountId =
                channelAccount.getId();

        InboundMessageRequest message =
                new InboundMessageRequest(
                        channelAccountId,
                        channelAccount.getExternalId(),
                        channelAccount.getUsername(),
                        channelAccount.getPhone(),
                        channelAccount.getDisplayName(),
                        channelAccount.getExternalId() + channelAccountId,
                        MessageType.TEXT,
                        "Hello Kafka",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-28T10:00:00Z"
                        )
                );

        PlatformInboundAttachment attachment =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/photo.jpg",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        message,
                        List.of(attachment)
                );

        String topic =
                KafkaTopicNames.inbound();

        Map<String, Object> consumerProperties =
                new HashMap<>();

        consumerProperties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "localhost:9092"
        );

        consumerProperties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "clientbus-test-"
                        + UUID.randomUUID()
        );

        consumerProperties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "latest"
        );

        consumerProperties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        consumerProperties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        consumerProperties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                JacksonJsonDeserializer.class
        );

        consumerProperties.put(
                JacksonJsonDeserializer.TRUSTED_PACKAGES,
                "kit.penny.clientbus.*"
        );

        consumerProperties.put(
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                KafkaEvent.class.getName()
        );

        try (
                Consumer<String, Object> consumer =
                        new KafkaConsumer<>(
                                consumerProperties
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

            Map<TopicPartition, Long> endOffsets =
                    consumer.endOffsets(partitions);

            endOffsets.forEach(
                    consumer::seek
            );

            publisher.publish(
                    event
            );

            ConsumerRecord<String, Object> record =
                    awaitRecord(
                            consumer
                    );

            assertEquals(
                    channelAccountId.toString(),
                    record.key()
            );

            assertNotNull(
                    record.value()
            );

            assertInstanceOf(
                    KafkaEvent.class,
                    record.value()
            );

            KafkaEvent<?> kafkaEvent =
                    (KafkaEvent<?>) record.value();

            assertEquals(
                    KafkaEventType.INBOUND_MESSAGE,
                    kafkaEvent.eventType()
            );

            assertEquals(
                    1,
                    kafkaEvent.schemaVersion()
            );

            assertNotNull(
                    kafkaEvent.eventId()
            );

            assertNotNull(
                    kafkaEvent.occurredAt()
            );

            assertNotNull(
                    kafkaEvent.correlationId()
            );

            assertNotNull(
                    kafkaEvent.payload()
            );
        }
    }

    private ConsumerRecord<String, Object> awaitRecord(
            Consumer<String, Object> consumer
    ) {

        long deadline =
                System.currentTimeMillis()
                        + 10_000;

        while (
                System.currentTimeMillis()
                        < deadline
        ) {

            ConsumerRecords<String, Object> records =
                    consumer.poll(
                            Duration.ofMillis(500)
                    );

            if (!records.isEmpty()) {

                return records.iterator().next();
            }
        }

        fail(
                "Kafka record was not received within timeout"
        );

        return null;
    }
}