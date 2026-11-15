package kit.penny.clientbus.connector.telegram.startup;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.connector.telegram.storage.TelegramDataStorage;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
class TelegramClientStartupServiceTest {
    HostChannelAccounts accounts;TelegramChannelAccountService clients;HostChannelStatus status;TelegramDataStorage storage;TelegramClientStartupService service;
    @BeforeEach void setup(){accounts=mock(HostChannelAccounts.class);clients=mock(TelegramChannelAccountService.class);status=mock(HostChannelStatus.class);storage=mock(TelegramDataStorage.class);
        service=new TelegramClientStartupService(accounts,clients,status,storage);}
    @Test void restoresOnlyConfiguredActiveStatusesThroughNormalLifecycle(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        when(accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.CONNECTED,ChannelConnectionStatus.CONNECTING))).thenReturn(List.of(a,b));
        service.restoreTelegramClients();var order=inOrder(clients);order.verify(clients).create(a);order.verify(clients).create(b);verifyNoMoreInteractions(clients);verifyNoInteractions(status);}
    @Test void failureDelegatesToCommonIncidentWriterAndDoesNotBlockOtherAccounts(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        when(accounts.findIdsByChannelTypeAndChannelStatuses(any(),any())).thenReturn(List.of(a,b));when(clients.create(a)).thenThrow(new IllegalStateException("failed"));
        service.restoreTelegramClients();verify(status).transition(a,ChannelConnectionStatus.ERROR,"CONNECTOR_RESTORE_FAILED");verify(clients).create(b);
        verify(accounts,never()).updateProfile(any(),any(),any(),any(),any(),any());}
    @Test void emptySnapshotDoesNotCreateIncidents(){when(accounts.findIdsByChannelTypeAndChannelStatuses(any(),any())).thenReturn(List.of());service.restoreTelegramClients();verifyNoInteractions(clients,status);}
    /** DISCONNECTED со штамп последнего останова, но живой сессией TDLib на диске поднимается без ручного перелогина. */
    @Test void disconnectedWithPersistedSessionIsRestored(){
        UUID a=UUID.randomUUID();
        when(accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.DISCONNECTED))).thenReturn(List.of(a));
        when(storage.hasPersistedSession(a)).thenReturn(true);
        service.restoreTelegramClients();verify(clients).create(a);verifyNoInteractions(status);}
    /** «Отключить» удаляет сессию — такой аккаунт не воскрешается. */
    @Test void disconnectedWithoutSessionStaysDown(){
        UUID a=UUID.randomUUID();
        when(accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.DISCONNECTED))).thenReturn(List.of(a));
        when(storage.hasPersistedSession(a)).thenReturn(false);
        service.restoreTelegramClients();verifyNoInteractions(clients,status);}
    /** Аккаунт, попавший в обе выборки, поднимается один раз. */
    @Test void accountInBothQueriesIsRestoredOnce(){
        UUID a=UUID.randomUUID();
        when(accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.CONNECTED,ChannelConnectionStatus.CONNECTING))).thenReturn(List.of(a));
        when(accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.TELEGRAM,List.of(ChannelConnectionStatus.DISCONNECTED))).thenReturn(List.of(a));
        when(storage.hasPersistedSession(a)).thenReturn(true);
        service.restoreTelegramClients();verify(clients,times(1)).create(a);}
}
