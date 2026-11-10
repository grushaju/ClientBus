package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiRequestsTest {
    static final Instant NOW=Instant.parse("2026-11-10T10:00:00Z");
    static final class Time extends Clock {
        Instant now=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void parsesSecondsDateAndConservativeMinimumWithoutAcceptingInvalidHeaders(){
        assertEquals(NOW.plusSeconds(120),DikidiRequests.parseRetryAfter("120",NOW));
        assertEquals(NOW.plusSeconds(120),DikidiRequests.parseRetryAfter("Tue, 10 Nov 2026 10:02:00 GMT",NOW));
        assertEquals(NOW.plusSeconds(1),DikidiRequests.parseRetryAfter("0",NOW));
        assertEquals(NOW.plusSeconds(1),DikidiRequests.parseRetryAfter("Mon, 09 Nov 2026 10:00:00 GMT",NOW));
        for(var bad:List.of("-1","later","9999999999999999999999"))assertNull(DikidiRequests.parseRetryAfter(bad,NOW));
    }
    @Test void sharedPauseSurvivesNewLimiterAndShorterLateResponseCannotShortenIt(){
        var port=DikidiRequests.memory();var clock=new Time();var id=UUID.randomUUID();
        var requests=new DikidiRequests(DikidiRequests.Settings.defaults(),port,clock);
        var failure=assertThrows(CrmFailure.class,()->requests.response(id,new DikidiTransport.Response(429,"","120")));
        assertEquals(CrmFailure.Code.RATE_LIMITED,failure.code());assertEquals(NOW.plusSeconds(120),failure.retryAt());
        assertFalse(failure.notDispatched());
        var restarted=new DikidiRequests(DikidiRequests.Settings.defaults(),port,clock);
        var paused=assertThrows(CrmFailure.class,()->restarted.acquire(id));
        assertEquals(CrmFailure.Code.COOLDOWN,paused.code());assertTrue(paused.notDispatched());
        assertThrows(CrmFailure.class,()->requests.response(id,new DikidiTransport.Response(429,"","1")));
        assertEquals(NOW.plusSeconds(120),restarted.retryAt(id));
        restarted.acquire(UUID.randomUUID());
        clock.now=NOW.plusSeconds(121);restarted.acquire(id);assertNull(restarted.retryAt(id));
        assertEquals(0,restarted.diagnostics().sent());
    }
    @Test void localBudgetIsFailFastAndDoesNotPersistAnExternalPause(){
        var port=DikidiRequests.memory();var clock=new Time();var id=UUID.randomUUID();
        var requests=new DikidiRequests(new DikidiRequests.Settings(1,1,2,Duration.ofSeconds(10),Duration.ofSeconds(20)),port,clock);
        requests.acquire(id);requests.dispatched();
        var busy=assertThrows(CrmFailure.class,()->requests.acquire(id));
        assertEquals(CrmFailure.Code.CONNECTION_BUSY,busy.code());assertTrue(busy.notDispatched());assertEquals(NOW.plusSeconds(1),busy.retryAt());
        assertNull(requests.retryAt(id));assertEquals(CrmTrafficPort.Pause.NONE,port.current(id));
        clock.now=NOW.plusSeconds(1);requests.acquire(id);
        assertEquals(1,requests.diagnostics().sent());assertEquals(1,requests.diagnostics().localRefusals());
    }
    @Test void invalidRetryAfterUsesBoundedBackoffAndSuccessDoesNotClearPause(){
        var port=DikidiRequests.memory();var clock=new Time();var id=UUID.randomUUID();
        var requests=new DikidiRequests(new DikidiRequests.Settings(1,1,1,Duration.ofSeconds(10),Duration.ofSeconds(20)),port,clock);
        for(int index=0;index<10;index++){
            var error=assertThrows(CrmFailure.class,()->requests.response(id,new DikidiTransport.Response(429,"","invalid")));
            assertFalse(error.retryAt().isAfter(NOW.plusSeconds(20)));
        }
        requests.response(id,new DikidiTransport.Response(200,""));assertEquals(NOW.plusSeconds(20),requests.retryAt(id));
        assertEquals(10,requests.diagnostics().external429());
    }
    @Test void globalBudgetBoundsDifferentConnectionsWithoutPersistingCooldown(){
        var clock=new Time();var port=DikidiRequests.memory();
        var requests=new DikidiRequests(new DikidiRequests.Settings(100,100,10,Duration.ofSeconds(10),Duration.ofSeconds(20),1,1),port,clock);
        requests.acquire(UUID.randomUUID());var second=UUID.randomUUID();
        assertEquals(CrmFailure.Code.CONNECTION_BUSY,assertThrows(CrmFailure.class,()->requests.acquire(second)).code());assertNull(requests.retryAt(second));
        clock.now=NOW.plusSeconds(1);requests.acquire(second);
    }
}
