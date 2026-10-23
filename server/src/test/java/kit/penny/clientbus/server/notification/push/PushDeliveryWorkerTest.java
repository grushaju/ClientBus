package kit.penny.clientbus.server.notification.push;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.common.dto.notification.NotificationDto;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.center.NotificationCenterService;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PushDeliveryWorkerTest {
    PushDeliveryStore store;NotificationCenterService center;AccessPolicy access;PushSettings settings;PushEndpointCipher cipher;
    NotificationProperties config;PushDeliveryStore.Claim claim;
    @BeforeEach void setup(){
        store=mock(PushDeliveryStore.class);center=mock(NotificationCenterService.class);access=mock(AccessPolicy.class);
        settings=new PushSettings(Base64.getEncoder().encodeToString(new byte[32]),List.of(new PushSettings.AllowedApp("clientbus.test",PushProviderType.FCM,PushEnvironment.SANDBOX)),1,Duration.ofMillis(50));
        cipher=new PushEndpointCipher(settings);config=new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.push-enabled","false")))
            .bind("clientbus.notifications",NotificationProperties.class).get();
        claim=new PushDeliveryStore.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1,1,Instant.now().plusSeconds(300),
            PushProviderType.FCM,"clientbus.test",PushEnvironment.SANDBOX,PushPlatform.ANDROID,cipher.encrypt("private-endpoint"),"fingerprint");
        when(store.current(claim)).thenReturn(true);when(access.allowsPush(claim.id())).thenReturn(true);
        when(center.findForRecipient(claim.notificationId(),claim.recipientId())).thenReturn(Optional.of(new NotificationDto(claim.notificationId(),NotificationType.INCOMING_MESSAGE,
            UUID.randomUUID(),null,Map.of(),Instant.now(),null,claim.expiresAt(),true)));
    }
    PushDeliveryWorker worker(PushProvider provider){return new PushDeliveryWorker(store,center,access,cipher,settings,config,List.of(provider),new SimpleMeterRegistry());}
    @Test void uninterruptibleProviderIsBoundedAndCannotGrowTheExecutorQueue() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        PushProvider provider=new PushProvider(){public PushProviderType provider(){return PushProviderType.FCM;}
            public Result send(Request request){entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}
                return new Result(Outcome.ACCEPTED,"late",null);}};
        try(var worker=worker(provider)){
            long start=System.nanoTime();worker.deliver(claim);assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
            verify(store).retry(claim,"PROVIDER_TIMEOUT",Duration.ZERO);
            worker.deliver(claim);verify(store).retry(claim,"PROVIDER_BUSY",Duration.ZERO);
            release.countDown();Thread.sleep(30);verify(store,never()).terminal(any(),eq("PROVIDER_ACCEPTED"),any(),any());
        }finally{release.countDown();}
    }
    @Test void policyFailureRetriesWithoutCallingProvider(){var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(access.allowsPush(claim.id())).thenThrow(new IllegalStateException("unavailable"));
        try(var worker=worker(provider)){worker.deliver(claim);}verify(provider,never()).send(any());verify(store).retry(claim,"POLICY_OR_DATABASE_UNAVAILABLE",Duration.ZERO);}
    @Test void providerRejectionIsTerminalAndDoesNotRetry(){var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(provider.send(any())).thenReturn(new PushProvider.Result(PushProvider.Outcome.REJECTED,null,null));
        try(var worker=worker(provider)){worker.deliver(claim);}verify(store).terminal(claim,"FAILED","PROVIDER_REJECTED",null);verify(store,never()).retry(any(),any(),any());}
    @Test void unknownAdapterAndMissingEncryptionFailBeforeStartingWorker(){
        var unknown=mock(PushProvider.class);when(unknown.provider()).thenReturn(PushProviderType.APNS);
        assertThrows(IllegalStateException.class,()->worker(unknown));
        var emptyCipher=new PushEndpointCipher(new PushSettings("",settings.allowedApps(),1,Duration.ofSeconds(1)));
        assertThrows(PushEndpointCipher.Unavailable.class,()->new PushDeliveryWorker(store,center,access,emptyCipher,settings,config,List.of(),new SimpleMeterRegistry()));
    }
}
