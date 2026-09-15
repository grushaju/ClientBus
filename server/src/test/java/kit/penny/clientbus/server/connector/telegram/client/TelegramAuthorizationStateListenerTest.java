package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAuthorizationStateListenerTest {

    private UUID channelId;

    private ChannelRepository channelRepository;
    private ChannelAccountRepository channelAccountRepository;
    private TelegramProperties properties;
    private TelegramAuthorizationManager authorizationManager;
    private ObjectProvider<TelegramClient> telegramClientProvider;

    private ChannelEntity channel;
    private ChannelAccountEntity account;

    private TelegramAuthorizationStateListener listener;

    @BeforeEach
    void setUp() {

        channelId = UUID.randomUUID();

        channelRepository =
                mock(ChannelRepository.class);

        channelAccountRepository =
                mock(ChannelAccountRepository.class);

        properties =
                mock(TelegramProperties.class);

        authorizationManager =
                mock(TelegramAuthorizationManager.class);

        telegramClientProvider =
                mock(ObjectProvider.class);

        channel =
                TestDataFactory.channel(null);

        account =
                TestDataFactory.channelAccount(
                        channel,
                        "channel-external-id",
                        "channel-username",
                        "+79990000000",
                        "channel-displayName"
                );

        channel.setId(channelId);
        channel.setAccount(account);

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        listener =
                new TelegramAuthorizationStateListener(
                        channelId,
                        channelRepository,
                        channelAccountRepository,
                        properties,
                        authorizationManager,
                        telegramClientProvider
                );
    }

    @Test
    void readyStateShouldSetChannelStatusToConnected() {

        TelegramClient telegramClient =
                mock(TelegramClient.class);

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        when(telegramClient.sendAsync(
                new TdApi.GetMe()
        )).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        new kit.penny.tdlib.query.TdlibResponse<>(
                                null,
                                new TdApi.Error(
                                        500,
                                        "test"
                                )
                        )
                )
        );

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateReady()
                )
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                channel.getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository)
                .save(channel);
    }

    @Test
    void loggingOutStateShouldSetChannelStatusToDisconnected() {

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateLoggingOut()
                )
        );

        assertEquals(
                ChannelConnectionStatus.DISCONNECTED,
                channel.getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository)
                .save(channel);
    }

    @Test
    void closingStateShouldSetChannelStatusToDisconnected() {

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosing()
                )
        );

        assertEquals(
                ChannelConnectionStatus.DISCONNECTED,
                channel.getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository)
                .save(channel);
    }

    @Test
    void closedStateShouldSetChannelStatusToDisconnected() {

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosed()
                )
        );

        assertEquals(
                ChannelConnectionStatus.DISCONNECTED,
                channel.getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository)
                .save(channel);
    }

    @Test
    void nullNotificationShouldBeIgnored() {

        channel.setStatus(
                ChannelConnectionStatus.CONNECTED
        );

        listener.handleNotification(null);

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                channel.getStatus()
        );

        verify(channelRepository, never())
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);

        verify(telegramClientProvider, never())
                .getObject();
    }

    @Test
    void nullAuthorizationStateShouldBeIgnored() {

        channel.setStatus(
                ChannelConnectionStatus.CONNECTED
        );

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(null)
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                channel.getStatus()
        );

        verify(channelRepository, never())
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }

    @Test
    void sameStatusShouldNotSaveChannel() {

        channel.setStatus(
                ChannelConnectionStatus.DISCONNECTED
        );

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosed()
                )
        );

        assertEquals(
                ChannelConnectionStatus.DISCONNECTED,
                channel.getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }

    @Test
    void missingChannelShouldNotSaveStatus() {

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.empty());

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosed()
                )
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }

    @Test
    void missingChannelAccountShouldNotSaveStatus() {

        channel.setAccount(null);

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosed()
                )
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }

    @Test
    void notificationTypeShouldReturnUpdateAuthorizationState() {

        assertEquals(
                TdApi.UpdateAuthorizationState.class,
                listener.notificationType()
        );
    }

    @Test
    void repositoryFailureShouldBeHandled() {

        when(channelRepository.findById(channelId))
                .thenThrow(
                        new RuntimeException(
                                "database failure"
                        )
                );

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateClosed()
                )
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }

    @Test
    void unsupportedAuthorizationStateShouldNotChangeStatus() {

        channel.setStatus(
                ChannelConnectionStatus.CONNECTED
        );

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateWaitRegistration()
                )
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                channel.getStatus()
        );

        verify(channelRepository, never())
                .findById(channelId);

        verify(channelRepository, never())
                .save(channel);
    }
}
