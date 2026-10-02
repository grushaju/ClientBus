package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformConversationPublisher;
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
class KafkaSyncRecentChatsConsumerTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ChannelConnectorRegistry connectorRegistry;

    @Mock
    private IChannelConnector connector;

    @Mock
    private IPlatformConversationPublisher publisher;

    private KafkaSyncRecentChatsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaSyncRecentChatsConsumer(
                connectorRegistry,
                publisher
        );

    }

    @Test
    void shouldPublishEverySynchronizedConversation() {
        PlatformConversationRequest first = conversation("200");
        PlatformConversationRequest second = conversation("201");

        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);

        when(connector.handle(any(SyncRecentChatsCommand.class)))
                .thenReturn(List.of(first, second));

        consumer.consume(event(), KafkaTopicNames.channelCommand(ChannelType.TELEGRAM));

        verify(connectorRegistry).getConnector(ChannelType.TELEGRAM);
        verify(connector).handle(
                new SyncRecentChatsCommand(ACCOUNT_ID)
        );
        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void shouldNotPublishWhenNoConversationsFound() {
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

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE,
                        1,
                        Instant.now(),
                        null,
                        new SyncRecentChatsKafkaCommand(ACCOUNT_ID)
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

    @Test
    void shouldRejectNullPayload() {

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.SYNC_RECENT_CHATS,
                        1,
                        Instant.now(),
                        null,
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event,
                        KafkaTopicNames.channelCommand(ChannelType.TELEGRAM)
                )
        );
    }

    private static KafkaEvent<SyncRecentChatsKafkaCommand> event() {
        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.SYNC_RECENT_CHATS,
                1,
                Instant.now(),
                null,
                new SyncRecentChatsKafkaCommand(ACCOUNT_ID)
        );
    }

    private static PlatformConversationRequest conversation(
            String externalId
    ) {
        return new PlatformConversationRequest(
                ACCOUNT_ID,
                externalId,
                null,
                null,
                "Client " + externalId,
                Instant.parse("2026-10-01T10:00:00Z"),
                "Hello",
                0
        );
    }
}
