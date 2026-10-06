package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.producer.ISyncRecentChatsCommandPublisher;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChannelSynchronizationListenerTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ISyncRecentChatsCommandPublisher publisher;

    @Mock
    private ChannelAccountRepository channelAccountRepository;

    private ChannelSynchronizationListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChannelSynchronizationListener(
                publisher,
                channelAccountRepository
        );
    }

    @Test
    public void shouldPublishRecentChatsSyncWhenChannelBecomesConnected() {
        ChannelEntity channel =
                new ChannelEntity(
                        null,
                        ChannelType.TELEGRAM,
                        "Test TG Channel"
                );

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        channel,
                        null,
                        null,
                        null,
                        null
                );

        account.setId(CHANNEL_ACCOUNT_ID);

        when(channelAccountRepository.findById(CHANNEL_ACCOUNT_ID))
                .thenReturn(Optional.of(account));

        listener.channelChanged(
                new ChannelEvent(
                        CHANNEL_ACCOUNT_ID,
                        ChannelConnectionStatus.CONNECTED
                )
        );

        verify(publisher).publish(
                ChannelType.TELEGRAM,
                new SyncRecentChatsCommand(CHANNEL_ACCOUNT_ID)
        );

        verifyNoMoreInteractions(publisher);
    }

    @Test
    public void shouldIgnoreNonConnectedStatus() {
        listener.channelChanged(
                new ChannelEvent(
                        CHANNEL_ACCOUNT_ID,
                        ChannelConnectionStatus.CONNECTING
                )
        );

        verifyNoInteractions(
                publisher,
                channelAccountRepository
        );
    }

    @Test
    public void shouldIgnoreNullEvent() {
        listener.channelChanged(null);

        verifyNoInteractions(
                publisher,
                channelAccountRepository
        );
    }

    @Test
    public void shouldRejectMissingChannelAccount() {
        when(channelAccountRepository.findById(CHANNEL_ACCOUNT_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> listener.channelChanged(
                        new ChannelEvent(
                                CHANNEL_ACCOUNT_ID,
                                ChannelConnectionStatus.CONNECTED
                        )
                )
        );

        verifyNoInteractions(publisher);
    }

    @Test
    public void shouldRejectChannelAccountWithoutChannel() {
        ChannelAccountEntity account =
                new ChannelAccountEntity();

        account.setId(CHANNEL_ACCOUNT_ID);

        when(channelAccountRepository.findById(CHANNEL_ACCOUNT_ID))
                .thenReturn(Optional.of(account));

        assertThrows(
                IllegalStateException.class,
                () -> listener.channelChanged(
                        new ChannelEvent(
                                CHANNEL_ACCOUNT_ID,
                                ChannelConnectionStatus.CONNECTED
                        )
                )
        );

        verifyNoInteractions(publisher);
    }
}