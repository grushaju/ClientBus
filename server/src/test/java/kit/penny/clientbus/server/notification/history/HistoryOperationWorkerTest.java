package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.notification.HistoryOperationStatus;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.SyncConversationHistoryResult;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryOperationWorkerTest {
    HistorySyncService history;
    ChannelConnectorRegistry registry;
    IChannelConnector connector;
    SimpleMeterRegistry meters;
    HistorySyncService.Operation claim;
    HistorySyncService.Execution execution;

    @BeforeEach void setup() {
        history=mock(HistorySyncService.class);registry=mock(ChannelConnectorRegistry.class);connector=mock(IChannelConnector.class);
        meters=new SimpleMeterRegistry();claim=operation(UUID.randomUUID(),1);
        execution=new HistorySyncService.Execution(ChannelType.TELEGRAM,"opaque-chat");
        when(history.claim(claim.id())).thenReturn(Optional.of(claim));
        when(history.execution(any())).thenReturn(execution);when(history.renew(any())).thenReturn(true);
        when(history.due()).thenReturn(List.of());when(registry.getConnector(ChannelType.TELEGRAM)).thenReturn(connector);
        when(connector.handle(any(SyncConversationHistoryCommand.class))).thenReturn(new SyncConversationHistoryResult(List.of(),true));
    }

    private HistorySyncService.Operation operation(UUID id,long version) {
        Instant now=Instant.now();
        return new HistorySyncService.Operation(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "key","hash",null,20,HistoryOperationStatus.RUNNING,1,version,now.plusSeconds(30),now.plusSeconds(120),now,now,null,null);
    }
    private HistoryOperationWorker worker() { return new HistoryOperationWorker(history,registry,new HistorySyncSettings(1,4,4,3),meters); }
    private double gauge(String name) { return meters.get(name).gauge().value(); }
    private static void uninterruptible(CountDownLatch release) {
        while(release.getCount()>0) try { release.await(); } catch(InterruptedException ignored) { /* Simulate an uncancellable connector. */ }
    }
    private void idle() { await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertEquals(0,gauge("clientbus.history.worker.slots.occupied"))); }

    @Test void occupiedSlotsCoverPreclaimAndUncooperativeCallAfterOwnershipLoss() throws Exception {
        var acquiring=new CountDownLatch(1);var claimRelease=new CountDownLatch(1);
        var calling=new CountDownLatch(1);var callRelease=new CountDownLatch(1);
        when(history.claim(claim.id())).thenAnswer(invocation->{acquiring.countDown();uninterruptible(claimRelease);return Optional.of(claim);});
        when(connector.handle(any(SyncConversationHistoryCommand.class))).thenAnswer(invocation->{calling.countDown();uninterruptible(callRelease);return new SyncConversationHistoryResult(List.of(),true);});
        try(var worker=worker()) {
            worker.offer(claim.id());assertTrue(acquiring.await(2,TimeUnit.SECONDS));
            assertEquals(1,gauge("clientbus.history.worker.slots.occupied"));assertEquals(0,gauge("clientbus.history.worker.active"));
            UUID another=UUID.randomUUID();worker.offer(another);verify(history,never()).claim(another);
            assertEquals(1,meters.get("clientbus.history.worker.offers").tag("result","full").counter().count());
            claimRelease.countDown();assertTrue(calling.await(2,TimeUnit.SECONDS));
            assertEquals(1,gauge("clientbus.history.worker.active"));assertEquals(1,gauge("clientbus.history.worker.capacity"));
            when(history.renew(claim)).thenReturn(false);
            worker.tick();worker.tick();
            verify(history,times(1)).renew(claim);
            assertEquals(1,meters.get("clientbus.history.worker.stale").tag("stage","renewal").counter().count());
            assertEquals(1,gauge("clientbus.history.worker.slots.occupied"),"Logical completion does not free an actual connector slot");
            callRelease.countDown();idle();
            assertEquals(1,meters.get("clientbus.history.worker.stale").tag("stage","completion").counter().count());
            verify(history,never()).importBatch(any(),anyList(),any(),anyBoolean(),anyInt(),anyBoolean());verify(history,never()).fail(any(),anyString());
        } finally { claimRelease.countDown();callRelease.countDown(); }
    }

    @Test void shutdownIsBoundedStopsAdmissionAndDoesNotFailAcceptedOperation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(connector.handle(any(SyncConversationHistoryCommand.class))).thenAnswer(invocation->{entered.countDown();uninterruptible(release);return new SyncConversationHistoryResult(List.of(),true);});
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(HistoryOperationWorker.class,this::worker);context.refresh();
            var worker=context.getBean(HistoryOperationWorker.class);
            worker.offer(claim.id());assertTrue(entered.await(2,TimeUnit.SECONDS));
            long started=System.nanoTime();context.close();
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(1))<0);
            assertEquals(0,gauge("clientbus.history.worker.admission.enabled"));assertEquals(1,gauge("clientbus.history.worker.active"));
            UUID another=UUID.randomUUID();worker.offer(another);worker.tick();worker.close();
            verify(history,never()).claim(another);verify(history,never()).maintain();
            release.countDown();idle();
            verify(history,never()).importBatch(any(),anyList(),any(),anyBoolean(),anyInt(),anyBoolean());verify(history,never()).fail(any(),anyString());
        } finally { release.countDown(); }
    }

    @Test void restartedWorkerCanCompleteNewClaimWhileStoppedOldConnectorStillRuns() throws Exception {
        var newer=operation(claim.id(),2);when(history.claim(claim.id())).thenReturn(Optional.of(claim),Optional.of(newer));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(connector.handle(any(SyncConversationHistoryCommand.class))).thenAnswer(invocation->{
            if(calls.incrementAndGet()==1){entered.countDown();uninterruptible(release);}
            return new SyncConversationHistoryResult(List.of(),true);
        });
        try(var old=worker()) {
            old.offer(claim.id());assertTrue(entered.await(2,TimeUnit.SECONDS));old.close();
            try(var restarted=new HistoryOperationWorker(history,registry,new HistorySyncSettings(1,4,4,3),new SimpleMeterRegistry())) {
                restarted.offer(claim.id());
                await().atMost(Duration.ofSeconds(3)).untilAsserted(()->verify(history).importBatch(eq(newer),eq(List.of()),eq(execution),eq(true),eq(0),eq(true)));
                release.countDown();idle();
                verify(history,never()).importBatch(eq(claim),anyList(),any(),anyBoolean(),anyInt(),anyBoolean());verify(history,never()).fail(eq(claim),anyString());
            }
        } finally { release.countDown(); }
    }

    @Test void closingDuringClaimLeavesAcceptedWorkForDurableRecovery() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(history.claim(claim.id())).thenAnswer(invocation->{entered.countDown();uninterruptible(release);return Optional.of(claim);});
        try(var worker=worker()) {
            worker.offer(claim.id());assertTrue(entered.await(2,TimeUnit.SECONDS));worker.close();release.countDown();idle();
            verify(history,never()).execution(any());verify(history,never()).fail(any(),anyString());verifyNoInteractions(connector);
        } finally { release.countDown(); }
    }

    @Test void fencedImportCountsStaleCompletionWithoutFailingNewOwner() {
        meters.counter("clientbus.history.worker.stale","stage","completion");
        doThrow(new HistorySyncService.StaleClaim()).when(history).importBatch(eq(claim),anyList(),eq(execution),eq(true),eq(0),eq(true));
        try(var worker=worker()) {
            worker.offer(claim.id());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertEquals(1,meters.get("clientbus.history.worker.stale").tag("stage","completion").counter().count()));
            idle();verify(history,never()).fail(any(),anyString());
        }
    }

    @Test void maintenanceReportsUnavailableAndKeepsPreviousSuccessAge() {
        try(var worker=worker()) {
            assertTrue(Double.isNaN(gauge("clientbus.history.maintenance.last.success.age.seconds")));assertEquals(0,gauge("clientbus.history.maintenance.valid"));
            worker.tick();double age=gauge("clientbus.history.maintenance.last.success.age.seconds");assertEquals(1,gauge("clientbus.history.maintenance.valid"));
            doThrow(new IllegalStateException("private-database-data")).when(history).maintain();worker.tick();
            assertEquals(0,gauge("clientbus.history.maintenance.valid"));assertTrue(gauge("clientbus.history.maintenance.last.success.age.seconds")>=age);
            assertEquals(1,meters.get("clientbus.history.errors").tag("stage","maintenance").counter().count());
            assertEquals(1,meters.get("clientbus.history.maintenance.duration").tag("result","success").timer().count());
            assertEquals(1,meters.get("clientbus.history.maintenance.duration").tag("result","failure").timer().count());
        }
    }
}
