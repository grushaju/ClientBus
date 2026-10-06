package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.RecentChatsSynchronizationService;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaSyncRecentChatsConsumerTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID SYNC_RUN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    @Mock
    private ChannelConnectorRegistry connectorRegistry;

    @Mock
    private IChannelConnector connector;

    @Mock
    private RecentChatsSynchronizationService
            recentChatsSynchronizationService;

    @Mock
    private RecentChatsSyncCoordinator coordinator;

    private KafkaSyncRecentChatsConsumer consumer;

    @BeforeEach
    void setUp() {

        consumer =
                new KafkaSyncRecentChatsConsumer(
                        connectorRegistry,
                        recentChatsSynchronizationService,
                        coordinator
                );
    }

    @Test
    void currentRun_connectorServiceAndComplete() {

        PlatformConversationRequest first =
                conversation("200");

        PlatformConversationRequest second =
                conversation("201");

        List<PlatformConversationRequest> conversations =
                List.of(first, second);

        when(coordinator.isExecutable(
                ACCOUNT_ID,
                SYNC_RUN_ID
        )).thenReturn(true);

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.handle(
                any(SyncRecentChatsCommand.class)
        )).thenReturn(conversations);

        consumer.consume(
                event(),
                KafkaTopicNames.channelCommand(
                        ChannelType.TELEGRAM
                )
        );

        verify(coordinator)
                .isExecutable(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verify(connectorRegistry)
                .getConnector(ChannelType.TELEGRAM);

        verify(connector)
                .handle(
                        new SyncRecentChatsCommand(
                                ACCOUNT_ID
                        )
                );

        verify(recentChatsSynchronizationService)
                .process(
                        ChannelType.TELEGRAM,
                        conversations
                );

        verify(coordinator)
                .complete(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verifyNoMoreInteractions(
                connectorRegistry,
                connector,
                recentChatsSynchronizationService,
                coordinator
        );
    }

    @Test
    void staleRun_noConnectorNoService() {

        when(coordinator.isExecutable(
                ACCOUNT_ID,
                SYNC_RUN_ID
        )).thenReturn(false);

        consumer.consume(
                event(),
                KafkaTopicNames.channelCommand(
                        ChannelType.TELEGRAM
                )
        );

        verify(coordinator)
                .isExecutable(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verify(coordinator)
                .complete(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verifyNoInteractions(
                connectorRegistry,
                connector,
                recentChatsSynchronizationService
        );
    }

    @Test
    void disconnectedCurrentRun_noConnectorNoServiceAndComplete() {

        when(coordinator.isExecutable(
                ACCOUNT_ID,
                SYNC_RUN_ID
        )).thenReturn(false);

        consumer.consume(
                event(),
                KafkaTopicNames.channelCommand(
                        ChannelType.TELEGRAM
                )
        );

        verify(coordinator)
                .isExecutable(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verify(coordinator)
                .complete(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );

        verifyNoInteractions(
                connectorRegistry,
                connector,
                recentChatsSynchronizationService
        );
    }

    @Test
    void shouldDelegateEmptyResultToService() {

        when(coordinator.isExecutable(
                ACCOUNT_ID,
                SYNC_RUN_ID
        )).thenReturn(true);

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.handle(
                any(SyncRecentChatsCommand.class)
        )).thenReturn(List.of());

        consumer.consume(
                event(),
                KafkaTopicNames.channelCommand(
                        ChannelType.TELEGRAM
                )
        );

        verify(connector)
                .handle(
                        new SyncRecentChatsCommand(
                                ACCOUNT_ID
                        )
                );

        verify(recentChatsSynchronizationService)
                .process(
                        ChannelType.TELEGRAM,
                        List.of()
                );

        verify(coordinator)
                .complete(
                        ACCOUNT_ID,
                        SYNC_RUN_ID
                );
    }

    @Test
    void shouldRejectNullEvent() {

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        null,
                        KafkaTopicNames.channelCommand(
                                ChannelType.TELEGRAM
                        )
                )
        );

        verifyNoInteractions(
                coordinator,
                connectorRegistry,
                recentChatsSynchronizationService
        );
    }

    @Test
    void shouldRejectWrongEventType() {

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        SYNC_RUN_ID,
                        KafkaEventType.PLATFORM_MESSAGE,
                        1,
                        Instant.now(),
                        null,
                        new SyncRecentChatsKafkaCommand(
                                ACCOUNT_ID
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event,
                        KafkaTopicNames.channelCommand(
                                ChannelType.TELEGRAM
                        )
                )
        );

        verifyNoInteractions(
                coordinator,
                connectorRegistry,
                recentChatsSynchronizationService
        );
    }

    @Test
    void shouldRejectNullPayload() {

        KafkaEvent<SyncRecentChatsKafkaCommand> event =
                new KafkaEvent<>(
                        SYNC_RUN_ID,
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
                        KafkaTopicNames.channelCommand(
                                ChannelType.TELEGRAM
                        )
                )
        );

        verifyNoInteractions(
                coordinator,
                connectorRegistry,
                recentChatsSynchronizationService
        );
    }

    private static KafkaEvent<SyncRecentChatsKafkaCommand> event() {

        return new KafkaEvent<>(
                SYNC_RUN_ID,
                KafkaEventType.SYNC_RECENT_CHATS,
                1,
                Instant.now(),
                null,
                new SyncRecentChatsKafkaCommand(
                        ACCOUNT_ID
                )
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
                Instant.parse(
                        "2026-10-01T10:00:00Z"
                ),
                "Hello",
                0
        );
    }
}