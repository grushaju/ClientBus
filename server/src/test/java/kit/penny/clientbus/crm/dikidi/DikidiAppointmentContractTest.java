package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

class DikidiAppointmentContractTest {
    private static final LocalDate DAY=LocalDate.of(2026,11,1);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String INFO="""
            "client_id":"c1","client_is_system":"0","client_name":"Historical name","client_phone":"70000000000",
            "is_time_reservation":"0","is_group_session":"0","is_deleted":"0","status_id":"1","is_accept":"1","client_came_id":"2",
            "comment":"note","services":[{"id":"s1","title":"Historical service","duration":"60","cost":"1200.0000","currency":"RUB"},
            {"id":"s2","title":"Extra","time":"30"}]
            """;
    private static String row(String id,String master,String begin,String end,String info) {
        return "{\"id\":\""+id+"\",\"company_id\":\"100\",\"item_id\":\""+master+"\",\"item_type\":\"master\",\"date\":\"2026-11-01\",\"begin\":\""+begin+"\",\"end\":\""+end+"\",\"duration\":3600,\"info\":{"+info+"}}";
    }
    private static String data(String row) { return "{\"m1\":{\"2026-11-01\":{\"r1\":"+row+"}}}"; }
    private static String response(String records) {
        return "{\"company\":{\"accesses\":{\"100\":{\"actor\":{\"id\":\"actor\"}}}},\"masters\":{\"info\":{\"ids\":[\"m1\"],\"data\":{\"m1\":{\"id\":\"m1\",\"item_type\":\"master\",\"is_master\":\"1\",\"title\":\"Current master\"}}},\"records\":{\"data\":"+records+"}}}";
    }
    private static List<CrmAppointment> parse(String records) { return DikidiAppointments.parse(JSON.readTree(response(records)),"100",DAY); }
    private static CrmAppointment basic() { return parse(data(row("r1","m1","10:00:00","11:00:00",INFO))).getFirst(); }
    private record Call(URI uri,List<Map.Entry<String,String>> form) {}
    private static final class Fake implements DikidiTransport {
        final Deque<Response> responses=new ArrayDeque<>(); final List<Call> calls=new ArrayList<>();
        Fake(String body) {
            for (var value : List.of("<input type='hidden' name='telegram_csrf' value='csrf'>","{\"html\":\"<input type='hidden' name='csrf' value='step2'>\"}",
                    "{\"callback\":[\"sw.auth.complete();\"]}","journal",body)) responses.add(new Response(200,value));
        }
        public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout) {
            calls.add(new Call(uri,form)); return Objects.requireNonNull(responses.poll(),"Unexpected call");
        }
    }
    private static DikidiProvider provider(Fake fake) { return new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(15),65536),() -> fake); }
    private static CrmAccess access() { return new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray()); }
    @Test void journalUsesOneLocalDayAndNeverClaimsCompleteHistory() {
        var fake=new Fake(response(data(row("r1","m1","10:00:00","11:00:00",INFO))));
        var query=new CrmAppointmentQuery(DAY,DAY.plusDays(1));
        try (var adapter=provider(fake); var access=access()) {
            var page=adapter.readAppointments(access,"100",query,null);
            assertEquals(query,page.scope()); assertEquals("100",page.externalProjectId());
            assertEquals("2026-11-02",page.nextCursor()); assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
            assertEquals(1,page.items().size());
        }
        assertTrue(fake.calls.getLast().form().contains(Map.entry("request[options][masters][records][0][date_from]","2026-11-01")));
        assertTrue(fake.calls.getLast().form().contains(Map.entry("request[options][masters][records][0][date_to]","2026-11-01")));
        assertTrue(fake.calls.stream().noneMatch(call -> call.uri().getPath().contains("appointment_list") || call.uri().getPath().contains("newrecord")));
    }
    @Test void severalServicesKeepHistoricalTermsAndMissingPriceIsNotZero() {
        var item=basic(); assertEquals(2,item.services().size());
        assertEquals("Historical service",item.services().getFirst().displayName());
        assertEquals(0,new BigDecimal("1200").compareTo(item.services().getFirst().price()));
        assertEquals(3600L,item.services().getFirst().durationSeconds()); assertEquals(1800L,item.services().getLast().durationSeconds());
        assertNull(item.services().getLast().price()); assertFalse(item.services().getLast().presentFields().contains("price"));
        assertEquals(3600L,item.masters().getFirst().durationSeconds());
        assertEquals("SCHEDULED",item.appointmentState()); assertEquals("CONFIRMED",item.confirmationState()); assertEquals("NO_SHOW",item.attendanceState());
    }
    @Test void sameVisitUnderSeveralMastersIsMergedWithoutMultiplyingServices() {
        String info=INFO+",\"masters\":[{\"id\":\"m1\",\"record_from\":\"10:00:00\",\"record_to\":\"11:00:00\",\"duration\":60},{\"id\":\"m2\",\"record_from\":\"11:00:00\",\"record_to\":\"12:00:00\",\"duration\":60}]";
        String rows=data(row("r1","m1","10:00:00","11:00:00",info));
        rows=rows.substring(0,rows.length()-1)+",\"m2\":{\"2026-11-01\":{\"r1\":"+row("r1","m2","11:00:00","12:00:00",info)+"}}}";
        var items=parse(rows); assertEquals(1,items.size()); var item=items.getFirst();
        assertEquals(2,item.masters().size()); assertEquals(2,item.services().size());
        assertEquals(DAY.atTime(10,0),item.localStart()); assertEquals(DAY.atTime(12,0),item.localEnd()); assertTrue(item.mastersComplete());
    }
    @Test void clientHistoryFiltersTheJournalByProvenProjectIdentity() {
        var fake=new Fake(response(data(row("r1","m1","10:00:00","11:00:00",INFO))));
        var query=new CrmAppointmentQuery(DAY,DAY,new CrmClientReference("PROJECT","different-client"));
        try (var adapter=provider(fake); var access=access()) {
            var page=adapter.readClientHistory(access,"100",query,null);
            assertTrue(page.items().isEmpty()); assertNull(page.nextCursor()); assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
        }
    }
    @Test void partialClientAndKindFieldsDoNotAssertThatAKnownIdentityDisappeared() {
        var item=parse(data(row("r1","m1","10:00:00","11:00:00","\"client_name\":\"Updated\""))).getFirst();
        assertFalse(item.presentFields().contains("client")); assertFalse(item.presentFields().contains("kind"));
        assertTrue(item.presentFields().contains("clientName"));
    }
    @Test void fullMasterDetailsDefineTheVisitRangeEvenWhenOnlyOneOuterRowIsReturned() {
        var item=parse(data(row("r1","m1","10:00:00","11:00:00",INFO+",\"masters\":[{\"id\":\"m1\",\"record_from\":\"10:00:00\",\"record_to\":\"11:00:00\"},{\"id\":\"m2\",\"record_from\":\"11:00:00\",\"record_to\":\"12:00:00\"}]"))).getFirst();
        assertEquals(DAY.atTime(12,0),item.localEnd()); assertEquals(2,item.masters().size());
    }
    @Test void systemGroupAndUnknownKindsAreNeverGuessedAsIndividualAppointments() {
        var system=parse(data(row("r1","m1","10:00:00","11:00:00",INFO.replace("\"client_is_system\":\"0\"","\"client_is_system\":\"1\"")))).getFirst();
        assertNull(system.client()); assertEquals("APPOINTMENT",system.kind());
        var group=parse(data(row("r1","m1","10:00:00","11:00:00",INFO.replace("\"is_group_session\":\"0\"","\"is_group_session\":\"1\"")))).getFirst();
        assertNull(group.client()); assertEquals("UNKNOWN",group.kind());
        var unknown=parse(data(row("r1","m1","10:00:00","11:00:00","\"client_id\":\"0\""))).getFirst();
        assertEquals("UNKNOWN",unknown.kind()); assertEquals("UNKNOWN",unknown.appointmentState()); assertFalse(unknown.servicesComplete());
    }
    @Test void incompatibleIdentityTimeAndDecimalPayloadsFailClosed() {
        String valid=row("r1","m1","10:00:00","11:00:00",INFO);
        for (String bad : List.of(valid.replace("\"id\":\"r1\"","\"id\":\"r2\""),valid.replace("\"company_id\":\"100\"","\"company_id\":\"200\""),valid.replace("\"item_id\":\"m1\"","\"item_id\":\"m2\"")))
            assertEquals(CONTEXT_MISMATCH,assertThrows(CrmFailure.class,() -> parse(data(bad))).code());
        for (String bad : List.of(valid.replace("10:00:00","bad-time"),valid.replace("11:00:00","09:00:00"),valid.replace("1200.0000","1.23456"),valid.replace("1200.0000","-1")))
            assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> parse(data(bad))).code());
    }
    @Test void emptyJournalUnknownStatusesAndAbsentFieldsDoNotManufactureFacts() {
        for (String value : List.of("[]","{}","{\"m1\":[]}")) assertTrue(parse(value).isEmpty());
        var item=parse(data(row("r1","m1","10:00:00","11:00:00",INFO.replace("\"status_id\":\"1\"","\"status_id\":\"99\"")))).getFirst();
        assertEquals("UNKNOWN",item.appointmentState()); assertEquals("CONFIRMED",item.confirmationState());
        assertEquals("99",item.providerState().get("status_id"));
        assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> DikidiAppointments.parse(JSON.readTree("{}"),"100",DAY)).code());
    }
    @Test void invalidCursorIsRejectedBeforeAnyRemoteRequest() {
        var fake=new Fake(response("{}"));
        try (var adapter=provider(fake); var access=access()) {
            for (String cursor : List.of("bad","2026-10-31","2026-11-03"))
                assertEquals(INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> adapter.readAppointments(access,"100",new CrmAppointmentQuery(DAY,DAY.plusDays(1)),cursor)).code());
        }
        assertTrue(fake.calls.isEmpty());
    }
}
