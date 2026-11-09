package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** Owner protocol verified on test appointments. The caller MUST NOT retry this sequence. */
final class DikidiCommands {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @FunctionalInterface interface Exchange { JsonNode call(String method,String path,List<Map.Entry<String,String>> form); }
    static void validate(CrmCommand c) {
        if(c==null || c.type()==null || c.operationId()==null || c.idempotencyKey()==null || c.client()==null || !"PROJECT".equals(c.client().identityKind())
                || c.localStart()==null || c.zoneId()==null || c.localStart().getNano()!=0)throw new CrmFailure(INVALID_RESPONSE);
        id(c.client().externalId());
        if(c.type()!=CrmCommand.Type.CANCEL){id(c.externalMasterId());if(c.offers().isEmpty() || c.offers().size()>10)throw new CrmFailure(CAPABILITY_UNVERIFIED);}
        if(c.type()!=CrmCommand.Type.CREATE || c.externalAppointmentId()!=null)id(c.externalAppointmentId());
        if(ZoneId.of(c.zoneId()).getRules().getValidOffsets(c.localStart()).size()!=1)throw new CrmFailure(CAPABILITY_UNVERIFIED);
        var ids=new HashSet<String>();
        for(var offer:c.offers()){
            id(offer.externalServiceId());
            if(!ids.add(offer.externalServiceId()) || offer.price()==null || offer.price().signum()<0 || c.type()==CrmCommand.Type.CREATE && offer.currency()==null || offer.currency()!=null && !offer.currency().matches("[A-Z]{3}") || offer.durationSeconds()<=0)
                throw new CrmFailure(CAPABILITY_UNVERIFIED);
        }
    }
    static CrmCommandResult run(Exchange io,String project,CrmCommand c,JsonNode catalog,CrmCommandPort.Progress progress) {
        validate(c);id(project);
        String reference=c.externalAppointmentId();
        if(c.type()==CrmCommand.Type.CREATE) {
            if(reference!=null || progress==null)throw new CrmFailure(CAPABILITY_UNVERIFIED);
            var seed=io.call("GET","/ajax/newrecord/new/?company_id="+project+"&is-from-journal=1&unique_key="+c.idempotencyKey(),null);
            if(!project.equals(seed.path("company_id").asString()) || !c.idempotencyKey().toString().equals(seed.path("unique_key").asString())
                    || !"1".equals(seed.path("is_from_journal").asString()) || !"3".equals(seed.path("action_source_id").asString()))throw new CrmFailure(CONTEXT_MISMATCH);
            var rows=catalog.path("masters").path("services").path("data").path(c.externalMasterId());
            String currency=seed.path("company").path("currency").path("iso").asString();
            for(var offer:c.offers()) {
                var row=rows.path(offer.externalServiceId());
                if(!offer.externalServiceId().equals(row.path("id").asString()) || !c.externalMasterId().equals(row.path("master_id").asString())
                        || !currency.equals(offer.currency()) || !decimal(row.path("cost")).equals(offer.price().stripTrailingZeros())
                        || decimal(row.path("time")).multiply(BigDecimal.valueOf(60)).longValueExact()!=offer.durationSeconds()
                        || !Set.of("1","true").contains(row.path("active").asString()) || Set.of("1","true").contains(row.path("floating").asString()))
                    return rejected(null);
            }
            if(!available(io,project,c))return rejected(null);
            String path="/ajax/newrecord/time_reservation/?company_id="+project+"&master_id="+c.externalMasterId()+"&time="+encode(local(c.localStart()))+"&action_source_id=3&is-from-journal=1&unique_key="+c.idempotencyKey();
            for(var offer:c.offers())path+="&services_id%5B%5D="+offer.externalServiceId();
            var reserved=io.call("GET",path,null);reference=reserved.path("record_id").asString();id(reference);
            progress.reserved(reference); // committed by the server before final save
            if(!c.externalMasterId().equals(reserved.path("master_id").asString()))throw new CrmFailure(CONTEXT_MISMATCH);
            var result=io.call("POST","/owner/ajax/journal/newrecord_record_save/?company="+project,List.of(
                    Map.entry("record_id",reference),Map.entry("unique_key",c.idempotencyKey().toString()),Map.entry("client_id",c.client().externalId()),
                    Map.entry("is_anonym","0"),Map.entry("remind[]","0"),Map.entry("comments",Objects.toString(c.comment(),"")),Map.entry("color_label_id","0")));
            // HTTP 200/callback/success alone never proves a visit; read the exact reservation ID below.
        } else {
            var before=single(io,project,reference);var observed=DikidiAppointments.single(before,project,reference);
            if(observed==null || !"APPOINTMENT".equals(observed.kind()) || !c.client().equals(observed.client()))return rejected(reference);
            if(!"SCHEDULED".equals(observed.appointmentState()) || !sourceUnchanged(observed,c))return rejected(reference);
            var beforeRow=before.path("master").path("record").path(reference);
            if(c.type()==CrmCommand.Type.MOVE) {
                if(!terms(observed,c) || !before.path("master").path("record").path(reference).path("info").path("resources").isEmpty())return rejected(reference);
                if(!available(io,project,c))return rejected(reference);
                c=checkpoint(progress,c,beforeRow);
                var result=io.call("POST","/ru/owner/ajax/journal/record_save/"+reference+"?company="+project,List.of(
                        Map.entry("unique_key",c.idempotencyKey().toString()),Map.entry("master_id",c.externalMasterId()),Map.entry("date",c.localStart().toLocalDate().toString()),Map.entry("time",c.localStart().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))));
                if(!"1".equals(result.path("isSuccess").asString()) || !reference.equals(result.path("appointment_id").asString()))return unknown(reference);
            } else {
                c=checkpoint(progress,c,beforeRow);
                io.call("GET","/ru/owner/ajax/journal/remove/"+reference+"/?company="+project+"&unique_key="+c.idempotencyKey(),null);
            }
            var after=single(io,project,reference);
            if(!preserved(before.path("master").path("record").path(reference)).equals(preserved(after.path("master").path("record").path(reference))))return unknown(reference);
            return evidence(after,project,c.withExternalReference(reference));
        }
        return evidence(single(io,project,reference),project,c.withExternalReference(reference));
    }
    static CrmCommandResult reconcile(Exchange io,String project,CrmCommand c) {
        validate(c);if(c.externalAppointmentId()==null)return unknown(null);
        return evidence(single(io,project,c.externalAppointmentId()),project,c);
    }
    static JsonNode single(Exchange io,String project,String reference) {
        return io.call("POST","/ru/owner/ajax/journal/api/?company="+project,List.of(Map.entry("request[get][]","company.accesses"),
                Map.entry("request[options][company][accesses][]",project),Map.entry("request[get][]","master.record"),Map.entry("request[options][master][record][]",reference)));
    }
    private static CrmCommandResult evidence(JsonNode root,String project,CrmCommand c) {
        if(!root.path("company").path("accesses").path(project).isContainer() || root.path("company").path("accesses").path(project).isEmpty())throw new CrmFailure(CONTEXT_MISMATCH);
        var row=DikidiAppointments.single(root,project,c.externalAppointmentId());
        if(row==null || !"APPOINTMENT".equals(row.kind()) || !c.client().equals(row.client()))return unknown(c.externalAppointmentId());
        if(c.type()!=CrmCommand.Type.CREATE) {
            var baseline=JSON.readTree(c.preservedSnapshot()).path("providerBefore");
            if(!baseline.isObject() || !preserved(baseline).equals(preserved(root.path("master").path("record").path(c.externalAppointmentId()))))return unknown(c.externalAppointmentId());
            if(c.type()==CrmCommand.Type.CANCEL && !sourceUnchanged(row,c))return unknown(c.externalAppointmentId());
        }
        if(c.type()==CrmCommand.Type.CANCEL)return "CANCELLED".equals(row.appointmentState()) ? confirmed(c.externalAppointmentId()) : unknown(c.externalAppointmentId());
        if(!"SCHEDULED".equals(row.appointmentState()) || !c.localStart().equals(row.localStart()) || !terms(row,c))return unknown(c.externalAppointmentId());
        return confirmed(c.externalAppointmentId());
    }
    private static boolean terms(CrmAppointment row,CrmCommand c) {
        if(!row.servicesComplete() || !row.mastersComplete() || row.masters().size()!=1 || !c.externalMasterId().equals(row.masters().getFirst().externalMasterId()) || row.services().size()!=c.offers().size())return false;
        for(var offer:c.offers()) {
            var matches=row.services().stream().filter(service->offer.externalServiceId().equals(service.externalServiceId())).toList();
            if(matches.size()!=1)return false;
            var service=matches.getFirst();
            if(service.price()==null || service.price().compareTo(offer.price())!=0 || !Objects.equals(service.durationSeconds(),offer.durationSeconds()) || service.currency()!=null && offer.currency()!=null && !service.currency().equals(offer.currency()))return false;
        }
        return true;
    }
    private static boolean sourceUnchanged(CrmAppointment row,CrmCommand c) {
        var snapshot=JSON.readTree(c.preservedSnapshot());
        var appointment=snapshot.path("appointment");
        if(!appointment.path("startlocal").isString())return false;
        try {
            if(!LocalDateTime.parse(appointment.path("startlocal").asString().replace(' ','T')).equals(row.localStart()))return false;
            if(appointment.path("endlocal").isString() && !LocalDateTime.parse(appointment.path("endlocal").asString().replace(' ','T')).equals(row.localEnd()))return false;
        } catch(DateTimeException error) { return false; }
        if(appointment.has("comment") && !Objects.equals(appointment.path("comment").isNull()?null:appointment.path("comment").asString(),row.comment()))return false;
        if(snapshot.path("masters").isArray()) {
            if(!row.mastersComplete())return false;
            var masters=new HashSet<String>();
            for(var master:snapshot.path("masters"))masters.add(master.path("externalmasterid").asString());
            if(!masters.equals(new HashSet<>(row.masters().stream().map(CrmAppointmentMaster::externalMasterId).toList())))return false;
        }
        return true;
    }
    private static CrmCommand checkpoint(CrmCommandPort.Progress progress,CrmCommand c,JsonNode row) {
        if(progress==null)throw new CrmFailure(CAPABILITY_UNVERIFIED);
        var snapshot=JSON.readTree(c.preservedSnapshot());
        if(!(snapshot instanceof ObjectNode object))throw new CrmFailure(INVALID_RESPONSE);
        object.set("providerBefore",row);
        progress.observed(row.toString());
        return c.withPreservedSnapshot(object.toString());
    }
    private static boolean available(Exchange io,String project,CrmCommand c) {
        String path="/ajax/newrecord/to_master_get_datetime/?company_id="+project+"&master_id="+c.externalMasterId()+"&date="+c.localStart().toLocalDate()+"&action_source_id=3&is-from-journal=1";
        for(var offer:c.offers())path+="&services_id%5B%5D="+offer.externalServiceId();
        var result=io.call("GET",path,null);
        return DikidiAvailability.parse(result,project,c.externalMasterId(),c.offers().stream().map(CrmCommand.Offer::externalServiceId).toList(),c.localStart().toLocalDate(),Instant.now()).starts().contains(c.localStart());
    }
    private static Map<String,JsonNode> preserved(JsonNode row) {
        var result=new LinkedHashMap<String,JsonNode>();
        for(String field:List.of("client_id","client_name","client_phone","client_discount","master_id","services","resources","payment","prepayment","comment","remind_confirm","is_accept","color_label_id","files","promocode","client_came_id"))result.put(field,row.path("info").path(field));
        return result;
    }
    private static String local(LocalDateTime start){return start.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));}
    private static String encode(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    private static BigDecimal decimal(JsonNode node){try{return new BigDecimal(node.asString()).stripTrailingZeros();}catch(RuntimeException e){throw new CrmFailure(INVALID_RESPONSE);}}
    private static void id(String value){if(value==null || !value.matches("[1-9][0-9]{0,19}"))throw new CrmFailure(CONTEXT_MISMATCH);}
    private static CrmCommandResult confirmed(String ref){return new CrmCommandResult(CrmCommandResult.Outcome.CONFIRMED,ref);}
    private static CrmCommandResult rejected(String ref){return new CrmCommandResult(CrmCommandResult.Outcome.REJECTED,ref);}
    private static CrmCommandResult unknown(String ref){return new CrmCommandResult(CrmCommandResult.Outcome.UNKNOWN,ref);}
}
