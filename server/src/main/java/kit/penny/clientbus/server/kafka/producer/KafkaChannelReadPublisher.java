package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaChannelReadPublisher
        implements IChannelReadPublisher {

    private static final int SCHEMA_VERSION = 1;
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaChannelReadPublisher(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(
            ChannelType channelType,
            ChannelReadKafkaCommand command
    ) {

        if (channelType == null) {
            throw new IllegalArgumentException(
                    "ChannelType must not be null"
            );
        }

        if (command == null) {
            throw new IllegalArgumentException(
                    "Command must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "ChannelAccountId must not be null"
            );
        }

        if (command.recipientExternalId() == null
                || command.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "RecipientExternalId must not be blank"
            );
        }

        if (command.lastReadExternalId() == null
                || command.lastReadExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "LastReadExternalId must not be blank"
            );
        }

        KafkaEvent<ChannelReadKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.CHANNEL_READ,
                        SCHEMA_VERSION,
                        Instant.now(),
                        UUID.randomUUID(),
                        command
                );

        try {

            kafkaTemplate.send(
                    KafkaTopicNames.channelRead(
                            channelType
                    ),
                    command.channelAccountId().toString(),
                    event
            ).get(
                    SEND_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Kafka channel read publication interrupted",
                    e
            );

        } catch (ExecutionException e) {

            throw new IllegalStateException(
                    "Kafka channel read publication failed",
                    e.getCause()
            );

        } catch (TimeoutException e) {

            throw new IllegalStateException(
                    "Kafka channel read publication timed out",
                    e
            );
        }
    }
}