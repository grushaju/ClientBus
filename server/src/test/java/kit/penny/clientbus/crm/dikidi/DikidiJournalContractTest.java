package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DikidiJournalContractTest {
    @Test void sharedUnfilteredDayRetainsItsOriginalObservationAndNeedsNoLoginOnHit(){
        var sequence=new AtomicLong();var epoch=new AtomicLong();var project=UUID.randomUUID();
        var port=new CrmJournalPort(){
            public long epoch(UUID id){return epoch.get();}
            public long begin(UUID id,long expected){return sequence.incrementAndGet();}
            public void invalidate(UUID id){epoch.incrementAndGet();}
        };
        var day=LocalDate.of(2026,11,1);var query=new CrmAppointmentQuery(day,day.plusDays(6));
        try(var fixture=new DikidiJournalFixture(port);var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","fake-secret".toCharArray())){
            var options=new CrmJournalOptions(project,0,0,0,false,Instant.now(),Instant.now().plusSeconds(20));
            var origins=new HashMap<LocalDate,CrmAppointmentPage>();String cursor=null;
            do{
                var page=fixture.provider.readAppointments(access,"100",query,cursor,options);assertEquals(3,page.items().size());
                origins.put(page.observation().day(),page);cursor=page.nextCursor();
            }while(cursor!=null);
            assertEquals(7,fixture.dayLoads.get());assertEquals(17,fixture.physical.get());
            for(int client=1;client<=3;client++){
                cursor=null;var history=new CrmAppointmentQuery(day,day.plusDays(6),new CrmClientReference("PROJECT",String.valueOf(client)));
                do{
                    var page=fixture.provider.readClientHistory(access,"100",history,cursor,options);assertEquals(1,page.items().size());
                    var original=origins.get(page.observation().day());assertEquals(original.observedAt(),page.observedAt());assertEquals(original.observation().sequence(),page.observation().sequence());
                    assertTrue(page.observation().cacheHit());assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());cursor=page.nextCursor();
                }while(cursor!=null);
            }
            assertEquals(7,fixture.dayLoads.get());assertEquals(17,fixture.physical.get());assertEquals(21,fixture.provider.journalDiagnostics().hits());
            port.invalidate(project);
            assertEquals(CrmFailure.Code.CONTEXT_MISMATCH,assertThrows(CrmFailure.class,()->fixture.provider.readAppointments(access,"100",query,null,options)).code());
        }
    }
}
