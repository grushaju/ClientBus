package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiDayCacheTest {
    final DikidiRequestsTest.Time time=new DikidiRequestsTest.Time();
    final UUID project=UUID.randomUUID(),connection=UUID.randomUUID();
    final LocalDate day=LocalDate.of(2026,11,10);
    DikidiDayCache.Key key(long credentials,long epoch){return new DikidiDayCache.Key(project,connection,credentials,"100",day,0,0,epoch);}
    CrmJournalOptions options(boolean force){return new CrmJournalOptions(project,0,0,0,force,time.instant(),time.instant().plusSeconds(5));}
    CrmAccess access(){return new CrmAccess(connection,1,"70000000000","independent".toCharArray());}
    DikidiDayCache.Snapshot snapshot(long sequence){return new DikidiDayCache.Snapshot("verified",time.instant(),sequence,0,day);}
    DikidiDayCache cache(){return new DikidiDayCache(Duration.ofSeconds(30),2,4096,2048,time);}
    @Test void ttlDoesNotSlideAndForceReplacesOriginWithoutAliasingCredentialsOrEpoch(){
        try(var cache=cache();var access=access()){
            var loads=new AtomicInteger();java.util.function.Function<CrmAccess,DikidiDayCache.Snapshot> load=owned->snapshot(loads.incrementAndGet());
            var first=cache.read(key(1,0),access,options(false),load,()->true);assertFalse(first.hit());
            time.now=time.now.plusSeconds(20);var hit=cache.read(key(1,0),access,options(false),load,()->true);
            assertTrue(hit.hit());assertSame(first.snapshot(),hit.snapshot());assertEquals(1,loads.get());
            time.now=time.now.plusSeconds(11);assertFalse(cache.read(key(1,0),access,options(false),load,()->true).hit());
            assertFalse(cache.read(key(1,0),access,options(true),load,()->true).hit());
            assertFalse(cache.read(key(2,0),access,options(false),load,()->true).hit());
            assertFalse(cache.read(key(2,1),access,options(false),load,()->true).hit());assertEquals(5,loads.get());
        }
    }
    @Test void firstCallerCancellationDoesNotCloseSharedCredentialsOrCancelSecondCaller() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var ownedAccess=new AtomicReference<CrmAccess>();var reads=new AtomicInteger();
        var callers=Executors.newFixedThreadPool(2);
        try(var cache=cache();var first=access();var second=access()){
            java.util.function.Function<CrmAccess,DikidiDayCache.Snapshot> load=owned->{
                reads.incrementAndGet();ownedAccess.set(owned);started.countDown();
                try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){throw new AssertionError(error);}
                assertArrayEquals("independent".toCharArray(),owned.secret());return snapshot(1);
            };
            var a=callers.submit(()->cache.read(key(1,0),first,options(false),load,()->true));assertTrue(started.await(2,TimeUnit.SECONDS));
            var b=callers.submit(()->cache.read(key(1,0),second,options(false),load,()->true));
            a.cancel(true);first.close();release.countDown();assertEquals(1,b.get(3,TimeUnit.SECONDS).snapshot().sequence());assertEquals(1,reads.get());
            // Completion is delivered before the loader's finally closes its private handle.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!Arrays.equals(new char[11],ownedAccess.get().secret())&&System.nanoTime()<deadline)Thread.yield();
            assertArrayEquals(new char[11],ownedAccess.get().secret());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void technicalInvalidationPreventsOldLoaderFromPublishingEvenWhenContextSupplierStillReturnsTrue() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newSingleThreadExecutor();
        try(var cache=cache();var access=access()){
            var read=callers.submit(()->cache.read(key(1,0),access,options(false),owned->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return snapshot(1);},()->true));
            assertTrue(started.await(2,TimeUnit.SECONDS));cache.invalidate(connection);
            assertInstanceOf(CrmFailure.class,assertThrows(ExecutionException.class,()->read.get(2,TimeUnit.SECONDS)).getCause());release.countDown();
            // A replacement credential key cannot join the invalidated flight.
            assertFalse(cache.read(key(2,0),access,options(false),owned->snapshot(2),()->true).hit());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void failuresAndOversizedSnapshotsAreNotCached(){
        try(var cache=cache();var access=access()){
            assertThrows(CrmFailure.class,()->cache.read(key(1,0),access,options(false),owned->{throw new CrmFailure(CrmFailure.Code.RATE_LIMITED);},()->true));
            var reads=new AtomicInteger();
            for(int index=0;index<2;index++)assertFalse(cache.read(key(1,0),access,options(false),owned->{reads.incrementAndGet();return new DikidiDayCache.Snapshot("x".repeat(3000),time.instant(),2,0,day);},()->true).hit());
            assertEquals(2,reads.get());
        }
    }
    @Test void forceCannotJoinAReadFromBeforeItsBarrierAndInvalidContextNeverPublishes() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newSingleThreadExecutor();var current=new AtomicBoolean(true);
        try(var cache=cache();var access=access()){
            var read=callers.submit(()->cache.read(key(1,0),access,options(false),owned->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return snapshot(1);},current::get));
            assertTrue(started.await(2,TimeUnit.SECONDS));time.now=time.now.plusSeconds(1);
            assertEquals(CrmFailure.Code.CONNECTION_BUSY,assertThrows(CrmFailure.class,()->cache.read(key(1,0),access,options(true),owned->snapshot(2),current::get)).code());
            current.set(false);release.countDown();assertInstanceOf(CrmFailure.class,assertThrows(ExecutionException.class,()->read.get(2,TimeUnit.SECONDS)).getCause());
            assertEquals(0,cache.diagnostics().entries());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void lruEvictionAndExpiredCallerCannotCreateUnboundedOrUnrequestedWork(){
        try(var cache=cache();var access=access()){
            var loads=new AtomicInteger();java.util.function.Function<CrmAccess,DikidiDayCache.Snapshot> load=owned->snapshot(loads.incrementAndGet());
            for(int credentials=1;credentials<=3;credentials++)cache.read(key(credentials,0),access,options(false),load,()->true);
            assertEquals(2,cache.diagnostics().entries());assertEquals(16,cache.diagnostics().bytes());
            cache.read(key(1,0),access,options(false),load,()->true);assertEquals(4,loads.get());
            var expired=new CrmJournalOptions(project,0,0,0,false,time.instant(),time.instant());
            assertThrows(CrmFailure.class,()->cache.read(key(5,0),access,expired,load,()->true));assertEquals(4,loads.get());
        }
    }
    @Test void slowContextOfCachedKeyDoesNotBlockAnotherConnectionOrInvalidation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);
        var other=new DikidiDayCache.Key(UUID.randomUUID(),UUID.randomUUID(),1,"200",day,0,0,0);
        try(var cache=cache();var access=access()){
            cache.read(key(1,0),access,options(false),owned->snapshot(1),()->true);
            cache.read(other,access,options(false),owned->snapshot(2),()->true);
            var first=callers.submit(()->cache.read(key(1,0),access,options(false),owned->{throw new AssertionError();},()->{
                entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return true;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var second=callers.submit(()->cache.read(other,access,options(false),owned->{throw new AssertionError();},()->true));
            assertTrue(second.get(1,TimeUnit.SECONDS).hit());
            callers.submit(()->cache.invalidate(connection)).get(1,TimeUnit.SECONDS);
            release.countDown();
            assertEquals(CrmFailure.Code.CONTEXT_MISMATCH,assertInstanceOf(CrmFailure.class,assertThrows(ExecutionException.class,()->first.get(2,TimeUnit.SECONDS)).getCause()).code());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void slowPublicationCheckDoesNotBlockOtherKeysAndCannotPublishAfterInvalidation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);var checks=new AtomicInteger();
        var other=new DikidiDayCache.Key(UUID.randomUUID(),UUID.randomUUID(),1,"200",day,0,0,0);
        try(var cache=cache();var access=access()){
            cache.read(other,access,options(false),owned->snapshot(2),()->true);
            var first=callers.submit(()->cache.read(key(1,0),access,options(false),owned->snapshot(1),()->{
                if(checks.incrementAndGet()==2){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}return true;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTrue(callers.submit(()->cache.read(other,access,options(false),owned->{throw new AssertionError();},()->true)).get(1,TimeUnit.SECONDS).hit());
            callers.submit(()->cache.invalidate(connection)).get(1,TimeUnit.SECONDS);
            assertInstanceOf(CrmFailure.class,assertThrows(ExecutionException.class,()->first.get(1,TimeUnit.SECONDS)).getCause());
            release.countDown();assertEquals(1,cache.diagnostics().entries());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void deadlineIsRecheckedAfterContextValidationForHitsAndFreshResults(){
        try(var cache=cache();var access=access()){
            cache.read(key(1,0),access,options(false),owned->snapshot(1),()->true);
            var expiredHit=assertThrows(CrmFailure.class,()->cache.read(key(1,0),access,options(false),owned->{throw new AssertionError();},()->{time.now=time.now.plusSeconds(6);return true;}));
            assertEquals(CrmFailure.Code.TEMPORARY_FAILURE,expiredHit.code());assertEquals(0,cache.diagnostics().hits());
            var checks=new AtomicInteger();
            var expiredFresh=assertThrows(CrmFailure.class,()->cache.read(key(2,0),access,options(false),owned->snapshot(2),()->{
                if(checks.incrementAndGet()==3)time.now=time.now.plusSeconds(6);return true;
            }));
            assertEquals(CrmFailure.Code.TEMPORARY_FAILURE,expiredFresh.code());
        }
    }
    @Test void disabledReuseStillSharesAnActiveSourceAndPreservesItsErrorOriginAfterInitiatorCancellation() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);var reads=new AtomicInteger();
        var health=new CrmSourceHealth(connection,1,5);
        try(var cache=new DikidiDayCache(Duration.ofSeconds(30),2,4096,2048,time,false);var first=access();var second=access()){
            java.util.function.Function<CrmAccess,DikidiDayCache.Snapshot> load=owned->{
                reads.incrementAndGet();started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                throw new CrmFailure(CrmFailure.Code.AUTH_REQUIRED).withSourceHealth(health);
            };
            var a=callers.submit(()->cache.read(key(1,0),first,options(false),load,()->true));assertTrue(started.await(2,TimeUnit.SECONDS));
            a.cancel(true);first.close();
            var b=callers.submit(()->cache.read(key(1,0),second,options(false),load,()->true));
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(cache.diagnostics().joins()==0&&System.nanoTime()<limit)Thread.yield();assertEquals(1,cache.diagnostics().joins());
            release.countDown();var error=assertInstanceOf(CrmFailure.class,assertThrows(ExecutionException.class,()->b.get(2,TimeUnit.SECONDS)).getCause());
            assertTrue(error.journalOrigin());assertEquals(health,error.sourceHealth());assertEquals(1,reads.get());assertEquals(0,cache.diagnostics().entries());
            for(int index=0;index<2;index++)assertFalse(cache.read(key(1,0),second,options(false),owned->snapshot(10),()->true).hit());
            assertEquals(0,cache.diagnostics().entries());
        }finally{release.countDown();callers.shutdownNow();}
    }
}
