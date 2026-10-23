package kit.penny.clientbus.server.kafka.config;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaErrorHandlerConfigTest {

    private static final UUID ATTEMPT_ID = UUID.randomUUID();

    private static final String TOPIC =
            "clientbus.outbound.telegram";

    private static final int PARTITION = 2;

    private static final long OFFSET = 17L;

    private KafkaErrorHandlerConfig config;

    private MessageService messageService;

    private DeadLetterPublishingRecoverer deadLetterPublishingRecoverer;

    private Consumer<?, ?> consumer;

    private MessageListenerContainer container;

    @Mock
    private RecentChatsSyncCoordinator recentChatsSyncCoordinator;

    @BeforeEach
    void setUp() {
        messageService =
                mock(MessageService.class);

        deadLetterPublishingRecoverer =
                mock(DeadLetterPublishingRecoverer.class);

        recentChatsSyncCoordinator =
                mock(RecentChatsSyncCoordinator.class);

        consumer =
                mock(Consumer.class);

        container =
                mock(MessageListenerContainer.class);

        config =
                new KafkaErrorHandlerConfig(
                        recentChatsSyncCoordinator
                );
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
                .markDeliveryFailed(messageId, ATTEMPT_ID);

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);

        verify(consumer, never())
                .seek(
                        any(TopicPartition.class),
                        any(Long.class)
                );
    }

    @Test
    void removedMessageStillReachesDlqWithoutBlockingThePartition() {
        var errorHandler = config.kafkaCommonErrorHandler(deadLetterPublishingRecoverer, messageService);
        UUID messageId = UUID.randomUUID();
        var record = outboundRecord(messageId);
        var failure = new EntityNotFoundException("Removed target");
        when(messageService.markDeliveryFailed(messageId, ATTEMPT_ID)).thenThrow(failure);

        errorHandler.handleOne(failure, record, consumer, container);

        verify(messageService).markDeliveryFailed(messageId, ATTEMPT_ID);
        verify(deadLetterPublishingRecoverer).accept(record, failure);
        verifyNoInteractions(recentChatsSyncCoordinator);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldPublishEverySourcePartitionToSinglePartitionDlq(int sourcePartition) {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        DeadLetterPublishingRecoverer recoverer = config.kafkaDeadLetterPublishingRecoverer(template);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(TOPIC, sourcePartition, OFFSET, "key", "payload");

        recoverer.accept(record, new IllegalStateException("Delivery failure"));

        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertEquals(TOPIC + ".dlq", sent.getValue().topic());
        assertEquals(0, sent.getValue().partition());
        assertEquals(record.key(), sent.getValue().key());
        assertEquals(record.value(), sent.getValue().value());
        assertEquals(sourcePartition, ByteBuffer.wrap(sent.getValue().headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt());
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
                .markDeliveryFailed(any(), any());

        verify(deadLetterPublishingRecoverer, never())
                .accept(any(), any());

        errorHandler.handleOne(
                exception,
                record,
                consumer,
                container
        );

        verify(messageService)
                .markDeliveryFailed(messageId, ATTEMPT_ID);

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
                .markDeliveryFailed(messageIdCaptor.capture(), eq(ATTEMPT_ID));

        verifyNoInteractions(recentChatsSyncCoordinator);

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
                .markDeliveryFailed(any(), any());

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
                .markDeliveryFailed(any(), any());

        verify(deadLetterPublishingRecoverer)
                .accept(record, exception);
    }


    @Test
    void shouldReleaseRecentChatsSyncRunOnlyOnTerminalRecovery() {
        CommonErrorHandler errorHandler =
                config.kafkaCommonErrorHandler(
                        deadLetterPublishingRecoverer,
                        messageService
                );

        UUID channelAccountId = UUID.randomUUID();
        UUID syncRunId = UUID.randomUUID();

        SyncRecentChatsKafkaCommand command =
                new SyncRecentChatsKafkaCommand(
                        channelAccountId
                );

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        syncRunId,
                        KafkaEventType.SYNC_RECENT_CHATS,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        command
                );

        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>(
                        "clientbus.outbound.telegram",
                        PARTITION,
                        OFFSET,
                        "key",
                        event
                );

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

        verify(recentChatsSyncCoordinator, never())
                .complete(channelAccountId, syncRunId);

        errorHandler.handleOne(
                exception,
                record,
                consumer,
                container
        );

        verify(recentChatsSyncCoordinator)
                .complete(channelAccountId, syncRunId);

        verify(messageService, never())
                .markDeliveryFailed(any(), any());

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
                        List.of(), ATTEMPT_ID
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
