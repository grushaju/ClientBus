package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

class DikidiScheduleContractTest {
    private record Call(URI uri,List<Map.Entry<String,String>> form) {}
    private static final String PREFIX="""
            {"company":{"accesses":{"100":{"actor":{"id":"actor"}}}},"masters":{
            "info":{"ids":["m1"],"data":{"m1":{"id":"m1","item_type":"master","is_master":"1","title":"Master"}}},
            "schedules":{"data":
            """;
    private static final class Fake implements DikidiTransport {
        final Deque<Response> responses=new ArrayDeque<>();
        final List<Call> calls=new ArrayList<>();
        Fake(String data) {
            for (var body : List.of("<input type='hidden' name='telegram_csrf' value='csrf'>","{\"html\":\"<input type='hidden' name='csrf' value='step2'>\"}",
                    "{\"callback\":[\"sw.auth.complete();\"]}","journal",data)) responses.add(new Response(200,body));
        }
        public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout) {
            calls.add(new Call(uri,form)); return Objects.requireNonNull(responses.poll(),"Unexpected call");
        }
    }
    private static CrmWorkIntervalQuery query() { return new CrmWorkIntervalQuery("m1",LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)); }
    private static DikidiProvider provider(Fake fake) { return new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(15),8192),() -> fake); }
    private static CrmAccess access() { return new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray()); }
    private static String response(String data) { return PREFIX+data+"}}}"; }
    private static String row(String from,String to,String pause) {
        return "{\"begin\":\"uninterpreted\",\"end\":\"uninterpreted\",\"work_from\":\""+from+"\",\"work_to\":\""+to+"\",\"break_from\":\""+pause+"\",\"break_to\":\""+pause+"\"}";
    }
    @Test void ownerSchedulePreservesDatedShiftsAndExactScopeWithoutInventingCompleteness() {
        var fake=new Fake(response("{\"m1\":{\"2026-11-02\":"+row("09:55:00","10:05:00","00:00:00")+"}}"));
        try (var adapter=provider(fake); var access=access()) {
            var result=adapter.readWorkIntervals(access,"100",query());
            assertEquals(query(),result.scope()); assertEquals("100",result.externalProjectId());
            assertEquals(CrmReadResult.Completeness.UNKNOWN,result.completeness());
            assertEquals(LocalDateTime.of(2026,11,2,9,55),result.items().getFirst().localStart());
            assertEquals(LocalDateTime.of(2026,11,2,10,5),result.items().getFirst().localEnd());
            assertEquals("WORK",result.items().getFirst().kind()); assertNull(result.items().getFirst().externalRef());
        }
        var form=fake.calls.getLast().form();
        assertTrue(form.contains(Map.entry("request[get][]","masters.schedules")));
        assertTrue(form.contains(Map.entry("request[options][masters][schedules][0][date_from]","2026-11-01")));
        assertTrue(form.contains(Map.entry("request[options][masters][schedules][0][date_to]","2026-11-30")));
        assertTrue(fake.calls.stream().noneMatch(call -> call.uri().getPath().contains("newrecord")));
    }
    @Test void emptyOwnerResponseNeverClearsAPreviousFullSnapshot() {
        for (String data : List.of("[]","{}","{\"m1\":[]}","{\"m1\":{}}")) {
            try (var adapter=provider(new Fake(response(data))); var access=access()) {
                var result=adapter.readWorkIntervals(access,"100",query());
                assertTrue(result.items().isEmpty()); assertEquals(CrmReadResult.Completeness.UNKNOWN,result.completeness());
            }
        }
    }
    @Test void lookaheadDatesDoNotExtendTheRequestedPeriod() {
        String data="{\"m1\":{\"2026-12-02\":"+row("10:00:00","11:00:00","")+"}}";
        try (var adapter=provider(new Fake(response(data))); var access=access()) {
            var result=adapter.readWorkIntervals(access,"100",query());
            assertTrue(result.items().isEmpty()); assertEquals(query(),result.scope());
        }
    }
    @Test void unprovenBreaksOvernightShiftsAndMultipleDailyRowsAreNotGuessed() {
        for (String value : List.of(row("23:00:00","01:00:00",""),row("10:00:00","11:00:00","10:30:00"))) {
            try (var adapter=provider(new Fake(response("{\"m1\":{\"2026-11-02\":"+value+"}}"))); var access=access()) {
                assertEquals(CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,() -> adapter.readWorkIntervals(access,"100",query())).code());
            }
        }
        try (var adapter=provider(new Fake(response("{\"m1\":{\"2026-11-02\":[]}}"))); var access=access()) {
            assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> adapter.readWorkIntervals(access,"100",query())).code());
        }
    }
    @Test void missingScheduleWrongMasterAndMalformedTimesFailClosed() {
        for (String data : List.of(response("{\"m1\":{\"bad-date\":{}}}"),response("{\"m1\":{\"2026-11-02\":"+row("broken","12:00:00","")+"}}"),
                response("{}").replace("\"schedules\"","\"other\""))) {
            try (var adapter=provider(new Fake(data)); var access=access()) {
                assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> adapter.readWorkIntervals(access,"100",query())).code());
            }
        }
        try (var adapter=provider(new Fake(response("{}").replace("\"m1\"","\"m2\""))); var access=access()) {
            assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> adapter.readWorkIntervals(access,"100",query())).code());
        }
    }
    @Test void conflictingEmbeddedMasterOrDateCannotBeAppliedUnderTheOuterKey() {
        for (String identity : List.of("\"master_id\":\"m2\",","\"date\":\"2026-11-03\",")) {
            String value="{"+identity+row("10:00:00","11:00:00","").substring(1);
            try (var adapter=provider(new Fake(response("{\"m1\":{\"2026-11-02\":"+value+"}}"))); var access=access()) {
                assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> adapter.readWorkIntervals(access,"100",query())).code());
            }
        }
    }
}
