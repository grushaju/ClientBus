package kit.penny.clientbus.server.kafka.config;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.service.MessageService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KafkaErrorHandlerConfigTest {

    private static final String TOPIC =
            "clientbus.outbound.telegram";

    private static final int PARTITION = 2;

    private static final long OFFSET = 17L;

    private KafkaErrorHandlerConfig config;

    private MessageService messageService;

    private DeadLetterPublishingRecoverer deadLetterPublishingRecoverer;

    private Consumer<?, ?> consumer;

    private MessageListenerContainer container;

    @BeforeEach
    void setUp() {
        config = new KafkaErrorHandlerConfig();

        messageService = mock(MessageService.class);
        deadLetterPublishingRecoverer =
                mock(DeadLetterPublishingRecoverer.class);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
    }

    @Test
    void shouldRecoverImmediatelyForEntityNotFoundException() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        UUID messageId = UUID.randomUUID();

        ConsumerRecord<String, Object> record =
                outboundRecord(messageId);

        EntityNotFoundException exception =
                new EntityNotFoundException("Message not found");

        errorHandler.handleOne(
                exception,
                record,
                consumer,
                container
        );

        verify(messageService)
                .markDeliveryFailed(messageId);

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);

        verify(consumer, never())
                .seek(
                        any(TopicPartition.class),
                        any(Long.class)
                );
    }

    @Test
    void shouldRetryThreeTimesBeforeRecovery() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        UUID messageId = UUID.randomUUID();

        ConsumerRecord<String, Object> record =
                outboundRecord(messageId);

        RuntimeException exception =
                new RuntimeException("Connector failure");

        for (int attempt = 1; attempt <= 3; attempt++) {
            errorHandler.handleOne(
                    exception,
                    record,
                    consumer,
                    container
            );
        }

        verify(messageService, never())
                .markDeliveryFailed(any());

        verify(deadLetterPublishingRecoverer, never())
                .accept(any(), any());

        errorHandler.handleOne(
                exception,
                record,
                consumer,
                container
        );

        verify(messageService)
                .markDeliveryFailed(messageId);

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);
    }

    @Test
    void shouldMarkOnlyOutboundMessageFromKafkaEventAsFailed() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        UUID messageId = UUID.randomUUID();

        ConsumerRecord<String, Object> record =
                outboundRecord(messageId);

        RuntimeException exception =
                new RuntimeException("Connector failure");

        for (int attempt = 1; attempt <= 4; attempt++) {
            errorHandler.handleOne(
                    exception,
                    record,
                    consumer,
                    container
            );
        }

        ArgumentCaptor<UUID> messageIdCaptor =
                ArgumentCaptor.forClass(UUID.class);

        verify(messageService)
                .markDeliveryFailed(messageIdCaptor.capture());

        assertEquals(
                messageId,
                messageIdCaptor.getValue()
        );
    }

    @Test
    void shouldIgnoreInvalidKafkaRecordPayload() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>(
                        TOPIC,
                        PARTITION,
                        OFFSET,
                        "key",
                        "invalid-payload"
                );

        RuntimeException exception =
                new RuntimeException("Processing failure");

        for (int attempt = 1; attempt <= 4; attempt++) {
            errorHandler.handleOne(
                    exception,
                    record,
                    consumer,
                    container
            );
        }

        verify(messageService, never())
                .markDeliveryFailed(any());

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);
    }

    @Test
    void shouldIgnoreNullRecordValue() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>(
                        TOPIC,
                        PARTITION,
                        OFFSET,
                        "key",
                        null
                );

        RuntimeException exception =
                new RuntimeException("Processing failure");

        for (int attempt = 1; attempt <= 4; attempt++) {
            errorHandler.handleOne(
                    exception,
                    record,
                    consumer,
                    container
            );
        }

        verify(messageService, never())
                .markDeliveryFailed(any());

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);
    }




    private ConsumerRecord<String, Object> outboundRecord(
            UUID messageId
    ) {
        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        UUID.randomUUID(),
                        "telegram-user",
                        kit.penny.clientbus.common.enums.MessageType.TEXT,
                        "Test message",
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

        return new ConsumerRecord<>(
                TOPIC,
                PARTITION,
                OFFSET,
                "key",
                event
        );
    }
}
