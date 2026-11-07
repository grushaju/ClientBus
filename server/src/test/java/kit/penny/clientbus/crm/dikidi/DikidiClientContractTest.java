package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiClientContractTest {
    private static final String ROW="<tr id='item-123'><td class='line-name'><a>Long &amp; Name</a><span>VIP</span></td><td class='line-phone'>+7 (999) 123-45-67</td></tr>";
    @Test void htmlExtractsOnlyReviewedFieldsWithoutBadgesOrExecutingLinks() {
        var row=DikidiClientsHtml.parse(ROW).getFirst();
        assertEquals("123",row.externalId()); assertEquals("PROJECT",row.identityKind());
        assertEquals("Long & Name",row.displayName());
        assertEquals(Set.of("displayName","phone"),row.presentFields());
        assertEquals("79991234567",CrmPhone.normalize(row.contacts().get("phone")));
    }
    @Test void missingPhoneIsDifferentFromExplicitlyEmptyPhoneAndLongNamesArePreserved() {
        String name="Name".repeat(100);
        var rows=DikidiClientsHtml.parse("<tr id='item-1'><td class='line-name'><a>"+name+"</a></td></tr>");
        assertEquals(name,rows.getFirst().displayName());
        assertEquals(Set.of("displayName"),rows.getFirst().presentFields());
        assertEquals("",DikidiClientsHtml.parse(ROW.replace("+7 (999) 123-45-67", "")).getFirst().contacts().get("phone"));
    }
    @Test void malformedAmbiguousOrSystemIdentityFailsEvenWhenHtmlParserSwallowsCallbacks() {
        for (String html : List.of(ROW+ROW,ROW.replace("item-123","item-bad"),ROW.replace("Long &amp; Name",""),
                ROW.replace("id='item-123'","id='item-123' data-is-system='1'")))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,()->DikidiClientsHtml.parse(html)).code());
    }
    @Test void phoneNormalizationDoesNotGuessCountryOrAcceptExtensionText() {
        assertEquals("89991234567",CrmPhone.normalize("8 (999) 123-45-67"));
        assertNotEquals(CrmPhone.normalize("89991234567"),CrmPhone.normalize("+79991234567"));
        assertNull(CrmPhone.normalize("+79991234567 ext 4")); assertNull(CrmPhone.normalize("123"));
    }
    @Test void importedPhoneWithHtmlAndUnicodeSpacesIsStillMatchable() {
        var row=DikidiClientsHtml.parse(ROW.replace("+7 (999) 123-45-67", "+7&nbsp;(999)&#8239;123&#8201;45-67")).getFirst();
        assertEquals("79991234567",CrmPhone.normalize(row.contacts().get("phone")));
        assertNull(CrmPhone.normalize("+7\u00a0(999) 123-45-67 ext 4"));
    }
    private CrmClientPage read(String response, String cursor) {
        var responses=new ArrayDeque<>(List.of("<input name='telegram_csrf' type='hidden' value='first'>",
                "{\"html\":\"<input name='csrf' type='hidden' value='second'>\"}",
                "{\"callback\":[\"sw.auth.complete();\"]}","journal",
                "{\"company\":{\"accesses\":{\"100\":{\"actor\":{\"id\":\"actor\"}}}},\"masters\":{\"info\":{\"ids\":[],\"data\":[]}}}",response));
        try (var provider=new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(15),65536),
                () -> (method,uri,form,ajax,project,timeout) -> {
                    if (uri.getPath().endsWith("/clients/filter/")) {
                        assertEquals("POST",method); assertEquals("100",project);
                        assertTrue(form.contains(Map.entry("offset",cursor==null ? "0" : cursor)));
                        assertTrue(form.contains(Map.entry("limit","50")));
                    }
                    return new DikidiTransport.Response(200,responses.removeFirst());
                }); var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            return provider.readClients(access,"100",cursor);
        }
    }
    @Test void offsetPagesRemainUnknownAndOnlyExplicitEmptyHtmlTerminates() {
        var page=read("{\"script\":[{\"html\":\""+ROW+"\"}]}","50");
        assertEquals("51",page.nextCursor()); assertEquals("100:CLIENTS",page.scope());
        assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
        assertNull(read("{\"html\":\"\"}",null).nextCursor());
        for (String response : List.of("{}","{\"html\":\"<div>Login</div>\"}","{\"script\":[{\"html\":\"\"},{\"html\":\"\"}]}",
                "{\"html\":\"\",\"script\":[{\"html\":\""+ROW+"\"}]}"))
            assertThrows(CrmFailure.class,()->read(response,null));
    }
    @Test void targetedClientRowsIgnoreTheSeparateCountUpdateWithoutExecutingCallbacks() {
        var page=read("{\"script\":[{\"append\":\".company-clients tbody\",\"html\":\""+ROW+"\"},"
                +"{\"container\":\".company-clients .count-items\",\"html\":\"<b>1</b>\"},{\"callback\":\"throw new Error('never execute')\"}]}",null);
        assertEquals(1,page.items().size()); assertEquals("123",page.items().getFirst().externalId());
        assertEquals("1",page.nextCursor()); assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
    }
    @Test void explicitEmptyTargetedRowsTerminateEvenWhenTheCountFragmentIsNotEmpty() {
        var page=read("{\"script\":[{\"container\":\".company-clients .count-items\",\"html\":\"<b>1</b>\"},"
                +"{\"append\":\".company-clients tbody\",\"html\":\"\\n  \"}]}","1");
        assertTrue(page.items().isEmpty()); assertNull(page.nextCursor());
        assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
    }
    @Test void unknownConflictingOrMissingRowTargetsNeverBecomeSuccessfulOrEmptyPages() {
        for (String scripts:List.of(
                "{\"append\":\".other-table tbody\",\"html\":\""+ROW+"\"}",
                "{\"container\":\".company-clients .count-items\",\"html\":\"\"}",
                "{\"container\":\".other-table .count-items\",\"html\":\"\"}",
                "{\"append\":\".company-clients tbody\",\"html\":\"\"},{\"html\":\"\"}",
                "{\"append\":\".company-clients tbody\",\"html\":\"\"},{\"append\":\".company-clients tbody\",\"html\":\"\"}",
                "{\"append\":\".company-clients tbody\",\"container\":\".company-clients .count-items\",\"html\":\"\"}"))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,()->read("{\"script\":["+scripts+"]}",null)).code());
    }
    @Test void reviewedNoResultsRowTerminatesTheOffsetListWithoutClaimingCompleteness() {
        for (String attributes:List.of("class='red text-center' colspan='10'","colspan='10' class='red text-center'")) {
            var page=read("{\"script\":[{\"append\":\".company-clients tbody\",\"html\":\"<tr><td "+attributes+">Ничего не найдено</td></tr>\"},"
                    +"{\"container\":\".company-clients .count-items\",\"html\":\"1\"}]}","1");
            assertTrue(page.items().isEmpty()); assertNull(page.nextCursor());
            assertEquals(CrmReadResult.Completeness.UNKNOWN,page.completeness());
        }
    }
    @Test void errorsAndOtherEmptyMarkupCannotMasqueradeAsTheReviewedTerminalRow() {
        for (String html:List.of("<tr><td class='red text-center' colspan='10'>Недостаточно прав</td></tr>",
                "<tr><td>Ничего не найдено</td></tr>","<div>Ничего не найдено</div>",
                "<tr><td class='red text-center' colspan='10'>Ничего не найдено</td></tr><div>Unexpected content</div>"))
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,()->read("{\"html\":\""+html+"\"}","1")).code());
    }
    @Test void emptyMarkerRequiresRealClassAndColspanAttributes() {
        for (String attributes:List.of("data-class='red text-center' data-colspan='10'",
                "class='red text-center' data-colspan='10'","data-class='red text-center' colspan='10'",
                "title=\"class='red text-center' colspan='10'\"","class='red text-center' colspan='0'"))
            assertFalse(DikidiClientsHtml.emptyPage("<tr><td "+attributes+">Ничего не найдено</td></tr>"));
        for (String html:List.of("<tr><td class='red text-center' colspan='10'>Ничего не найдено",
                "<tr><td class='red text-center' colspan='10'>Ничего не найдено</td></tr><!-- extra -->",
                "<tr><td class='red text-center' colspan='10'><script>ignored()</script>Ничего не найдено</td></tr>"))
            assertFalse(DikidiClientsHtml.emptyPage(html));
    }
}
