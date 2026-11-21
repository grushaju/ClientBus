package kit.penny.clientbus.crm.dikidi;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.integration.dikidi.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiClientPhotoTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String URL="https://f2.dikidi.ru/c01/v00001/avatar123.jpg";
    static final String FORM="<form method='post' action='/ru/owner/ajax/clients/save/123/?company=100'><input name='name' value='Anna'><input name='surname' value='Surname'><input name='number' value='79991234567'><input name='email' value='person@example.test'><select name='source_id'><option value='0'>None</option><option value='12' selected>Selected</option></select><select name='sex'><option value='0'>None</option><option value='1'>One</option></select><textarea name='comment'>Keep &amp; preserve</textarea><input type='checkbox' name='is_blacklist' value='1'><input type='checkbox' name='keep' value='1' checked><input disabled name='disabled' value='omit'><input name='links[]' value='https://example.test/a'><input name='links[]' value='https://example.test/b'></form>";
    @Test void profilePreservesCurrentSuccessfulFieldsAndRejectsOtherProjects(){
        var fields=DikidiClientPhoto.profile(FORM,"100","123");
        assertTrue(fields.contains(Map.entry("source_id","12")));assertTrue(fields.contains(Map.entry("sex","0")));
        assertTrue(fields.contains(Map.entry("comment","Keep & preserve")));assertTrue(fields.contains(Map.entry("keep","1")));
        assertFalse(fields.stream().anyMatch(e->Set.of("disabled","is_blacklist").contains(e.getKey())));
        assertEquals(2,fields.stream().filter(e->e.getKey().equals("links[]")).count());
        assertThrows(CrmFailure.class,()->DikidiClientPhoto.profile(FORM,"999","123"));
        assertThrows(CrmFailure.class,()->DikidiClientPhoto.profile(FORM+FORM,"100","123"));
    }
    @Test void transferSendsOneSaveAndVerifiesImageIndependently(){
        var site=new Site();try(var provider=site.provider();var access=access()){
            assertEquals(URL,provider.transferClientAvatar(access,"100","123",new byte[]{1},Instant.now().plusSeconds(30),()->site.checked++));
            assertEquals(1,site.writes);assertEquals(1,site.checked);assertEquals(1,site.uploads);
            assertTrue(site.saved.contains(Map.entry("image_id","55")));assertTrue(site.saved.contains(Map.entry("email","person@example.test")));
        }
    }
    @Test void disabledContainersAndOptionsAreRejectedBeforeAnyExternalWrite(){
        for(String control:List.of(
                "<fieldset disabled><input name='comment' value='must not be sent'></fieldset>",
                "<select name='other' multiple><option value='1'>Unsupported multiple</option></select>",
                "<input type='file' name='unsupported'>",
                "<select name='other'><option disabled value='0'>Disabled</option><option value='1'>Enabled</option></select>",
                "<select name='other'><optgroup disabled><option value='0' selected>Disabled group</option></optgroup></select>")) {
            var site=new Site();site.profile=FORM.replace("</form>",control+"</form>");
            try(var provider=site.provider();var access=access()){
                var failure=assertThrows(CrmFailure.class,()->provider.transferClientAvatar(access,"100","123",new byte[]{1},Instant.now().plusSeconds(30),()->fail("must not reach save")));
                assertTrue(failure.notDispatched());assertEquals(0,site.uploads);assertEquals(0,site.writes);
            }
        }
        assertThrows(CrmFailure.class,()->DikidiClientPhoto.profile(FORM.replace("method='post'","method='get'"),"100","123"));
    }
    @Test void changedProfileOrRevokedLocalPermissionStopsBeforeSave(){
        var site=new Site();site.changed=true;try(var provider=site.provider();var access=access()){
            assertTrue(assertThrows(CrmFailure.class,()->provider.transferClientAvatar(access,"100","123",new byte[]{1},Instant.now().plusSeconds(30),()->fail())).notDispatched());assertEquals(0,site.writes);
        }
        site=new Site();try(var provider=site.provider();var access=access()){
            assertThrows(IllegalStateException.class,()->provider.transferClientAvatar(access,"100","123",new byte[]{1},Instant.now().plusSeconds(30),()->{throw new IllegalStateException("revoked");}));assertEquals(0,site.writes);
        }
    }
    @Test void aSaveFailureIsUncertainAndNeverRetried(){
        var site=new Site();site.failSave=true;try(var provider=site.provider();var access=access()){
            assertFalse(assertThrows(CrmFailure.class,()->provider.transferClientAvatar(access,"100","123",new byte[]{1},Instant.now().plusSeconds(30),()->{})).notDispatched());assertEquals(1,site.writes);
        }
    }
    static CrmAccess access(){return new CrmAccess(UUID.randomUUID(),1,"70000000000","secret".toCharArray());}
    static class Site implements DikidiTransport {
        int writes,uploads,reads,checked;boolean changed,failSave;List<Map.Entry<String,String>> saved;String profile=FORM;
        final ArrayDeque<String> login=new ArrayDeque<>(List.of("<input name='telegram_csrf' type='hidden' value='first'>","{\"html\":\"<input name='csrf' type='hidden' value='second'>\"}","{\"callback\":[\"sw.auth.complete();\"]}"));
        DikidiProvider provider(){return new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(10),65536),()->this);}
        public Response exchange(String method,URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout){
            if(!login.isEmpty())return new Response(200,login.removeFirst());String path=uri.getPath();
            if(path.endsWith("/journal/"))return new Response(200,"journal");
            if(path.endsWith("/journal/api/"))return new Response(200,"{\"company\":{\"accesses\":{\"100\":{\"actor\":{\"id\":\"actor\"}}}},\"masters\":{\"info\":{\"ids\":[],\"data\":[]}}}");
            if(path.contains("/clients/settings/")){reads++;return new Response(200,JSON.writeValueAsString(Map.of("html",changed && reads>1?profile.replace("value='Anna'","value='Changed'"):profile)));}
            if(path.contains("/clients/save/")){writes++;saved=form;return new Response(200,failSave?"{\"error\":1,\"message\":\"auth required\"}":"{}");}
            if(path.contains("/clients/filter/"))return new Response(200,JSON.writeValueAsString(Map.of("html","<tr id='item-123'><td class='line-name'><div class='icon ico-circle clients_icon' style='background-image:url("+URL+")'></div><a>Anna Surname</a></td><td class='line-phone'>79991234567</td></tr>")));
            throw new AssertionError(path);
        }
        @Override public Response upload(URI uri,String field,String filename,String contentType,byte[] content,String project,RequestProfile profile,Duration timeout,Observer observer){
            uploads++;observer.beforeSend();observer.dispatched();var result=new Response(200,JSON.writeValueAsString(Map.of("data",List.of("55","image",URL,"code",512,512))));observer.response(result);return result;
        }
    }
}
