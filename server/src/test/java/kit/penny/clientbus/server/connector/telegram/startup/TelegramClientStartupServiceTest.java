package kit.penny.clientbus.server.connector.telegram.startup;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.service.ChannelStatusService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
class TelegramClientStartupServiceTest {
    ChannelAccountRepository accounts;TelegramChannelAccountService clients;ChannelStatusService status;TelegramClientStartupService service;
    @BeforeEach void setup(){accounts=mock(ChannelAccountRepository.class);clients=mock(TelegramChannelAccountService.class);status=mock(ChannelStatusService.class);
        service=new TelegramClientStartupService(accounts,clients,status);}
    @Test void restoresOnlyConfiguredActiveStatusesThroughNormalLifecycle(){var first=mock(ChannelAccountEntity.class);var second=mock(ChannelAccountEntity.class);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();when(first.getId()).thenReturn(a);when(second.getId()).thenReturn(b);
        when(accounts.findAllByChannelTypeAndChannelStatusIn(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.CONNECTED,ChannelConnectionStatus.CONNECTING))).thenReturn(List.of(first,second));
        service.restoreTelegramClients();var order=inOrder(clients);order.verify(clients).create(a);order.verify(clients).create(b);verifyNoMoreInteractions(clients);verifyNoInteractions(status);}
    @Test void failureDelegatesToCommonIncidentWriterAndDoesNotBlockOtherAccounts(){var failed=mock(ChannelAccountEntity.class);var next=mock(ChannelAccountEntity.class);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();when(failed.getId()).thenReturn(a);when(next.getId()).thenReturn(b);
        when(accounts.findAllByChannelTypeAndChannelStatusIn(any(),any())).thenReturn(List.of(failed,next));when(clients.create(a)).thenThrow(new IllegalStateException("failed"));
        service.restoreTelegramClients();verify(status).transition(a,ChannelConnectionStatus.ERROR,"CONNECTOR_RESTORE_FAILED");verify(clients).create(b);verify(accounts,never()).save(any());}
    @Test void emptySnapshotDoesNotCreateIncidents(){when(accounts.findAllByChannelTypeAndChannelStatusIn(any(),any())).thenReturn(List.of());service.restoreTelegramClients();verifyNoInteractions(clients,status);}
}
