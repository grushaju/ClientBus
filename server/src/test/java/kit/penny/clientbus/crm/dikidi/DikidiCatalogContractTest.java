package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiCatalogContractTest {
    private static final String CATALOG = """
            {"company":{"accesses":{"100":{"actor":{"id":"actor"}}}},"masters":{
              "info":{"ids":["m1","m2"],"data":{
                "m1":{"id":"m1","item_type":"master","is_master":"1","title":"One"},
                "m2":{"id":"m2","item_type":"master","is_master":"1","title":"Two"}}},
              "services":{"ids":["m1","m2"],"data":{
                "m1":{"s1":{"id":"s1","master_id":"m1","title":"Service","cost":"1500.25","time":"30.5",
                    "break_duration":"5","active":"1","online":"0","floating":"1","description":"Description"}},
                "m2":{"s1":{"id":"s1","master_id":"m2","title":"Service variant","cost":1900,"time":45,"active":true,"online":false}}}}}}
            """;
    private record Request(String address, List<Map.Entry<String, String>> form) {}
    private final List<Request> requests = new ArrayList<>();
    private CrmCatalogSnapshot read(String response) {
        var responses = new ArrayDeque<>(List.of("<input name='telegram_csrf' type='hidden' value='first'>",
                "{\"html\":\"<input name='csrf' type='hidden' value='second'>\"}",
                "{\"callback\":[\"sw.auth.complete();\"]}", "journal", response));
        try (var provider = new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536),
                () -> (method, uri, form, ajax, project, timeout) -> {
                    requests.add(new Request(uri.getPath(), form == null ? List.of() : List.copyOf(form)));
                    return new DikidiTransport.Response(200, responses.removeFirst());
                }); var access = new CrmAccess(UUID.randomUUID(), 1, "70000000000", "test-secret".toCharArray())) {
            return provider.readCatalog(access, "100");
        }
    }
    @Test void mastersServicesAndOffersComeFromOneR01WithMasterSpecificValues() {
        var result = read(CATALOG);
        assertEquals("100:CATALOG", result.scope()); assertEquals(CrmReadResult.Completeness.COMPLETE, result.completeness());
        assertEquals(2, result.masters().size()); assertEquals(1, result.services().size()); assertEquals(2, result.offers().size());
        assertEquals("Service", result.services().getFirst().displayName()); assertNull(result.services().getFirst().category());
        var first = result.offers().getFirst(); var second = result.offers().getLast();
        assertEquals(new BigDecimal("1500.2500"), first.price()); assertEquals(1830L, first.durationSeconds());
        assertEquals(300L, first.attributes().get("breakDurationSeconds")); assertEquals(true, first.attributes().get("floating"));
        assertEquals(Map.of("active", true, "online", false), first.offerState());
        assertEquals(Map.of("active", true, "online", false), second.offerState());
        assertEquals("Service variant", second.attributes().get("title")); assertEquals(new BigDecimal("1900.0000"), second.price());
        assertEquals(2700L, second.durationSeconds()); assertNull(first.currency());
        assertEquals(Set.of("price", "durationSeconds"), first.presentFields());
        assertEquals(1, requests.stream().filter(r -> r.address().equals("/ru/owner/ajax/journal/api/")).count());
        assertEquals(List.of("company.accesses", "masters.info", "masters.services"), requests.getLast().form().stream()
                .filter(e -> e.getKey().equals("request[get][]")).map(Map.Entry::getValue).toList());
    }
    @Test void missingAndExplicitUnknownFieldsHaveDifferentPresence() {
        var result = read(CATALOG.replace("\"cost\":\"1500.25\",\"time\":\"30.5\",", "")
                .replace("\"cost\":1900,\"time\":45", "\"cost\":null,\"time\":\"\""));
        assertNull(result.offers().getFirst().price()); assertNull(result.offers().getFirst().durationSeconds());
        assertTrue(result.offers().getFirst().presentFields().isEmpty());
        assertNull(result.offers().getLast().price()); assertNull(result.offers().getLast().durationSeconds());
        assertEquals(Set.of("price", "durationSeconds"), result.offers().getLast().presentFields());
    }
    @Test void emptyServiceSnapshotCanBeCompleteWithoutRemovingMasters() {
        var result = read(CATALOG.substring(0, CATALOG.indexOf("\"services\"")) + "\"services\":{\"ids\":[],\"data\":[]}}}");
        assertEquals(2, result.masters().size()); assertTrue(result.services().isEmpty()); assertTrue(result.offers().isEmpty());
        assertEquals(CrmReadResult.Completeness.COMPLETE, result.completeness());
    }
    @Test void missingServiceSectionOrConflictingReferencesNeverBecomeCompleteEmptyCatalog() {
        for (String response : List.of(
                CATALOG.replace("\"services\"", "\"missing\""),
                CATALOG.replace("\"master_id\":\"m1\"", "\"master_id\":\"other\""),
                CATALOG.replace("\"id\":\"s1\",\"master_id\":\"m1\"", "\"id\":\"other\",\"master_id\":\"m1\""),
                CATALOG.replace("\"services\":{\"ids\":[\"m1\",\"m2\"]", "\"services\":{\"ids\":[\"m1\",\"m1\"]")))
            assertThrows(CrmFailure.class, () -> read(response));
    }
    @Test void invalidMoneyDurationAndFlagsFailWithBoundedTypedErrors() {
        for (String value : List.of("-1", "1.00001", "1e100000000", "1000000000000000", "garbage"))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE, assertThrows(CrmFailure.class,
                    () -> read(CATALOG.replace("\"cost\":\"1500.25\"", "\"cost\":\""+value+"\""))).code());
        for (String value : List.of("-1", "0.00001", "1e100000000", "garbage"))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE, assertThrows(CrmFailure.class,
                    () -> read(CATALOG.replace("\"time\":\"30.5\"", "\"time\":\""+value+"\""))).code());
        assertEquals(CrmFailure.Code.INVALID_RESPONSE, assertThrows(CrmFailure.class,
                () -> read(CATALOG.replace("\"active\":\"1\"", "\"active\":\"unexpected\""))).code());
    }
}
