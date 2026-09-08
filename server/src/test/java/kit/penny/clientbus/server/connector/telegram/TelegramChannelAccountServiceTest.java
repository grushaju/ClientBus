package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelAccountServiceTest {

    @Mock
    private ChannelAccountRepository channelAccountRepository;

    @Mock
    private TelegramClientLifecycleService lifecycleService;

    private TelegramChannelAccountService service;

    @BeforeEach
    void setUp() {
        service = new TelegramChannelAccountService(
                channelAccountRepository,
                lifecycleService
        );
    }

    @Test
    void createShouldCreateTelegramClientForAccount() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        null,
                        null,
                        null,
                        "+79990000000",
                        null
                );

        account.setId(channelAccountId);

        TelegramClientContext context =
                mock(TelegramClientContext.class);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        when(lifecycleService.create(
                channelAccountId,
                "+79990000000"
        )).thenReturn(context);

        TelegramClientContext result =
                service.create(channelAccountId);

        assertSame(context, result);

        verify(channelAccountRepository)
                .findById(channelAccountId);

        verify(lifecycleService)
                .create(
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
                IllegalArgumentException.class,
                () -> service.create(channelAccountId)
        );

        verify(lifecycleService, never())
                .create(any(), any());
    }

    @Test
    void createShouldFailWhenPhoneIsNotConfigured() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        account.setId(channelAccountId);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        assertThrows(
                IllegalStateException.class,
                () -> service.create(channelAccountId)
        );

        verify(lifecycleService, never())
                .create(any(), any());
    }

    @Test
    void getShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        TelegramClientContext context =
                mock(TelegramClientContext.class);

        when(lifecycleService.get(channelAccountId))
                .thenReturn(context);

        TelegramClientContext result =
                service.get(channelAccountId);

        assertSame(context, result);

        verify(lifecycleService)
                .get(channelAccountId);
    }

    @Test
    void requireShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        TelegramClientContext context =
                mock(TelegramClientContext.class);

        when(lifecycleService.require(channelAccountId))
                .thenReturn(context);

        TelegramClientContext result =
                service.require(channelAccountId);

        assertSame(context, result);

        verify(lifecycleService)
                .require(channelAccountId);
    }

    @Test
    void stopShouldDelegateToLifecycleService() {
        UUID channelAccountId = UUID.randomUUID();

        service.stop(channelAccountId);

        verify(lifecycleService)
                .stop(channelAccountId);
    }

    @Test
    void restartShouldRestartTelegramClientForAccount() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        null,
                        null,
                        null,
                        "+79990000000",
                        null
                );

        account.setId(channelAccountId);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        service.restart(channelAccountId);

        verify(channelAccountRepository)
                .findById(channelAccountId);

        verify(lifecycleService)
                .restart(
                        channelAccountId,
                        "+79990000000"
                );
    }

    @Test
    void restartShouldFailWhenPhoneIsNotConfigured() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        account.setId(channelAccountId);

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(account));

        assertThrows(
                IllegalStateException.class,
                () -> service.restart(channelAccountId)
        );

        verify(lifecycleService, never())
                .restart(any(), any());
    }
}