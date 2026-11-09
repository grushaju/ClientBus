package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiCommandsTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDateTime START=LocalDateTime.of(2030,1,10,12,0);
    static CrmCommand command(CrmCommand.Type type){return new CrmCommand(type,UUID.randomUUID(),UUID.randomUUID(),type==CrmCommand.Type.CREATE?null:"500",new CrmClientReference("PROJECT","300"),"200",List.of(new CrmCommand.Offer("400",new BigDecimal("15000"),"RUB",3600)),START,"Europe/Moscow","note",
            type==CrmCommand.Type.CREATE?"{}":"{\"appointment\":{\"startlocal\":\""+(type==CrmCommand.Type.MOVE?"2030-01-09":"2030-01-10")+"T12:00:00\",\"endlocal\":\""+(type==CrmCommand.Type.MOVE?"2030-01-09":"2030-01-10")+"T13:00:00\",\"comment\":\"note\"},\"masters\":[{\"externalmasterid\":\"200\"}]}");}
    static JsonNode catalog(){return JSON.readTree("{\"masters\":{\"services\":{\"data\":{\"200\":{\"400\":{\"id\":\"400\",\"master_id\":\"200\",\"cost\":\"15000.0000\",\"time\":\"60\",\"active\":\"1\",\"floating\":\"0\"}}}}}}");}
    static JsonNode single(String status,String start){
        String day=start.substring(0,10),end=day+" 13:00:00";
        return JSON.readTree("""
                {"company":{"accesses":{"100":{"owner":true}}},"master":{"record":{"500":{
                "id":"500","company_id":"100","item_id":"200","item_type":"master","date":"%s","begin":"%s","end":"%s",
                "info":{"company_id":"100","client_id":"300","client_is_system":"0","client_name":"Test client","client_phone":"test-only",
                "is_time_reservation":"0","is_group_session":"0","status_id":"%s","deleted_datetime":"%s","is_accept":"0","client_came_id":"0",
                "comment":"note","resources":[],"payment":{"document_id":"0","cost":""},"services":[{"id":"400","title":"Test service","cost":"15000","duration":"60"}],
                "masters":[{"id":"200","record_from":"12:00:00","record_to":"13:00:00","duration":"60"}]}}}}}
                """.formatted(day,start,end,status,status.equals("2")?"2030-01-01 00:00:00":""));
    }
    record Request(String method,String path,List<Map.Entry<String,String>> form){}
    static final class Fake implements DikidiCommands.Exchange {
        final CrmCommand command;CrmCommand durable;final List<Request> calls=new ArrayList<>();boolean reserved,checkpoint,saved,failSave,failChange;String mode="create";
        Fake(CrmCommand c){command=c;durable=c;}
        CrmCommandPort.Progress progress(){return new CrmCommandPort.Progress(){
            public void reserved(String id){checkpoint=true;}
            public void observed(String snapshot){assertFalse(saved);checkpoint=true;
                var document=(tools.jackson.databind.node.ObjectNode)JSON.readTree(command.preservedSnapshot());
                document.set("providerBefore",JSON.readTree(snapshot));durable=command.withPreservedSnapshot(document.toString());
            }
        };}
        public JsonNode call(String method,String path,List<Map.Entry<String,String>> form){
            calls.add(new Request(method,path,form));
            if(path.startsWith("/ajax/newrecord/new/"))return JSON.readTree("{\"company_id\":\"100\",\"unique_key\":\""+command.idempotencyKey()+"\",\"is_from_journal\":1,\"action_source_id\":3,\"company\":{\"currency\":{\"iso\":\"RUB\"}}}");
            if(path.contains("to_master_get_datetime"))return JSON.readTree("{\"appointments\":{},\"timeworks\":{},\"dates_true\":[\"2030-01-10\"],\"times\":{\"200\":[\"2030-01-10 12:00:00\"]}}");
            if(path.contains("time_reservation")){reserved=true;return JSON.readTree("{\"record_id\":500,\"master_id\":200}");}
            if(path.contains("newrecord_record_save")){assertTrue(checkpoint,"Final save requires committed progress");saved=true;if(failSave)throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);return JSON.readTree("{\"success\":true}");}
            if(path.contains("record_save/500")){assertTrue(checkpoint);saved=true;if(failChange)throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);return JSON.readTree("{\"isSuccess\":1,\"appointment_id\":500}");}
            if(path.contains("remove/500")){assertTrue(checkpoint);saved=true;if(failChange)throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);return JSON.readTree("{}");}
            if(mode.equals("cancel"))return single(saved?"2":"1","2030-01-10 12:00:00");
            if(mode.equals("move"))return single("1",saved?"2030-01-10 12:00:00":"2030-01-09 12:00:00");
            return single("1","2030-01-10 12:00:00");
        }
    }
    @Test void createCheckpointsReservationBeforeSaveAndUsesOnlyExistingClientId(){
        var c=command(CrmCommand.Type.CREATE);var io=new Fake(c);
        var result=DikidiCommands.run(io,"100",c,catalog(),id->{assertTrue(io.reserved);assertFalse(io.saved);assertEquals("500",id);io.checkpoint=true;});
        assertEquals(CrmCommandResult.Outcome.CONFIRMED,result.outcome());
        var form=io.calls.stream().filter(call->call.path().contains("newrecord_record_save")).findFirst().orElseThrow().form();
        assertTrue(form.contains(Map.entry("client_id","300")));assertTrue(form.contains(Map.entry("remind[]","0")));
        assertTrue(form.stream().noneMatch(field->Set.of("client_name","client_phone").contains(field.getKey())));
    }
    @Test void failedCheckpointNeverStartsFinalSave(){
        var c=command(CrmCommand.Type.CREATE);var io=new Fake(c);
        assertThrows(IllegalStateException.class,()->DikidiCommands.run(io,"100",c,catalog(),id->{throw new IllegalStateException("database unavailable");}));
        assertTrue(io.reserved);assertFalse(io.saved);
    }
    @Test void lostSaveResponseUsesReadOnlyReconciliationAndNeverReservesAgain(){
        var c=command(CrmCommand.Type.CREATE);var io=new Fake(c);io.failSave=true;
        assertThrows(CrmFailure.class,()->DikidiCommands.run(io,"100",c,catalog(),id->io.checkpoint=true));
        int before=io.calls.size();var result=DikidiCommands.reconcile(io,"100",c.withExternalReference("500"));
        assertEquals(CrmCommandResult.Outcome.CONFIRMED,result.outcome());assertEquals(before+1,io.calls.size());
        assertEquals(1,io.calls.stream().filter(call->call.path().contains("time_reservation")).count());
        assertEquals(1,io.calls.stream().filter(call->call.path().contains("newrecord_record_save")).count());
    }
    @Test void moveSendsOnlyTimeAndTheUnchangedMasterWithoutRewritingServicesOrMoney(){
        var c=command(CrmCommand.Type.MOVE);var io=new Fake(c);io.mode="move";
        assertEquals(CrmCommandResult.Outcome.CONFIRMED,DikidiCommands.run(io,"100",c,catalog(),io.progress()).outcome());
        var form=io.calls.stream().filter(call->call.path().contains("record_save/500")).findFirst().orElseThrow().form();
        assertEquals(Set.of("unique_key","master_id","date","time"),new HashSet<>(form.stream().map(Map.Entry::getKey).toList()));
        assertTrue(form.contains(Map.entry("time","12:00:00")));
    }
    @Test void cancelRequiresTheRetainedCancelledObjectAndDoesNotInferItFromAbsence(){
        var c=command(CrmCommand.Type.CANCEL);var io=new Fake(c);io.mode="cancel";
        assertEquals(CrmCommandResult.Outcome.CONFIRMED,DikidiCommands.run(io,"100",c,catalog(),io.progress()).outcome());
        assertEquals(CrmCommandResult.Outcome.UNKNOWN,DikidiCommands.reconcile((method,path,form)->JSON.readTree("{\"company\":{\"accesses\":{\"100\":{\"owner\":true}}},\"master\":{\"record\":{}}}"),"100",c).outcome());
    }
    @Test void freshSeedAndOfferMismatchPreventReservation(){
        var c=command(CrmCommand.Type.CREATE);var io=new Fake(c);
        var changed=JSON.readTree(catalog().toString().replace("15000.0000","16000.0000"));
        assertEquals(CrmCommandResult.Outcome.REJECTED,DikidiCommands.run(io,"100",c,changed,id->fail()).outcome());
        assertFalse(io.reserved);assertFalse(io.saved);
    }
    @Test void currentActiveAndCancelledStatesAreRecognizedWithoutInventingAMissingDeletedFlag(){
        assertEquals("SCHEDULED",DikidiAppointments.single(single("1","2030-01-10 12:00:00"),"100","500").appointmentState());
        assertEquals("CANCELLED",DikidiAppointments.single(single("2","2030-01-10 12:00:00"),"100","500").appointmentState());
        var ambiguous=JSON.readTree(single("1","2030-01-10 12:00:00").toString().replace("\"deleted_datetime\":\"\",",""));
        assertEquals("UNKNOWN",DikidiAppointments.single(ambiguous,"100","500").appointmentState());
    }
    @Test void externallyMovedAppointmentCannotBeMovedOrCancelledFromTheOldSnapshot(){
        for(var type:List.of(CrmCommand.Type.MOVE,CrmCommand.Type.CANCEL)){
            var c=command(type);var io=new Fake(c);io.mode=type==CrmCommand.Type.MOVE?"move":"cancel";
            DikidiCommands.Exchange changed=(method,path,form)->{
                var result=io.call(method,path,form);
                return path.contains("journal/api/")?JSON.readTree(result.toString().replace("2030-01-09","2030-01-11").replace("2030-01-10","2030-01-11")):result;
            };
            assertEquals(CrmCommandResult.Outcome.REJECTED,DikidiCommands.run(changed,"100",c,catalog(),io.progress()).outcome());
            assertFalse(io.saved);assertFalse(io.checkpoint);
        }
    }
    @Test void changedCommentOrMasterAlsoRejectsTheStaleIntentBeforeCancellation(){
        for(String change:List.of("comment","master")){
            var c=command(CrmCommand.Type.CANCEL);var io=new Fake(c);io.mode="cancel";
            DikidiCommands.Exchange changed=(method,path,form)->{
                var result=io.call(method,path,form);
                if(!path.contains("journal/api/"))return result;
                String json=result.toString();
                return JSON.readTree(change.equals("comment")?json.replace("\"comment\":\"note\"","\"comment\":\"new\""):json.replace("200","201"));
            };
            assertEquals(CrmCommandResult.Outcome.REJECTED,DikidiCommands.run(changed,"100",c,catalog(),io.progress()).outcome());assertFalse(io.saved);
        }
    }
    @Test void missingOriginalTimeNeverAuthorizesAnExistingObjectChange(){
        var c=command(CrmCommand.Type.CANCEL).withPreservedSnapshot("{}");var io=new Fake(c);io.mode="cancel";
        assertEquals(CrmCommandResult.Outcome.REJECTED,DikidiCommands.run(io,"100",c,catalog(),io.progress()).outcome());assertFalse(io.saved);
    }
    @Test void reconciliationCannotEraseDetectedFinancialOrCommentDrift(){
        for(var type:List.of(CrmCommand.Type.MOVE,CrmCommand.Type.CANCEL))for(String field:List.of("payment","comment")){
            var c=command(type);var io=new Fake(c);io.mode=type==CrmCommand.Type.MOVE?"move":"cancel";
            DikidiCommands.Exchange drift=(method,path,form)->{
                var result=io.call(method,path,form);
                if(!path.contains("journal/api/") || !io.saved)return result;
                return JSON.readTree(field.equals("payment")?result.toString().replace("\"document_id\":\"0\"","\"document_id\":\"900\""):result.toString().replace("\"comment\":\"note\"","\"comment\":\"changed\""));
            };
            assertEquals(CrmCommandResult.Outcome.UNKNOWN,DikidiCommands.run(drift,"100",c,catalog(),io.progress()).outcome());
            int requests=io.calls.size();
            assertEquals(CrmCommandResult.Outcome.UNKNOWN,DikidiCommands.reconcile(drift,"100",io.durable).outcome());
            assertEquals(requests+1,io.calls.size());
            assertEquals(CrmCommandResult.Outcome.CONFIRMED,DikidiCommands.reconcile(io,"100",io.durable).outcome());
        }
    }
    @Test void failedObservationCheckpointStopsMoveAndCancellation(){
        for(var type:List.of(CrmCommand.Type.MOVE,CrmCommand.Type.CANCEL)){
            var c=command(type);var io=new Fake(c);io.mode=type==CrmCommand.Type.MOVE?"move":"cancel";
            var progress=new CrmCommandPort.Progress(){public void reserved(String id){fail();}public void observed(String snapshot){throw new IllegalStateException("database unavailable");}};
            assertThrows(IllegalStateException.class,()->DikidiCommands.run(io,"100",c,catalog(),progress));assertFalse(io.saved);
        }
    }
    @Test void lostChangeResponseReconcilesFromTheDurableObservationWithoutASecondWrite(){
        for(var type:List.of(CrmCommand.Type.MOVE,CrmCommand.Type.CANCEL)){
            var c=command(type);var io=new Fake(c);io.mode=type==CrmCommand.Type.MOVE?"move":"cancel";io.failChange=true;
            assertThrows(CrmFailure.class,()->DikidiCommands.run(io,"100",c,catalog(),io.progress()));
            int requests=io.calls.size();assertEquals(CrmCommandResult.Outcome.CONFIRMED,DikidiCommands.reconcile(io,"100",io.durable).outcome());assertEquals(requests+1,io.calls.size());
            assertEquals(CrmCommandResult.Outcome.UNKNOWN,DikidiCommands.reconcile(io,"100",c).outcome());
        }
    }
}
