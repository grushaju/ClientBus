package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import kit.penny.clientbus.integration.dikidi.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiClientCreationTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final CrmClientDraft DRAFT=new CrmClientDraft("Anna","Surname","+7 (999) 123-45-67");
    @Test void ownerFormHasOneSelectedPhoneAndNoChatOrInvitationFields() {
        var form=DikidiClientCreation.form("<form method='post' action='/ru/owner/ajax/clients/save/?company=42'><input name='name' required><input name='surname'><input name='number' required></form>","42",DRAFT);
        assertTrue(form.contains(Map.entry("number","79991234567")));
        assertEquals(1,form.stream().filter(e->e.getKey().equals("number")).count());
        assertFalse(form.stream().anyMatch(e->e.getKey().contains("notify")||e.getKey().contains("invite")));
    }
    @Test void unexpectedRequiredFieldOrDifferentProjectPreventsWrite() {
        assertThrows(CrmFailure.class,()->DikidiClientCreation.form("<form method='get' action='/ru/owner/ajax/clients/save/?company=42'><input name='name'><input name='surname'><input name='number'></form>","42",DRAFT));
        assertThrows(CrmFailure.class,()->DikidiClientCreation.form("<form method='post' action='/ru/owner/ajax/clients/save/?company=99'><input name='name'><input name='surname'><input name='number'></form>","42",DRAFT));
        assertThrows(CrmFailure.class,()->DikidiClientCreation.form("<form method='post' action='/ru/owner/ajax/clients/save/?company=42'><input name='name'><input name='surname'><input name='number'><input name='consent' required></form>","42",DRAFT));
    }
    @Test void bareModalIdAndSearchHintsNeverProveCreation() {
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.readTree("{\"id\":123,\"dialog\":{\"id\":123}}"),DRAFT));
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.readTree("{\"id\":123,\"name\":\"Anna\",\"surname\":\"Surname\",\"phone\":\"79991234567\",\"is_system\":1}"),DRAFT));
    }
    @Test void structuredProjectClientMustMatchTheConfirmedContactSnapshot() {
        var result=DikidiClientCreation.created(JSON.readTree("{\"client\":{\"id\":123,\"name\":\"Anna\",\"surname\":\"Surname\",\"phone\":\"79991234567\",\"is_system\":0}}"),DRAFT);
        assertEquals("123",result.externalId());assertEquals("PROJECT",result.identityKind());
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.readTree("{\"client\":{\"id\":123,\"name\":\"Anna\",\"surname\":\"Surname\",\"phone\":\"79990000000\",\"is_system\":0}}"),DRAFT));
    }
    @Test void conflictingSaveIdentitiesNeverProveCreation() {
        var client=Map.of("id",123,"name","Anna","surname","Surname","phone","79991234567","is_system",0);
        var other=new HashMap<String,Object>(client);other.put("id",456);
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.valueToTree(Map.of("client",client,"data",other)),DRAFT));
        String row="<tr id='item-456'><td class='line-name'><a>Anna Surname</a></td><td class='line-phone'>79991234567</td></tr>";
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.valueToTree(Map.of("client",client,"html",row)),DRAFT));
        other.put("phone","79990000000");
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.valueToTree(Map.of("client",client,"data",other)),DRAFT));
        assertEquals("123",DikidiClientCreation.created(JSON.valueToTree(Map.of("client",client,"data",client)),DRAFT).externalId());
    }
    @Test void observedOwnerSaveUsesClientInfoRatherThanTheRefreshedDirectory() {
        var profile=Map.of("id","123","name","Anna","surname","Surname","phone","+7 (999) 123-45-67");
        var response=JSON.valueToTree(Map.of("client_info",profile,"callback",List.of("backend.modalClose(\"new-client\")","$(\"body\").trigger(\"clients.insert\", data.client_info)"),"container",".main-pane","html","<div>Refreshed directory with unrelated clients</div>"));
        assertEquals("123",DikidiClientCreation.created(response,DRAFT).externalId());
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.valueToTree(Map.of("client_info",profile)),DRAFT));
        var other=new HashMap<String,Object>(profile);other.put("phone","79990000000");
        assertThrows(CrmFailure.class,()->DikidiClientCreation.created(JSON.valueToTree(Map.of("client_info",other,"callback",List.of("$(\"body\").trigger(\"clients.insert\", data.client_info)"))),DRAFT));
    }
    @Test void explicitPhoneRejectionIsNotAnAccessFailureAndDoesNotRetry() {
        var writes=new AtomicInteger();
        try(var provider=provider(writes,JSON.writeValueAsString(Map.of("error",1,"message","Видимо вы неверно ввели номер")),false);
            var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            var error=assertThrows(CrmFailure.class,()->provider.createClient(access,"100",DRAFT,Instant.now().plusSeconds(30),c->fail()));
            assertEquals(CrmFailure.Code.CLIENT_PHONE_INVALID,error.code());assertFalse(error.notDispatched());assertEquals(1,writes.get());
        }
    }
    @Test void providerNeverRetriesAClientSaveAfterAnAuthenticationResponse() {
        var writes=new AtomicInteger();
        try(var provider=provider(writes,"{\"error\":1,\"message\":\"auth required\"}",false);
            var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            var error=assertThrows(CrmFailure.class,()->provider.createClient(access,"100",DRAFT,Instant.now().plusSeconds(30),c->fail("No confirmed identity")));
            assertFalse(error.notDispatched());assertEquals(1,writes.get());
        }
    }
    @Test void providerRejectsChangedFormBeforeSaveAndCommitsOnlyTheSaveResponseIdentity() {
        var writes=new AtomicInteger();
        try(var provider=provider(writes,"{}",true);var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            var error=assertThrows(CrmFailure.class,()->provider.createClient(access,"100",DRAFT,Instant.now().plusSeconds(30),c->fail()));
            assertTrue(error.notDispatched());assertEquals(0,writes.get());
        }
        String row="<tr id='item-123'><td class='line-name'><a>Anna Surname</a></td><td class='line-phone'>79991234567</td></tr>";
        try(var provider=provider(writes,JSON.writeValueAsString(Map.of("html",row)),false);var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            var ids=new ArrayList<String>();provider.createClient(access,"100",DRAFT,Instant.now().plusSeconds(30),c->ids.add(c.externalId()));
            assertEquals(List.of("123"),ids);assertEquals(1,writes.get());
        }
    }
    @Test void externalSearchCannotOverwriteAuthoritativeStatisticsWithListCounters() {
        var writes=new AtomicInteger();
        String row="<tr id='item-123'><td class='line-name'><a>Anna Surname</a></td><td class='line-phone'>79991234567</td><td class='appointments'>1</td><td class='spent'>2</td></tr>";
        try(var provider=provider(writes,JSON.writeValueAsString(Map.of("html",row)),false);var access=new CrmAccess(UUID.randomUUID(),1,"70000000000","test-secret".toCharArray())) {
            var result=provider.findClients(access,"100","79991234567",Instant.now().plusSeconds(30)).items().getFirst();
            assertNull(result.visitCount());assertNull(result.totalSpent());
            assertFalse(result.presentFields().contains("visitCount"));assertFalse(result.presentFields().contains("totalSpent"));
        }
    }
    private static DikidiProvider provider(AtomicInteger writes,String saveResponse,boolean changedForm) {
        var login=new ArrayDeque<>(List.of("<input name='telegram_csrf' type='hidden' value='first'>",
                "{\"html\":\"<input name='csrf' type='hidden' value='second'>\"}","{\"callback\":[\"sw.auth.complete();\"]}"));
        return new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(10),65536),
                ()->(method,uri,form,ajax,project,timeout)->{
                    String path=uri.getPath();
                    if(!login.isEmpty())return new DikidiTransport.Response(200,login.removeFirst());
                    if(path.endsWith("/journal/"))return new DikidiTransport.Response(200,"journal");
                    if(path.endsWith("/journal/api/"))return new DikidiTransport.Response(200,"{\"company\":{\"accesses\":{\"100\":{\"actor\":{\"id\":\"actor\"}}}},\"masters\":{\"info\":{\"ids\":[],\"data\":[]}}}");
                    if(path.endsWith("/clients/filter/"))return new DikidiTransport.Response(200,saveResponse);
                    if(path.endsWith("/clients/add/"))return new DikidiTransport.Response(200,JSON.writeValueAsString(Map.of("html",
                            "<form method='post' action='/ru/owner/ajax/clients/save/?company=100'><input name='name' required><input name='surname'><input name='number' required>"+(changedForm?"<input name='unknown' required>":"")+"</form>")));
                    if(path.endsWith("/clients/save/")){writes.incrementAndGet();return new DikidiTransport.Response(200,saveResponse);}
                    throw new AssertionError("Unexpected provider endpoint");
                });
    }
}
