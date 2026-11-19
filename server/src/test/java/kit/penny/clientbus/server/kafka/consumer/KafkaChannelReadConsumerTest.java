package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.ChannelAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaChannelReadConsumerTest {
    @Test void oldPayloadStartsAtTheHeadAndContinuationSurvivesSerialization() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var account = UUID.randomUUID();
        var legacy = mapper.readValue("{\"channelAccountId\":\"" + account
                + "\",\"recipientExternalId\":\"21\",\"lastReadExternalId\":\"200\",\"dialogExternalId\":\"7\"}", ChannelReadKafkaCommand.class);
        assertNull(legacy.beforeExternalId());
        var next = new ChannelReadKafkaCommand(account, "21", "200", "7", "100");
        assertEquals(next, mapper.readValue(mapper.writeValueAsString(next), ChannelReadKafkaCommand.class));
    }

    @Test void continuationIsAcknowledgedBeforeTheCurrentBatchCompletesAndFailuresAreRetried() {
        var registry = mock(ChannelConnectorRegistry.class);
        var availability = mock(ChannelAvailabilityService.class);
        var connector = mock(IChannelConnector.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var consumer = new KafkaChannelReadConsumer(registry, availability, kafka);
        when(registry.getConnector(ChannelType.DIKIDI)).thenReturn(connector);
        var account = UUID.randomUUID();
        var next = new MarkMessagesReadCommand(account, "21", "200", "7", "100");
        when(connector.continueRead(any())).thenReturn(next);
        var acknowledgement = new CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>>();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(acknowledgement);
        var event = new KafkaEvent<>(UUID.randomUUID(), KafkaEventType.CHANNEL_READ, 1, Instant.now(),
                UUID.randomUUID(), new ChannelReadKafkaCommand(account, "21", "200", "7", "150"));
        var processing = CompletableFuture.runAsync(() -> consumer.consume(event, KafkaTopicNames.channelRead(ChannelType.DIKIDI)));
        verify(kafka, timeout(3000)).send(anyString(), eq(account.toString()), argThat(value -> {
            var sent = (KafkaEvent<?>) value;
            return sent.correlationId().equals(event.correlationId())
                    && ((ChannelReadKafkaCommand) sent.payload()).beforeExternalId().equals("100");
        }));
        assertFalse(processing.isDone());
        acknowledgement.complete(null);
        processing.orTimeout(3, java.util.concurrent.TimeUnit.SECONDS).join();
        verify(connector).continueRead(new MarkMessagesReadCommand(account, "21", "200", "7", "150"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> consumer.consume(event, KafkaTopicNames.channelRead(ChannelType.DIKIDI)));
    }
}
