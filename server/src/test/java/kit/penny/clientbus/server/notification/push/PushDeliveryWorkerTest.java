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
import static org.awaitility.Awaitility.await;
class PushDeliveryWorkerTest {
    PushDeliveryStore store;NotificationCenterService center;AccessPolicy access;PushSettings settings;PushEndpointCipher cipher;
    NotificationProperties config;PushDeliveryStore.Claim claim;
    SimpleMeterRegistry meters;
    @BeforeEach void setup(){
        store=mock(PushDeliveryStore.class);center=mock(NotificationCenterService.class);access=mock(AccessPolicy.class);
        meters=new SimpleMeterRegistry();
        settings=new PushSettings(Base64.getEncoder().encodeToString(new byte[32]),List.of(new PushSettings.AllowedApp("clientbus.test",PushProviderType.FCM,PushEnvironment.SANDBOX)),1,Duration.ofMillis(50));
        cipher=new PushEndpointCipher(settings);config=new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.push-enabled","false")))
            .bind("clientbus.notifications",NotificationProperties.class).get();
        claim=new PushDeliveryStore.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1,1,Instant.now().plusSeconds(300),
            PushProviderType.FCM,"clientbus.test",PushEnvironment.SANDBOX,PushPlatform.ANDROID,cipher.encrypt("private-endpoint"),"fingerprint");
        when(store.current(claim)).thenReturn(true);when(access.allowsPush(claim.id())).thenReturn(true);
        when(center.findForRecipient(claim.notificationId(),claim.recipientId())).thenReturn(Optional.of(new NotificationDto(claim.notificationId(),NotificationType.INCOMING_MESSAGE,
            UUID.randomUUID(),null,Map.of(),Instant.now(),null,claim.expiresAt(),true)));
    }
    PushDeliveryWorker worker(PushProvider provider){return new PushDeliveryWorker(store,center,access,cipher,settings,config,List.of(provider),meters);}
    @Test void uninterruptibleProviderIsBoundedAndCannotGrowTheExecutorQueue() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        PushProvider provider=new PushProvider(){public PushProviderType provider(){return PushProviderType.FCM;}
            public Result send(Request request){entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}
                return new Result(Outcome.ACCEPTED,"late",null);}};
        try(var worker=worker(provider)){
            long start=System.nanoTime();worker.deliver(claim);assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
            verify(store).retry(claim,"PROVIDER_TIMEOUT",Duration.ZERO);
            assertEquals(1,meters.get("clientbus.notification.push.provider.active").gauge().value());
            assertEquals(1,meters.get("clientbus.notification.push.provider.capacity").gauge().value());
            assertEquals(1,meters.get("clientbus.notification.push.provider.waits").tag("result","timeout").counter().count());
            worker.deliver(claim);verify(store).retry(claim,"PROVIDER_BUSY",Duration.ZERO);
            assertEquals(1,meters.get("clientbus.notification.push.provider.calls").counter().count(),"Busy rejection never calls provider");
            assertEquals(1,meters.get("clientbus.notification.push.provider.waits").tag("result","busy").counter().count());
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->{
                assertEquals(0,meters.get("clientbus.notification.push.provider.active").gauge().value());
                assertEquals(1,meters.get("clientbus.notification.push.provider.responses").tag("outcome","accepted").counter().count());
            });
            verify(store,never()).terminal(any(),eq("PROVIDER_ACCEPTED"),any(),any());
            assertEquals(1,meters.get("clientbus.notification.push.provider.duration").timer().count());
        }finally{release.countDown();}
    }
    @Test void policyFailureRetriesWithoutCallingProvider(){var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(access.allowsPush(claim.id())).thenThrow(new IllegalStateException("unavailable"));
        try(var worker=worker(provider)){worker.deliver(claim);}verify(provider,never()).send(any());verify(store).retry(claim,"POLICY_OR_DATABASE_UNAVAILABLE",Duration.ZERO);}
    @Test void providerRejectionIsTerminalAndDoesNotRetry(){var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(provider.send(any())).thenReturn(new PushProvider.Result(PushProvider.Outcome.REJECTED,null,null));
        try(var worker=worker(provider)){worker.deliver(claim);}verify(store).terminal(claim,"FAILED","PROVIDER_REJECTED",null);verify(store,never()).retry(any(),any(),any());
        assertEquals(1,meters.get("clientbus.notification.push.provider.responses").tag("outcome","rejected").counter().count());
        assertEquals(1,meters.get("clientbus.notification.push.provider.waits").tag("result","completed").counter().count());}
    @Test void providerExceptionIsCountedAsActualCallAndFailedWait(){
        var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(provider.send(any())).thenThrow(new IllegalStateException("private-endpoint"));
        try(var worker=worker(provider)){worker.deliver(claim);}
        verify(store).retry(claim,"PROVIDER_FAILURE",Duration.ZERO);
        assertEquals(1,meters.get("clientbus.notification.push.provider.calls").counter().count());
        assertEquals(1,meters.get("clientbus.notification.push.provider.responses").tag("outcome","exception").counter().count());
        assertEquals(1,meters.get("clientbus.notification.push.provider.waits").tag("result","failure").counter().count());
        assertTrue(meters.getMeters().stream().flatMap(m->m.getId().getTags().stream()).noneMatch(tag->tag.getValue().contains("private-endpoint")));
    }
    @Test void interruptedWaitIsVisibleAndPreservesInterrupt(){
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        PushProvider provider=new PushProvider(){public PushProviderType provider(){return PushProviderType.FCM;}
            public Result send(Request request){entered.countDown();try{release.await();}catch(InterruptedException ignored){}
                return new Result(Outcome.ACCEPTED,"late",null);}};
        try(var worker=worker(provider)){
            Thread.currentThread().interrupt();worker.deliver(claim);assertTrue(Thread.currentThread().isInterrupted());
            verify(store).retry(claim,"INTERRUPTED",Duration.ZERO);
            assertEquals(1,meters.get("clientbus.notification.push.provider.waits").tag("result","interrupted").counter().count());
        }finally{Thread.interrupted();release.countDown();}
    }
    @Test void staleClaimDoesNotCallProviderOrReportAcceptedResult(){
        var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        when(store.current(claim)).thenReturn(false);
        try(var worker=worker(provider)){worker.deliver(claim);}
        verify(provider,never()).send(any());assertEquals(1,meters.get("clientbus.notification.push.stale").counter().count());
        assertNull(meters.find("clientbus.notification.push.provider.calls").counter());
    }
    @Test void closedWorkerDoesNotClaimOrStartNewProviderCalls(){
        var provider=mock(PushProvider.class);when(provider.provider()).thenReturn(PushProviderType.FCM);
        try(var worker=worker(provider)){
            worker.close();worker.tick();worker.deliver(claim);worker.close();
            verify(store,never()).claim();verify(store,never()).current(any());verify(provider,never()).send(any());
            assertEquals(0,meters.get("clientbus.notification.push.admission.enabled").gauge().value());
        }
    }
    @Test void unknownAdapterAndMissingEncryptionFailBeforeStartingWorker(){
        var unknown=mock(PushProvider.class);when(unknown.provider()).thenReturn(PushProviderType.APNS);
        assertThrows(IllegalStateException.class,()->worker(unknown));
        var emptyCipher=new PushEndpointCipher(new PushSettings("",settings.allowedApps(),1,Duration.ofSeconds(1)));
        assertThrows(PushEndpointCipher.Unavailable.class,()->new PushDeliveryWorker(store,center,access,emptyCipher,settings,config,List.of(),new SimpleMeterRegistry()));
    }
}
