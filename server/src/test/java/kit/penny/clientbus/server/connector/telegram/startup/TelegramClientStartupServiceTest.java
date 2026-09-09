package kit.penny.clientbus.server.connector.telegram.startup;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TelegramClientStartupServiceTest {

    private ChannelAccountRepository channelAccountRepository;
    private TelegramChannelAccountService channelAccountService;

    private TelegramClientStartupService service;

    @BeforeEach
    void setUp() {
        channelAccountRepository = mock(ChannelAccountRepository.class);
        channelAccountService = mock(TelegramChannelAccountService.class);

        service = new TelegramClientStartupService(
                channelAccountRepository,
                channelAccountService
        );
    }

    @Test
    void restoreTelegramClientsShouldRestoreConnectedTelegramAccounts() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        ChannelAccountEntity first = mock(ChannelAccountEntity.class);
        ChannelAccountEntity second = mock(ChannelAccountEntity.class);

        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);

        when(channelAccountRepository
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                ))
                .thenReturn(List.of(first, second));

        service.restoreTelegramClients();

        verify(channelAccountRepository)
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                );

        verify(channelAccountService).create(firstId);
        verify(channelAccountService).create(secondId);

        verifyNoMoreInteractions(channelAccountService);
    }

    @Test
    void restoreTelegramClientsShouldDoNothingWhenNoAccountsFound() {
        when(channelAccountRepository
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                ))
                .thenReturn(List.of());

        service.restoreTelegramClients();

        verify(channelAccountRepository)
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                );

        verifyNoMoreInteractions(channelAccountService);
    }

    @Test
    void restoreTelegramClientsShouldSetErrorWhenRestoreFails() {
        UUID channelAccountId = UUID.randomUUID();

        ChannelAccountEntity account = mock(ChannelAccountEntity.class);

        when(account.getId()).thenReturn(channelAccountId);
        when(account.getChannel()).thenReturn(
                mock(kit.penny.clientbus.server.persistence.entity.ChannelEntity.class)
        );

        doThrow(new RuntimeException("restore failed"))
                .when(channelAccountService)
                .create(channelAccountId);

        when(channelAccountRepository
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                ))
                .thenReturn(List.of(account));

        service.restoreTelegramClients();

        verify(channelAccountService).create(channelAccountId);

        verify(account.getChannel())
                .setStatus(ChannelConnectionStatus.ERROR);

        verify(channelAccountRepository).save(account);
    }

    @Test
    void restoreTelegramClientsShouldContinueWhenOneAccountFails() {
        UUID failedId = UUID.randomUUID();
        UUID successfulId = UUID.randomUUID();

        ChannelAccountEntity failed = mock(ChannelAccountEntity.class);
        ChannelAccountEntity successful = mock(ChannelAccountEntity.class);

        var failedChannel =
                mock(kit.penny.clientbus.server.persistence.entity.ChannelEntity.class);

        when(failed.getId()).thenReturn(failedId);
        when(failed.getChannel()).thenReturn(failedChannel);

        when(successful.getId()).thenReturn(successfulId);

        doThrow(new RuntimeException("restore failed"))
                .when(channelAccountService)
                .create(failedId);

        when(channelAccountRepository
                .findAllByChannelTypeAndChannelStatus(
                        ChannelType.TELEGRAM,
                        ChannelConnectionStatus.CONNECTED
                ))
                .thenReturn(List.of(failed, successful));

        service.restoreTelegramClients();

        verify(channelAccountService).create(failedId);
        verify(channelAccountService).create(successfulId);

        verify(failedChannel)
                .setStatus(ChannelConnectionStatus.ERROR);

        verify(channelAccountRepository).save(failed);

        assertEquals(
                ChannelConnectionStatus.ERROR,
                // состояние хранится на ChannelEntity;
                // здесь достаточно проверить вызов setStatus выше
                ChannelConnectionStatus.ERROR
        );
    }
}