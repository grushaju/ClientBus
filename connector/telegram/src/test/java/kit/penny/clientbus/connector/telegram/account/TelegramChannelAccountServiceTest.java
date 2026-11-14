package kit.penny.clientbus.connector.telegram.account;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.connector.telegram.client.*;
import kit.penny.clientbus.connector.telegram.storage.TelegramDataStorage;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TelegramChannelAccountServiceTest {
    HostChannelAccounts accounts;HostChannelStatus status;TelegramClientLifecycleService lifecycle;TelegramDataStorage storage;
    TelegramChannelAccountService service;UUID id;
    static HostChannelAccountSnapshot snap(UUID id,String phone){return new HostChannelAccountSnapshot(id,UUID.randomUUID(),UUID.randomUUID(),ChannelType.TELEGRAM,ChannelConnectionStatus.CONNECTED,phone,"external");}
    @BeforeEach void setup(){accounts=mock(HostChannelAccounts.class);status=mock(HostChannelStatus.class);
        lifecycle=mock(TelegramClientLifecycleService.class);storage=mock(TelegramDataStorage.class);
        service=new TelegramChannelAccountService(accounts,status,lifecycle,storage);id=UUID.randomUUID();
        when(accounts.require(id)).thenReturn(snap(id,"+79990000000"));}
    @Test void creationPersistsConnectingBeforeStartingConnector(){var context=mock(TelegramClientContext.class);when(lifecycle.create(id,"+79990000000")).thenReturn(context);
        assertSame(context,service.create(id));var order=inOrder(status,lifecycle);order.verify(status).beginConnecting(id);order.verify(lifecycle).create(id,"+79990000000");}
    @Test void missingAccountDoesNotStartConnector(){when(accounts.require(id)).thenThrow(new IllegalStateException("Telegram channel account not found: "+id));assertThrows(IllegalStateException.class,()->service.create(id));verifyNoInteractions(status,lifecycle);}
    @Test void missingPhoneDoesNotStartConnector(){when(accounts.require(id)).thenReturn(snap(id,null));assertThrows(IllegalStateException.class,()->service.create(id));verifyNoInteractions(status,lifecycle);}
    @Test void startFailureUsesCommonIncidentWriter(){var failure=new IllegalStateException("failed");when(lifecycle.create(id,"+79990000000")).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->service.create(id)));verify(status).transition(id,ChannelConnectionStatus.ERROR,"CONNECTOR_START_FAILED");}
    @Test void disableCommitsIntentBeforeSynchronousStopCallback(){service.disable(id);var order=inOrder(status,lifecycle);order.verify(status).expectDisconnect(id,ChannelConnectionStatus.DISABLED);order.verify(lifecycle).stop(id);}
    @Test void disconnectCommitsIntentBeforeLogoutAndDeletesDataAfterSuccess(){service.disconnect(id);var order=inOrder(status,lifecycle,storage);
        order.verify(status).expectDisconnect(id,ChannelConnectionStatus.DISCONNECTED);order.verify(lifecycle).disconnect(id);order.verify(storage).delete(id);}
    @Test void failedLogoutKeepsDataButRetainsUserStopIntent(){doThrow(new IllegalStateException("failed")).when(lifecycle).disconnect(id);
        assertThrows(IllegalStateException.class,()->service.disconnect(id));verify(status).expectDisconnect(id,ChannelConnectionStatus.DISCONNECTED);verifyNoInteractions(storage);}
    @Test void enableUsesCommonConnectPath(){service.enable(id);verify(status).beginConnecting(id);verify(lifecycle).create(id,"+79990000000");}
    @Test void restartPersistsConnectingBeforeRestart(){service.restart(id);var order=inOrder(status,lifecycle);order.verify(status).beginConnecting(id);order.verify(lifecycle).restart(id,"+79990000000");}
    @Test void restartFailureUsesCommonIncidentWriter(){doThrow(new IllegalStateException("failed")).when(lifecycle).restart(id,"+79990000000");
        assertThrows(IllegalStateException.class,()->service.restart(id));verify(status).transition(id,ChannelConnectionStatus.ERROR,"CONNECTOR_START_FAILED");}
    @Test void restartWithoutPhoneDoesNotChangeStateOrStartConnector(){
        when(accounts.require(id)).thenReturn(snap(id,null));assertThrows(IllegalStateException.class,()->service.restart(id));verifyNoInteractions(status,lifecycle);
    }
    @Test void lifecycleCommandsKeepTwoAccountsAndContextsIsolated(){
        UUID secondId=UUID.randomUUID();
        when(accounts.require(secondId)).thenReturn(snap(secondId,"+79990000001"));
        var firstContext=mock(TelegramClientContext.class);var secondContext=mock(TelegramClientContext.class);
        when(lifecycle.create(id,"+79990000000")).thenReturn(firstContext);when(lifecycle.create(secondId,"+79990000001")).thenReturn(secondContext);
        assertSame(firstContext,service.create(id));assertSame(secondContext,service.create(secondId));
        service.disable(id);verify(status).beginConnecting(id);verify(status).beginConnecting(secondId);
        verify(status).expectDisconnect(id,ChannelConnectionStatus.DISABLED);verify(status,never()).expectDisconnect(eq(secondId),any());
        verify(lifecycle).stop(id);verify(lifecycle,never()).stop(secondId);
    }
    @Test void gettersDelegate(){var context=mock(TelegramClientContext.class);when(lifecycle.get(id)).thenReturn(context);when(lifecycle.require(id)).thenReturn(context);
        assertSame(context,service.get(id));assertSame(context,service.require(id));verifyNoInteractions(status);}
}
