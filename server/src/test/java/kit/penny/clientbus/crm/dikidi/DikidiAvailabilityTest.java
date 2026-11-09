package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiAvailabilityTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final LocalDate DAY=LocalDate.of(2030,1,10);
    private static final Instant OBSERVED=Instant.parse("2030-01-01T00:00:00Z");
    private static CrmAvailability parse(String body) { return DikidiAvailability.parse(JSON.readTree(body),"100","200",List.of("300"),DAY,OBSERVED); }
    @Test void explicitStartsKeepScopeAndOriginalObservation() {
        var result=parse("""
                {"appointments":{},"timeworks":{},"dates_true":["2030-01-10","2030-01-17"],
                 "times":{"200":["2030-01-10 12:00:00","2030-01-10 10:00:00","2030-01-10 12:00:00"]}}
                """);
        assertEquals(List.of(DAY.atTime(10,0),DAY.atTime(12,0)),result.starts());
        assertEquals("100",result.externalProjectId());assertEquals("200",result.externalMasterId());
        assertEquals(List.of("300"),result.externalServiceIds());assertEquals(DAY,result.date());assertEquals(OBSERVED,result.observedAt());
    }
    @Test void workingHoursAndAppointmentsNeverCreateAvailability() {
        for(String times:List.of("{}","[]","{\"200\":[]}")) assertTrue(parse("{\"appointments\":{\"200\":[{}]},\"timeworks\":{\"2030-01-10\":[{}]},\"dates_true\":[\"2030-01-10\"],\"times\":"+times+"}").starts().isEmpty());
    }
    @Test void anotherMasterIsNotAnEmptySuccessfulResult() {
        assertEquals(CrmFailure.Code.CONTEXT_MISMATCH,assertThrows(CrmFailure.class,()->parse("{\"appointments\":{},\"timeworks\":{},\"dates_true\":[],\"times\":{\"201\":[]}}")).code());
    }
    @Test void wrongDateAndUnadvertisedDateFailClosed() {
        for(String body:List.of(
                "{\"appointments\":{},\"timeworks\":{},\"dates_true\":[\"2030-01-11\"],\"times\":{\"200\":[\"2030-01-11 12:00:00\"]}}",
                "{\"appointments\":{},\"timeworks\":{},\"dates_true\":[],\"times\":{\"200\":[\"2030-01-10 12:00:00\"]}}"))
            assertEquals(CrmFailure.Code.CONTEXT_MISMATCH,assertThrows(CrmFailure.class,()->parse(body)).code());
    }
    @Test void malformedAndMissingSectionsAreNotEmptySuccess() {
        for(String body:List.of("{}","{\"error\":1}","{\"appointments\":{},\"timeworks\":{},\"dates_true\":[],\"times\":null}",
                "{\"appointments\":{},\"timeworks\":{},\"dates_true\":[],\"times\":{\"200\":\"12:00\"}}",
                "{\"appointments\":{},\"timeworks\":{},\"dates_true\":[\"2030-01-10\"],\"times\":{\"200\":[\"2030-01-10 25:00:00\"]}}"))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,()->parse(body)).code());
    }
}
