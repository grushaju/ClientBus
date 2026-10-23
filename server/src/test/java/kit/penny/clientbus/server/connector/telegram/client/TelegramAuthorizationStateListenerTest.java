package kit.penny.clientbus.server.connector.telegram.client;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.server.service.ChannelStatusService;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TelegramAuthorizationStateListenerTest {
    UUID account;ChannelStatusService status;IChannelListener events;TelegramAuthorizationStateListener listener;ObjectProvider<TelegramClient> clients;
    @BeforeEach void setup(){account=UUID.randomUUID();status=mock(ChannelStatusService.class);events=mock(IChannelListener.class);
        clients=mock(ObjectProvider.class);when(clients.getObject()).thenReturn(mock(TelegramClient.class));
        listener=new TelegramAuthorizationStateListener(UUID.randomUUID(),account,status,mock(TelegramAccountProfileSynchronizer.class),
            mock(TelegramProperties.class),mock(TelegramAuthorizationManager.class),clients,List.of(events));}
    @Test void closedDelegatesEffectiveStateToCommonWriterAndPublishesOnlyAChange(){when(status.transition(account,ChannelConnectionStatus.DISCONNECTED,"CONNECTOR_AUTH_STATE")).thenReturn(true);
        listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateClosed()));
        verify(events).channelChanged(new ChannelEvent(account,ChannelConnectionStatus.DISCONNECTED));}
    @Test void repeatedCallbackDoesNotPublishAnExtraEvent(){listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateClosed()));
        verify(status).transition(account,ChannelConnectionStatus.DISCONNECTED,"CONNECTOR_AUTH_STATE");verifyNoInteractions(events);}
    @Test void ordinaryAuthFlowUsesConnectingWithoutAnExtraCapability(){listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateWaitCode()));
        verify(status).transition(account,ChannelConnectionStatus.CONNECTING,"CONNECTOR_AUTH_STATE");}
    @Test void readyUsesCommonRecoveryPath(){
        when(status.transition(account,ChannelConnectionStatus.CONNECTED,"CONNECTOR_AUTH_STATE")).thenReturn(true);
        listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateReady()));
        verify(status).transition(account,ChannelConnectionStatus.CONNECTED,"CONNECTOR_AUTH_STATE");
        verify(events).channelChanged(new ChannelEvent(account,ChannelConnectionStatus.CONNECTED));
    }
    @Test void closingAndLoggingOutUseTheSameCommonDisconnectPath(){
        for(TdApi.AuthorizationState state:new TdApi.AuthorizationState[]{new TdApi.AuthorizationStateClosing(),new TdApi.AuthorizationStateLoggingOut()}){
            clearInvocations(status);listener.handleNotification(new TdApi.UpdateAuthorizationState(state));
            verify(status).transition(account,ChannelConnectionStatus.DISCONNECTED,"CONNECTOR_AUTH_STATE");
        }
    }
    @Test void nullNotificationDoesNotInitializeAConnectorClient(){listener.handleNotification(null);verify(clients,never()).getObject();verifyNoInteractions(status,events);}
    @Test void nullAndUnsupportedUpdatesDoNotWriteState(){listener.handleNotification(null);listener.handleNotification(new TdApi.UpdateAuthorizationState(null));
        listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateWaitRegistration()));verifyNoInteractions(status,events);}
    @Test void failedWriteDoesNotPublishUncommittedState(){when(status.transition(any(),any(),any())).thenThrow(new IllegalStateException("failed"));
        assertDoesNotThrow(()->listener.handleNotification(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateClosed())));verifyNoInteractions(events);}
    @Test void declaresItsUpdateType(){assertEquals(TdApi.UpdateAuthorizationState.class,listener.notificationType());}
}
