package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
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

    private ChannelAccountRepository channelAccountRepository;
    private TelegramProperties properties;
    private TelegramAuthorizationManager authorizationManager;
    private ObjectProvider<TelegramClient> telegramClientProvider;

    private TelegramAuthorizationStateListener listener;

    @BeforeEach
    void setUp() {
        channelAccountRepository = mock(ChannelAccountRepository.class);
        properties = mock(TelegramProperties.class);
        authorizationManager = mock(TelegramAuthorizationManager.class);
        telegramClientProvider = mock(ObjectProvider.class);

        listener = new TelegramAuthorizationStateListener(
                UUID.randomUUID(),
                channelAccountRepository,
                properties,
                authorizationManager,
                telegramClientProvider
        );
    }

    @Test
    void readyStateShouldSetChannelStatusToConnected() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                TestDataFactory.channelAccount(
                        TestDataFactory.channel(null),
                        "channel-external-id",
                        "channel-username",
                        "+79990000000",
                        "channel-displayName"
                );
        account.setId(channelAccountId);

        listener = new TelegramAuthorizationStateListener(
                channelAccountId,
                channelAccountRepository,
                properties,
                authorizationManager,
                telegramClientProvider
        );

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        listener.handleNotification(
                new TdApi.UpdateAuthorizationState(
                        new TdApi.AuthorizationStateReady()
                )
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTED,
                account.getChannel().getStatus()
        );

        verify(channelAccountRepository)
                .findById(channelAccountId);

        verify(channelAccountRepository)
                .save(account);
    }
}