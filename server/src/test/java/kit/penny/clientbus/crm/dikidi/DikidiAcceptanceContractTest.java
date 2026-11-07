package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DikidiAcceptanceContractTest {
    private static final CrmClientReference CLIENT=new CrmClientReference("PROJECT","1");
    private static CrmAppointment appointment(String kind,CrmClientReference client) {
        var start=LocalDateTime.of(2026,11,1,10,0);
        return new CrmAppointment("1",kind,client,Map.of(),start,start.plusHours(1),"UNKNOWN","UNKNOWN","UNKNOWN",
                Map.of(),null,List.of(),List.of(),false,false,Set.of(),"JOURNAL");
    }
    @Test void matchingAndExplicitlyEmptyHistoryAreAccepted() {
        var row=appointment("APPOINTMENT",CLIENT);
        CrmDikidiReadAcceptance.verifyClientHistory(Map.of("1",row),Map.of("1",row),CLIENT);
        CrmDikidiReadAcceptance.verifyClientHistory(Map.of(),Map.of(),CLIENT);
    }
    @Test void missingOrExtraVisitsFailInsteadOfPrintingAFalseSuccess() {
        var row=appointment("APPOINTMENT",CLIENT);
        assertThrows(AssertionError.class,() -> CrmDikidiReadAcceptance.verifyClientHistory(Map.of("1",row),Map.of(),CLIENT));
        assertThrows(AssertionError.class,() -> CrmDikidiReadAcceptance.verifyClientHistory(Map.of(),Map.of("1",row),CLIENT));
    }
    @Test void foreignClientsAndNonAppointmentRecordsFailEvenWhenIdsMatch() {
        var journal=Map.of("1",appointment("APPOINTMENT",CLIENT));
        assertThrows(AssertionError.class,() -> CrmDikidiReadAcceptance.verifyClientHistory(journal,
                Map.of("1",appointment("APPOINTMENT",new CrmClientReference("PROJECT","2"))),CLIENT));
        assertThrows(AssertionError.class,() -> CrmDikidiReadAcceptance.verifyClientHistory(journal,
                Map.of("1",appointment("BLOCK",CLIENT)),CLIENT));
    }
}
