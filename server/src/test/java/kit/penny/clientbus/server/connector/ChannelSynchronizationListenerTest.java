package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelSynchronizationListenerTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    @Mock
    private RecentChatsSyncCoordinator coordinator;

    private ChannelSynchronizationListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new ChannelSynchronizationListener(
                        coordinator
                );
    }

    @Test
    void shouldRequestRecentChatsSyncWhenChannelBecomesConnected() {

        listener.channelChanged(
                new ChannelEvent(
                        CHANNEL_ACCOUNT_ID,
                        ChannelConnectionStatus.CONNECTED
                )
        );

        verify(coordinator)
                .request(CHANNEL_ACCOUNT_ID);

        verifyNoMoreInteractions(coordinator);
    }

    @Test
    void shouldIgnoreNonConnectedStatus() {

        listener.channelChanged(
                new ChannelEvent(
                        CHANNEL_ACCOUNT_ID,
                        ChannelConnectionStatus.CONNECTING
                )
        );

        verifyNoInteractions(coordinator);
    }

    @Test
    void shouldIgnoreNullEvent() {

        listener.channelChanged(null);

        verifyNoInteractions(coordinator);
    }

    @Test
    void shouldRejectMissingChannelAccountId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> listener.channelChanged(
                        new ChannelEvent(
                                null,
                                ChannelConnectionStatus.CONNECTED
                        )
                )
        );

        verifyNoInteractions(coordinator);
    }
}