package kit.penny.clientbus.server.notification.history;

import kit.penny.clientbus.common.dto.notification.HistorySyncRequest;
import kit.penny.clientbus.common.kafka.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.kafka.config.*;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import kit.penny.clientbus.server.service.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryWireProtocolTest {
    private KafkaEvent<HistorySyncCommand> event() {
        UUID operation = UUID.randomUUID();
        return new KafkaEvent<>(UUID.randomUUID(), KafkaEventType.HISTORY_SYNC_OPERATION, 1, Instant.now(), operation,
                new HistorySyncCommand(operation, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), new HistorySyncRequest(null, 100), EventOrigin.HISTORY_IMPORT, Instant.now().plusSeconds(120)));
    }
    @Test void newOperationPayloadAndLegacyHistoryPayloadHaveSeparateDeserializerTypes() {
        var mapper = new JsonMapper(); var deserializer = new KafkaEventJsonDeserializer(); var event = event();
        assertEquals(event, deserializer.deserialize(HistorySyncConfiguration.TOPIC, mapper.writeValueAsBytes(event)));
        var legacy = new KafkaEvent<>(UUID.randomUUID(), KafkaEventType.SYNC_CONVERSATION_HISTORY, 1, Instant.now(), null,
                new SyncConversationHistoryKafkaCommand(UUID.randomUUID(), "chat", null, 50));
        assertInstanceOf(SyncConversationHistoryKafkaCommand.class,
                deserializer.deserialize("clientbus.channel-command.conversation-history.telegram", mapper.writeValueAsBytes(legacy)).payload());
    }
    @Test void commandDlqRecoveryUsesThePersistedOperationProtocol() {
        var history = mock(HistorySyncService.class); var messages = mock(MessageService.class);
        var recoverer = mock(DeadLetterPublishingRecoverer.class);
        var config = new KafkaErrorHandlerConfig(mock(RecentChatsSyncCoordinator.class));
        var handler = config.kafkaCommonErrorHandler(recoverer, messages, history);
        var event = event();
        var record = new ConsumerRecord<String, Object>(HistorySyncConfiguration.TOPIC, 0, 1, "operation", event);
        var failure = new jakarta.persistence.EntityNotFoundException("Deleted target");
        handler.handleOne(failure, record, mock(org.apache.kafka.clients.consumer.Consumer.class), mock(MessageListenerContainer.class));
        verify(history).deadLetter(event); verifyNoInteractions(messages); verify(recoverer).accept(record, failure);
    }
}
