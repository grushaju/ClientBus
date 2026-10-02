package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaSyncConversationHistoryConsumerTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ChannelConnectorRegistry connectorRegistry;

    @Mock
    private IChannelConnector connector;

    @Mock
    private IPlatformMessagePublisher publisher;

    private KafkaSyncConversationHistoryConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaSyncConversationHistoryConsumer(
                connectorRegistry,
                publisher
        );

//        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
//                .thenReturn(connector);
    }

    @Test
    void shouldForwardHistoryCommandAndPublishMessages() {
        PlatformMessageRequest first = message("101");
        PlatformMessageRequest second = message("102");

        when(connector.handle(any(SyncConversationHistoryCommand.class)))
                .thenReturn(List.of(first, second));

        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);

        consumer.consume(event(), KafkaTopicNames.channelCommand(ChannelType.TELEGRAM));

        verify(connector).handle(
                new SyncConversationHistoryCommand(
                        ACCOUNT_ID,
                        "200",
                        "105",
                        50
                )
        );

        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void shouldSkipNullMessagesReturnedByConnector() {
        when(connector.handle(any(SyncConversationHistoryCommand.class)))
                .thenReturn(java.util.Arrays.asList(message("101"), null));

        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);

        consumer.consume(event(), KafkaTopicNames.channelCommand(ChannelType.TELEGRAM));

        verify(publisher).publish(any(PlatformMessageRequest.class));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void shouldNotPublishForEmptyHistory() {
        when(connector.handle(any(SyncConversationHistoryCommand.class)))
                .thenReturn(List.of());

        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);

        consumer.consume(event(), KafkaTopicNames.channelCommand(ChannelType.TELEGRAM));

        verifyNoInteractions(publisher);
    }

    @Test
    void shouldRejectNullEvent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        null,
                        KafkaTopicNames.channelCommand(ChannelType.TELEGRAM)
                )
        );
    }

    @Test
    void shouldRejectWrongEventType() {
        KafkaEvent<SyncConversationHistoryKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE,
                        1,
                        Instant.now(),
                        null,
                        new SyncConversationHistoryKafkaCommand(
                                ACCOUNT_ID,
                                "200",
                                "105",
                                50
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event,
                        KafkaTopicNames.channelCommand(ChannelType.TELEGRAM)
                )
        );

        verifyNoInteractions(connectorRegistry, publisher);
    }

    private static KafkaEvent<SyncConversationHistoryKafkaCommand> event() {
        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.SYNC_CONVERSATION_HISTORY,
                1,
                Instant.now(),
                null,
                new SyncConversationHistoryKafkaCommand(
                        ACCOUNT_ID,
                        "200",
                        "105",
                        50
                )
        );
    }

    private static PlatformMessageRequest message(String externalId) {
        return new PlatformMessageRequest(
                ACCOUNT_ID,
                "200",
                "john_doe",
                null,
                "John Doe",
                "200",
                externalId,
                MessageType.TEXT,
                "Message " + externalId,
                null,
                Instant.parse("2026-10-01T10:00:00Z"),
                List.of()
        );
    }
}
