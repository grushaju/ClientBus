package kit.penny.clientbus.server.connector.telegram.account;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TelegramChannelAccountServiceTest {

    private ChannelAccountRepository channelAccountRepository;
    private ChannelRepository channelRepository;
    private TelegramClientLifecycleService lifecycleService;

    private TelegramChannelAccountService service;

    @BeforeEach
    void setUp() {
        channelAccountRepository = mock(ChannelAccountRepository.class);
        channelRepository = mock(ChannelRepository.class);
        lifecycleService = mock(TelegramClientLifecycleService.class);

        service = new TelegramChannelAccountService(
                channelAccountRepository,
                channelRepository,
                lifecycleService
        );
    }

    @Test
    void createShouldCreateTelegramClientForAccount() {
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

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        service.create(channelAccountId);

        assertEquals(
                ChannelConnectionStatus.CONNECTING,
                account.getChannel().getStatus()
        );

        verify(channelAccountRepository).findById(channelAccountId);
        verify(lifecycleService).create(
                channelAccountId,
                "+79990000000"
        );
    }

    @Test
    void createShouldFailWhenAccountDoesNotExist() {
        UUID channelAccountId = UUID.randomUUID();

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> service.create(channelAccountId)
        );

        verify(channelAccountRepository).findById(channelAccountId);
    }

    @Test
    void createShouldFailWhenPhoneIsNotConfigured() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                TestDataFactory.channelAccount(
                        TestDataFactory.channel(null),
                        "channel-external-id",
                        "channel-username",
                        null,
                        "channel-displayName"
                );
        account.setId(channelAccountId);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        assertThrows(
                IllegalStateException.class,
                () -> service.create(channelAccountId)
        );

        verify(channelAccountRepository).findById(channelAccountId);
    }

    @Test
    void getShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        TelegramClientContext context = mock(TelegramClientContext.class);

        when(lifecycleService.get(channelAccountId))
                .thenReturn(context);

        TelegramClientContext result =
                service.get(channelAccountId);

        assertSame(context, result);

        verify(lifecycleService).get(channelAccountId);
    }

    @Test
    void requireShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        TelegramClientContext context = mock(TelegramClientContext.class);

        when(lifecycleService.require(channelAccountId))
                .thenReturn(context);

        TelegramClientContext result =
                service.require(channelAccountId);

        assertSame(context, result);

        verify(lifecycleService).require(channelAccountId);
    }

    @Test
    void stopShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        service.stop(channelAccountId);

        verify(lifecycleService).stop(channelAccountId);
    }

    @Test
    void restartShouldRestartTelegramClientForAccount() {
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

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        service.restart(channelAccountId);

        assertEquals(
                ChannelConnectionStatus.CONNECTING,
                account.getChannel().getStatus()
        );

        verify(channelAccountRepository).findById(channelAccountId);
        verify(lifecycleService).restart(
                channelAccountId,
                "+79990000000"
        );
    }

    @Test
    void restartShouldFailWhenPhoneIsNotConfigured() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                TestDataFactory.channelAccount(
                        TestDataFactory.channel(null),
                        "channel-external-id",
                        "channel-username",
                        null,
                        "channel-displayName"
                );
        account.setId(channelAccountId);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        assertThrows(
                IllegalStateException.class,
                () -> service.restart(channelAccountId)
        );

        verify(channelAccountRepository).findById(channelAccountId);
    }

    @Test
    void restartShouldSetErrorStatusWhenLifecycleFails() {
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

        RuntimeException failure =
                new RuntimeException("restart failed");

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        doThrow(failure)
                .when(lifecycleService)
                .restart(channelAccountId, "+79990000000");

        RuntimeException result =
                assertThrows(
                        RuntimeException.class,
                        () -> service.restart(channelAccountId)
                );

        assertSame(failure, result);

        assertEquals(
                ChannelConnectionStatus.ERROR,
                account.getChannel().getStatus()
        );

        verify(channelAccountRepository).findById(channelAccountId);
        verify(lifecycleService).restart(
                channelAccountId,
                "+79990000000"
        );
    }

    @Test
    void multipleAccountsShouldRemainIsolated() {
        UUID accountAId = UUID.randomUUID();
        UUID accountBId = UUID.randomUUID();

        ChannelAccountEntity accountA =
                TestDataFactory.channelAccount(
                        TestDataFactory.channel(null),
                        "external-a",
                        "username-a",
                        "+79990000001",
                        "display-a"
                );
        accountA.setId(accountAId);

        ChannelAccountEntity accountB =
                TestDataFactory.channelAccount(
                        TestDataFactory.channel(null),
                        "external-b",
                        "username-b",
                        "+79990000002",
                        "display-b"
                );
        accountB.setId(accountBId);

        TelegramClientContext contextA =
                mock(TelegramClientContext.class);
        TelegramClientContext contextB =
                mock(TelegramClientContext.class);

        when(channelAccountRepository.findById(accountAId))
                .thenReturn(Optional.of(accountA));
        when(channelAccountRepository.findById(accountBId))
                .thenReturn(Optional.of(accountB));

        when(lifecycleService.create(accountAId, "+79990000001"))
                .thenReturn(contextA);
        when(lifecycleService.create(accountBId, "+79990000002"))
                .thenReturn(contextB);

        when(lifecycleService.get(accountAId))
                .thenReturn(contextA);
        when(lifecycleService.get(accountBId))
                .thenReturn(contextB);

        TelegramClientContext resultA =
                service.create(accountAId);

        TelegramClientContext resultB =
                service.create(accountBId);

        assertSame(contextA, resultA);
        assertSame(contextB, resultB);

        assertSame(
                contextA,
                service.get(accountAId)
        );

        assertSame(
                contextB,
                service.get(accountBId)
        );

        service.stop(accountAId);

        assertEquals(
                ChannelConnectionStatus.DISCONNECTED,
                accountA.getChannel().getStatus()
        );

        assertEquals(
                ChannelConnectionStatus.CONNECTING,
                accountB.getChannel().getStatus()
        );

        verify(lifecycleService).stop(accountAId);
        verify(lifecycleService, times(1)).create(
                accountAId,
                "+79990000001"
        );
        verify(lifecycleService, times(1)).create(
                accountBId,
                "+79990000002"
        );

        verify(channelRepository, times(2))
                .save(accountA.getChannel());
    }
}