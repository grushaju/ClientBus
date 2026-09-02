package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboundMessagePublisherIntegrationTest
        extends AbstractIntegrationTest {

    private static final String BOOTSTRAP_SERVERS =
            "localhost:9092";

    private static final String GROUP_ID =
            "clientbus.outbound.processor.test";

    private static final Duration ASSIGNMENT_TIMEOUT =
            Duration.ofSeconds(10);

    private static final Duration POLL_TIMEOUT =
            Duration.ofSeconds(10);

    @Autowired
    private KafkaOutboundMessagePublisher publisher;

    @Test
    void publish_sendsOutboundMessageCommandToChannelTopic() {
        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "telegram-user-123",
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                );

        String topic =
                KafkaTopicNames.outbound(ChannelType.TELEGRAM);

        try (KafkaConsumer<String, KafkaEvent<?>> consumer =
                     createConsumer(topic)) {

            publisher.publish(
                    ChannelType.TELEGRAM,
                    command
            );

            ConsumerRecord<String, KafkaEvent<?>> record =
                    consumeSingleRecord(consumer);

            assertThat(record.topic())
                    .isEqualTo(topic);

            assertThat(record.key())
                    .isEqualTo(channelAccountId.toString());

            KafkaEvent<?> event = record.value();

            assertThat(event)
                    .isNotNull();

            assertThat(event.eventId())
                    .isNotNull();

            assertThat(event.eventType())
                    .isEqualTo(KafkaEventType.OUTBOUND_MESSAGE);

            assertThat(event.schemaVersion())
                    .isEqualTo(1);

            assertThat(event.occurredAt())
                    .isNotNull();

            assertThat(event.correlationId())
                    .isNotNull();

            assertThat(event.payload())
                    .isInstanceOf(
                            OutboundMessageKafkaCommand.class
                    );

            OutboundMessageKafkaCommand received =
                    (OutboundMessageKafkaCommand) event.payload();

            assertThat(received.messageId())
                    .isEqualTo(messageId);

            assertThat(received.channelAccountId())
                    .isEqualTo(channelAccountId);

            assertThat(received.recipientExternalId())
                    .isEqualTo("telegram-user-123");

            assertThat(received.type())
                    .isEqualTo(MessageType.TEXT);

            assertThat(received.content())
                    .isEqualTo("Hello Telegram");

            assertThat(received.attachments())
                    .isEmpty();
        }
    }

    private KafkaConsumer<String, KafkaEvent<?>> createConsumer(
            String topic
    ) {
        Properties properties =
                new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS
        );

        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                GROUP_ID
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

        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                JsonDeserializer.class
        );

        properties.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "kit.penny.clientbus.common.*"
        );

        properties.put(
                JsonDeserializer.VALUE_DEFAULT_TYPE,
                KafkaEvent.class.getName()
        );

        KafkaConsumer<String, KafkaEvent<?>> consumer =
                new KafkaConsumer<>(
                        properties,
                        new StringDeserializer(),
                        new JsonDeserializer<>(
                                KafkaEvent.class
                        )
                );

        consumer.subscribe(List.of(topic));

        waitForAssignment(consumer);

        consumer.seekToEnd(
                consumer.assignment()
        );

        return consumer;
    }

    private void waitForAssignment(
            KafkaConsumer<String, KafkaEvent<?>> consumer
    ) {
        long deadline =
                System.nanoTime()
                        + ASSIGNMENT_TIMEOUT.toNanos();

        while (
                consumer.assignment().isEmpty()
                        && System.nanoTime() < deadline
        ) {
            consumer.poll(Duration.ofMillis(100));
        }

        assertThat(consumer.assignment())
                .as("Kafka consumer partition assignment")
                .isNotEmpty();
    }

    private ConsumerRecord<String, KafkaEvent<?>> consumeSingleRecord(
            KafkaConsumer<String, KafkaEvent<?>> consumer
    ) {
        long deadline =
                System.nanoTime()
                        + POLL_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            var records =
                    consumer.poll(
                            Duration.ofMillis(500)
                    );

            for (
                    ConsumerRecord<String, KafkaEvent<?>> record
                    : records
            ) {
                KafkaEvent<?> event =
                        record.value();

                if (
                        event != null
                                && event.eventType()
                                == KafkaEventType.OUTBOUND_MESSAGE
                ) {
                    return record;
                }
            }
        }

        throw new AssertionError(
                "No outbound message event received from Kafka within "
                        + POLL_TIMEOUT
        );
    }
}
