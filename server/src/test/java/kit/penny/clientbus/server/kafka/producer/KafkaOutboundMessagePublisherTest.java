package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboundMessagePublisherTest {

    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaOutboundMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate =
                mock(KafkaTemplate.class);

        publisher =
                new KafkaOutboundMessagePublisher(
                        kafkaTemplate
                );
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
    }

    @Test
    void publish_shouldSendEventToCorrectTopicAndWaitForKafkaAck()
            throws Exception {

        UUID channelAccountId =
                UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                command(channelAccountId);

        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(
                        mock(SendResult.class)
                );

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                )
        ).thenReturn(future);

        publisher.publish(
                ChannelType.TELEGRAM,
                command
        );

        ArgumentCaptor<String> topicCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<KafkaEvent> eventCaptor =
                ArgumentCaptor.forClass(KafkaEvent.class);

        verify(kafkaTemplate)
                .send(
                        topicCaptor.capture(),
                        keyCaptor.capture(),
                        eventCaptor.capture()
                );

        assertThat(topicCaptor.getValue())
                .isEqualTo(
                        KafkaTopicNames.outbound(
                                ChannelType.TELEGRAM
                        )
                );

        assertThat(keyCaptor.getValue())
                .isEqualTo(
                        channelAccountId.toString()
                );

        KafkaEvent<?> event =
                eventCaptor.getValue();

        assertThat(event)
                .isNotNull();

        assertThat(event.eventType())
                .isEqualTo(
                        KafkaEventType.OUTBOUND_MESSAGE
                );

        assertThat(event.schemaVersion())
                .isEqualTo(1);

        assertThat(event.correlationId())
                .isNotNull();

        assertThat(event.eventId())
                .isNotNull();

        assertThat(event.occurredAt())
                .isNotNull();

        assertThat(event.payload())
                .isSameAs(command);
    }

    @Test
    void publish_shouldThrowWhenKafkaPublicationFails()
            throws Exception {

        UUID channelAccountId =
                UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                command(channelAccountId);

        RuntimeException cause =
                new RuntimeException(
                        "Kafka broker unavailable"
                );

        CompletableFuture<SendResult<String, Object>> future =
                new CompletableFuture<>();

        future.completeExceptionally(cause);

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                )
        ).thenReturn(future);

        assertThatThrownBy(
                () -> publisher.publish(
                        ChannelType.TELEGRAM,
                        command
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Kafka outbound message publication failed"
                )
                .hasCause(cause);

        verify(kafkaTemplate)
                .send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                );
    }

    @Test
    void publish_shouldThrowWhenKafkaPublicationTimesOut()
            throws Exception {

        UUID channelAccountId =
                UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                command(channelAccountId);

        CompletableFuture<SendResult<String, Object>> future =
                mock(CompletableFuture.class);

        when(
                future.get(
                        10L,
                        TimeUnit.SECONDS
                )
        ).thenThrow(
                new TimeoutException(
                        "Kafka send timed out"
                )
        );

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                )
        ).thenReturn(future);

        assertThatThrownBy(
                () -> publisher.publish(
                        ChannelType.TELEGRAM,
                        command
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Kafka outbound message publication timed out"
                )
                .hasCauseInstanceOf(
                        TimeoutException.class
                );

        verify(kafkaTemplate)
                .send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                );
    }

    @Test
    void publish_shouldRestoreInterruptFlagWhenInterrupted()
            throws Exception {

        UUID channelAccountId =
                UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                command(channelAccountId);

        CompletableFuture<SendResult<String, Object>> future =
                mock(CompletableFuture.class);

        when(
                future.get(
                        10L,
                        TimeUnit.SECONDS
                )
        ).thenThrow(
                new InterruptedException(
                        "Kafka send interrupted"
                )
        );

        when(
                kafkaTemplate.send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                )
        ).thenReturn(future);

        assertThatThrownBy(
                () -> publisher.publish(
                        ChannelType.TELEGRAM,
                        command
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Kafka outbound message publication interrupted"
                )
                .hasCauseInstanceOf(
                        InterruptedException.class
                );

        assertThat(
                Thread.currentThread().isInterrupted()
        )
                .isTrue();
    }

    @Test
    void publish_shouldRejectNullChannelType() {

        OutboundMessageKafkaCommand command =
                command(UUID.randomUUID());

        assertThatThrownBy(
                () -> publisher.publish(
                        null,
                        command
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "ChannelType must not be null"
                );

        verify(kafkaTemplate, never())
                .send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                );
    }

    @Test
    void publish_shouldRejectNullCommand() {

        assertThatThrownBy(
                () -> publisher.publish(
                        ChannelType.TELEGRAM,
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Command must not be null"
                );

        verify(kafkaTemplate, never())
                .send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                );
    }

    @Test
    void publish_shouldRejectCommandWithoutChannelAccountId() {

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        UUID.randomUUID(),
                        null,
                        "telegram-user",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        assertThatThrownBy(
                () -> publisher.publish(
                        ChannelType.TELEGRAM,
                        command
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "ChannelAccountId must not be null"
                );

        verify(kafkaTemplate, never())
                .send(
                        any(String.class),
                        any(String.class),
                        any(KafkaEvent.class)
                );
    }

    private OutboundMessageKafkaCommand command(
            UUID channelAccountId
    ) {
        return new OutboundMessageKafkaCommand(
                UUID.randomUUID(),
                channelAccountId,
                "telegram-user",
                MessageType.TEXT,
                "Hello Telegram",
                List.of()
        );
    }
}
