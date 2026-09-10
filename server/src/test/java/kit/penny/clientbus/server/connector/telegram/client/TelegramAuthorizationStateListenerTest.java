package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAuthorizationStateListenerTest {

    private ChannelRepository channelRepository;
    private TelegramProperties properties;
    private TelegramAuthorizationManager authorizationManager;
    private ObjectProvider<TelegramClient> telegramClientProvider;

    private TelegramAuthorizationStateListener listener;

    @BeforeEach
    void setUp() {
        channelRepository = mock(ChannelRepository.class);
        properties = mock(TelegramProperties.class);
        authorizationManager = mock(TelegramAuthorizationManager.class);
        telegramClientProvider = mock(ObjectProvider.class);

        listener = new TelegramAuthorizationStateListener(
                UUID.randomUUID(),
                channelRepository,
                properties,
                authorizationManager,
                telegramClientProvider
        );
    }

    @Test
    void readyStateShouldSetChannelStatusToConnected() {
        UUID channelId = UUID.randomUUID();

        ChannelEntity channel = TestDataFactory.channel(null);

        ChannelAccountEntity account =
                TestDataFactory.channelAccount(
                        channel,
                        "channel-external-id",
                        "channel-username",
                        "+79990000000",
                        "channel-displayName"
                );
        channel.setId(channelId);

        listener = new TelegramAuthorizationStateListener(
                channelId,
                channelRepository,
                properties,
                authorizationManager,
                telegramClientProvider
        );

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateReady()
                )
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                account.getChannel().getStatus()
        );

        verify(channelRepository)
                .findById(channelId);

        verify(channelRepository)
                .save(channel);
    }
}