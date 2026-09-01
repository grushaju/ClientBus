package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.config.KafkaEventJsonDeserializer;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
class KafkaPlatformEventPublisherIntegrationTest
        extends AbstractIntegrationTest {

    private static final String KAFKA_BOOTSTRAP_SERVERS =
            "localhost:9092";

    private static final String CONSUMER_GROUP_ID =
            "clientbus.platform-events.integration-test";

    private static final Duration POLL_TIMEOUT =
            Duration.ofMillis(500);

    private static final Duration MAX_WAIT =
            Duration.ofSeconds(10);

    @Autowired
    private KafkaPlatformEventPublisher publisher;

    @Test
    void publish_sendsPlatformMessageEventToKafka() {
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Instant occurredAt =
                Instant.parse("2026-01-01T12:00:00Z");

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "external-123",
                        PlatformMessageEventType.DELIVERED,
                        occurredAt,
                        "metadata"
                );

        try (KafkaConsumer<String, KafkaEvent<?>> consumer =
                     createConsumer()) {

            prepareConsumer(consumer);

            publisher.publish(
                    event,
                    correlationId
            );

            KafkaEvent<?> kafkaEvent =
                    consumeSingleEvent(consumer);

            assertNotNull(kafkaEvent);

            assertNotNull(
                    kafkaEvent.eventId()
            );

            assertEquals(
                    KafkaEventType.PLATFORM_MESSAGE_EVENT,
                    kafkaEvent.eventType()
            );

            assertEquals(
                    1,
                    kafkaEvent.schemaVersion()
            );

            assertEquals(
                    occurredAt,
                    kafkaEvent.occurredAt()
            );

            assertEquals(
                    correlationId,
                    kafkaEvent.correlationId()
            );

            assertNotNull(
                    kafkaEvent.payload()
            );

            assertEquals(
                    PlatformMessageKafkaEvent.class,
                    kafkaEvent.payload().getClass()
            );

            PlatformMessageKafkaEvent payload =
                    (PlatformMessageKafkaEvent)
                            kafkaEvent.payload();

            assertEquals(
                    channelAccountId,
                    payload.channelAccountId()
            );

            assertEquals(
                    "external-123",
                    payload.externalId()
            );

            assertEquals(
                    PlatformMessageEventType.DELIVERED,
                    payload.type()
            );

            assertEquals(
                    "metadata",
                    payload.metadata()
            );
        }
    }

    @Test
    void publish_withoutOccurredAt_generatesOccurredAt() {
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "external-456",
                        PlatformMessageEventType.SENT,
                        null,
                        null
                );

        try (KafkaConsumer<String, KafkaEvent<?>> consumer =
                     createConsumer()) {

            prepareConsumer(consumer);

            publisher.publish(
                    event,
                    correlationId
            );

            KafkaEvent<?> kafkaEvent =
                    consumeSingleEvent(consumer);

            assertNotNull(kafkaEvent);

            assertNotNull(
                    kafkaEvent.occurredAt()
            );

            assertEquals(
                    KafkaEventType.PLATFORM_MESSAGE_EVENT,
                    kafkaEvent.eventType()
            );

            assertEquals(
                    correlationId,
                    kafkaEvent.correlationId()
            );

            assertNotNull(
                    kafkaEvent.payload()
            );

            assertEquals(
                    PlatformMessageKafkaEvent.class,
                    kafkaEvent.payload().getClass()
            );

            PlatformMessageKafkaEvent payload =
                    (PlatformMessageKafkaEvent)
                            kafkaEvent.payload();

            assertEquals(
                    channelAccountId,
                    payload.channelAccountId()
            );

            assertEquals(
                    "external-456",
                    payload.externalId()
            );

            assertEquals(
                    PlatformMessageEventType.SENT,
                    payload.type()
            );

            assertNull(
                    payload.metadata()
            );
        }
    }

    private KafkaConsumer<String, KafkaEvent<?>> createConsumer() {
        Properties properties =
                new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA_BOOTSTRAP_SERVERS
        );

        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                CONSUMER_GROUP_ID
        );

        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "latest"
        );

        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"
        );

        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        return new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new KafkaEventJsonDeserializer()
        );
    }

    private void prepareConsumer(
            KafkaConsumer<String, KafkaEvent<?>> consumer
    ) {
        consumer.subscribe(
                Collections.singletonList(
                        KafkaTopicNames.platformEvents()
                )
        );

        waitForAssignment(consumer);

        Set<TopicPartition> partitions =
                consumer.assignment();

        if (partitions.isEmpty()) {
            throw new AssertionError(
                    "Kafka consumer was not assigned any partitions"
            );
        }

        Map<TopicPartition, Long> endOffsets =
                consumer.endOffsets(partitions);

        for (TopicPartition partition : partitions) {
            consumer.seek(
                    partition,
                    endOffsets.get(partition)
            );
        }
    }

    private void waitForAssignment(
            KafkaConsumer<String, KafkaEvent<?>> consumer
    ) {
        long deadline =
                System.currentTimeMillis()
                        + MAX_WAIT.toMillis();

        while (
                consumer.assignment().isEmpty()
                        && System.currentTimeMillis() < deadline
        ) {
            consumer.poll(POLL_TIMEOUT);
        }

        if (consumer.assignment().isEmpty()) {
            throw new AssertionError(
                    "Kafka consumer was not assigned any partitions within "
                            + MAX_WAIT
            );
        }
    }

    private KafkaEvent<?> consumeSingleEvent(
            KafkaConsumer<String, KafkaEvent<?>> consumer
    ) {
        long deadline =
                System.currentTimeMillis()
                        + MAX_WAIT.toMillis();

        while (System.currentTimeMillis() < deadline) {

            var records =
                    consumer.poll(POLL_TIMEOUT);

            for (
                    ConsumerRecord<String, KafkaEvent<?>> record
                    : records
            ) {
                return record.value();
            }
        }

        throw new AssertionError(
                "No platform event received from Kafka within "
                        + MAX_WAIT
        );
    }
}