package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import java.math.*;
import java.time.*;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** R02 only. HTML history labels and payment evidence do not own appointment/financial state. */
final class DikidiAppointments {
    static List<CrmAppointment> parse(JsonNode response,String project,LocalDate day) {
        JsonNode data=response.path("masters").path("records").path("data");
        if (!(data.isObject() || data.isArray() && data.isEmpty())) fail(INVALID_RESPONSE);
        var collected=new LinkedHashMap<String,CrmAppointment>();
        for (var master : data.properties()) {
            id(master.getKey());
            if (!(master.getValue().isObject() || master.getValue().isArray() && master.getValue().isEmpty())) fail(INVALID_RESPONSE);
            for (var date : master.getValue().properties()) {
                LocalDate localDate;
                try { localDate=LocalDate.parse(date.getKey()); } catch (DateTimeException error) { throw new CrmFailure(INVALID_RESPONSE); }
                if (!localDate.equals(day)) continue;
                if (!(date.getValue().isObject() || date.getValue().isArray() && date.getValue().isEmpty())) fail(INVALID_RESPONSE);
                for (var record : date.getValue().properties()) {
                    var item=parseRecord(record.getKey(),record.getValue(),master.getKey(),day,project);
                    var old=collected.get(item.externalId());
                    collected.put(item.externalId(),old==null ? item : merge(old,item));
                    if (collected.size()>5000) fail(INVALID_RESPONSE);
                }
            }
        }
        return List.copyOf(collected.values());
    }
    private static CrmAppointment parseRecord(String key,JsonNode row,String master,LocalDate date,String project) {
        if (!row.isObject()) fail(INVALID_RESPONSE);
        String external=id(key);
        if (!external.equals(identifier(row.path("id")))) fail(CONTEXT_MISMATCH);
        JsonNode info=row.path("info"); if (!info.isObject()) fail(INVALID_RESPONSE);
        for (JsonNode value : List.of(row.path("company_id"),info.path("company_id")))
            if (present(value) && !project.equals(identifier(value))) fail(CONTEXT_MISMATCH);
        if (present(row.path("item_id")) && !master.equals(identifier(row.path("item_id")))) fail(CONTEXT_MISMATCH);
        if (present(row.path("item_type")) && !"master".equals(text(row.path("item_type")))) fail(CONTEXT_MISMATCH);
        if (present(row.path("date")) && !date.toString().equals(text(row.path("date")))) fail(CONTEXT_MISMATCH);
        LocalDateTime start=local(row.path("begin"),date), end=local(row.path("end"),date);
        if (start==null || !start.toLocalDate().equals(date) || end!=null && (!end.isAfter(start) || end.isAfter(start.plusDays(31)))) fail(INVALID_RESPONSE);
        String group=flag(info.path("is_group_session")), reservation=flag(info.path("is_time_reservation"));
        String kind="1".equals(group) ? "UNKNOWN" : "1".equals(reservation) ? "RESERVATION"
                : "0".equals(reservation) && "0".equals(group) ? "APPOINTMENT" : "UNKNOWN";
        CrmClientReference client=null;
        if (present(info.path("client_id")) && !"0".equals(info.path("client_id").asString()) && "0".equals(flag(info.path("client_is_system")))
                && !"1".equals(group)) client=new CrmClientReference("PROJECT",identifier(info.path("client_id")));
        Map<String,Object> snapshot=new LinkedHashMap<>();
        put(snapshot,"name",text(info.path("client_name"))); put(snapshot,"phone",text(info.path("client_phone")));
        JsonNode participants=info.path("clients");
        if (participants.isArray() && participants.size()>1) {
            if (participants.size()>100) fail(INVALID_RESPONSE);
            var names=new ArrayList<Map<String,Object>>();
            for (var participant : participants) {
                var values=new LinkedHashMap<String,Object>(); put(values,"name",text(participant.path("name")));
                put(values,"phone",text(participant.path("phone"))); names.add(values);
            }
            snapshot.put("participants",names); client=null; kind="UNKNOWN";
        }
        var states=new LinkedHashMap<String,String>();
        for (String field : List.of("status_id","is_deleted","is_accept","client_came_id","is_time_reservation","is_group_session")) {
            var value=info.path(field); if (present(value)) states.put(field,scalar(value));
        }
        String deleted=flag(info.path("is_deleted"));
        String state="1".equals(deleted) ? "DELETED" : "1".equals(states.get("status_id")) && "0".equals(deleted) ? "SCHEDULED" : "UNKNOWN";
        String confirmation=switch (Objects.toString(flag(info.path("is_accept")),"")) { case "1" -> "CONFIRMED"; case "0" -> "UNCONFIRMED"; default -> "UNKNOWN"; };
        String attendance=switch (Objects.toString(text(info.path("client_came_id")),"")) { case "1" -> "ARRIVED"; case "2" -> "NO_SHOW"; case "0" -> "NOT_RECORDED"; default -> "UNKNOWN"; };
        var services=new ArrayList<CrmAppointmentService>(); boolean servicesComplete=info.has("services") && info.path("services").isArray();
        if (info.has("services") && !info.path("services").isArray()) fail(INVALID_RESPONSE);
        if (servicesComplete) for (var service : info.path("services")) {
            if (services.size()>=200 || !service.isObject()) fail(INVALID_RESPONSE);
            services.add(new CrmAppointmentService(optionalId(service.path("id")),optionalId(service.path("line_id")),
                    text(service.path("title")),minutes(service.path("duration").isMissingNode() ? service.path("time") : service.path("duration")),
                    money(service.path("cost").isMissingNode() ? service.path("price") : service.path("cost")),currency(service.path("currency")),
                    fields(service,Map.of("title","displayName","duration","durationSeconds","time","durationSeconds","cost","price","price","price","currency","currency"))));
        }
        var masters=new LinkedHashMap<String,CrmAppointmentMaster>(); boolean mastersComplete=info.has("masters") && info.path("masters").isArray();
        if (info.has("masters") && !info.path("masters").isArray()) fail(INVALID_RESPONSE);
        if (mastersComplete) for (var value : info.path("masters")) {
            if (masters.size()>=100 || !value.isObject()) fail(INVALID_RESPONSE);
            String mid=identifier(value.path("id").isMissingNode() ? value.path("master_id") : value.path("id"));
            if (masters.put(mid,new CrmAppointmentMaster(mid,text(value.path("name").isMissingNode() ? value.path("username") : value.path("name")),
                    local(value.path("record_from"),date),local(value.path("record_to"),date),minutes(value.path("duration")),money(value.path("cost")),currency(value.path("currency")),
                    fields(value,Map.of("name","displayName","username","displayName","record_from","localStart","record_to","localEnd","duration","durationSeconds","cost","price","currency","currency"))))!=null)
                fail(INVALID_RESPONSE);
        }
        var observedFields=new HashSet<>(fields(row,Map.of("begin","localStart","end","localEnd","duration","durationSeconds")));
        if (info.has("master_username")) observedFields.add("displayName");
        var observed=new CrmAppointmentMaster(master,text(info.path("master_username")),start,end,seconds(row.path("duration")),null,null,observedFields);
        if (mastersComplete && !masters.containsKey(master)) fail(CONTEXT_MISMATCH);
        masters.merge(master,observed,DikidiAppointments::mergeMaster);
        start=masters.values().stream().map(CrmAppointmentMaster::localStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(start);
        end=masters.values().stream().anyMatch(value -> value.localEnd()==null) ? null
                : masters.values().stream().map(CrmAppointmentMaster::localEnd).max(Comparator.naturalOrder()).orElse(end);
        var fields=new HashSet<>(Set.of("time"));
        if (!"UNKNOWN".equals(kind) || "1".equals(group) || participants.isArray() && participants.size()>1) fields.add("kind");
        // A missing classifier is not evidence that an existing project-client link disappeared.
        if (client!=null || info.has("client_id") && (!present(info.path("client_id")) || "0".equals(info.path("client_id").asString()))
                || "1".equals(flag(info.path("client_is_system"))) || "1".equals(group) || participants.isArray() && participants.size()>1) fields.add("client");
        if (info.has("client_name")) fields.add("clientName");
        if (info.has("client_phone")) fields.add("clientPhone");
        if (info.has("clients")) fields.add("participants");
        if (info.has("comment")) fields.add("comment");
        if (info.has("status_id") || info.has("is_deleted")) fields.add("appointmentState");
        if (info.has("is_accept")) fields.add("confirmationState");
        if (info.has("client_came_id")) fields.add("attendanceState");
        return new CrmAppointment(external,kind,client,snapshot,start,end,state,confirmation,attendance,states,text(info.path("comment")),
                services,new ArrayList<>(masters.values()),servicesComplete,mastersComplete,fields,"JOURNAL");
    }
    private static CrmAppointment merge(CrmAppointment a,CrmAppointment b) {
        if (!Objects.equals(a.client(),b.client()) || !a.clientSnapshot().equals(b.clientSnapshot()) || !a.kind().equals(b.kind())
                || !a.providerState().equals(b.providerState()) || !Objects.equals(a.comment(),b.comment()) || !a.services().equals(b.services()))
            throw new CrmFailure(CAPABILITY_UNVERIFIED);
        var masters=new LinkedHashMap<String,CrmAppointmentMaster>();
        for (var item : a.masters()) masters.put(item.externalMasterId(),item);
        for (var item : b.masters()) masters.merge(item.externalMasterId(),item,DikidiAppointments::mergeMaster);
        LocalDateTime start=masters.values().stream().map(CrmAppointmentMaster::localStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(a.localStart());
        LocalDateTime end=masters.values().stream().anyMatch(value -> value.localEnd()==null) ? null
                : masters.values().stream().map(CrmAppointmentMaster::localEnd).max(Comparator.naturalOrder()).orElse(null);
        return new CrmAppointment(a.externalId(),a.kind(),a.client(),a.clientSnapshot(),start,end,a.appointmentState(),a.confirmationState(),a.attendanceState(),
                a.providerState(),a.comment(),a.services(),new ArrayList<>(masters.values()),a.servicesComplete() && b.servicesComplete(),
                a.mastersComplete() && b.mastersComplete(),a.presentFields(),a.source());
    }
    private static <T> T same(T a,T b) { if (a!=null && b!=null && !a.equals(b)) fail(CONTEXT_MISMATCH); return a==null ? b : a; }
    private static CrmAppointmentMaster mergeMaster(CrmAppointmentMaster a,CrmAppointmentMaster b) {
        var fields=new HashSet<>(a.presentFields()); fields.addAll(b.presentFields());
        return new CrmAppointmentMaster(a.externalMasterId(),same(a.displayName(),b.displayName()),same(a.localStart(),b.localStart()),same(a.localEnd(),b.localEnd()),
                same(a.durationSeconds(),b.durationSeconds()),same(a.price(),b.price()),same(a.currency(),b.currency()),fields);
    }
    private static LocalDateTime local(JsonNode node,LocalDate date) {
        String value=text(node); if (value==null || value.isBlank()) return null;
        try {
            if (value.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?")) return date.atTime(LocalTime.parse(value));
            var result=LocalDateTime.parse(value.replace(' ','T')); if (result.getNano()!=0) fail(INVALID_RESPONSE); return result;
        } catch (DateTimeException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static BigDecimal money(JsonNode node) {
        String value=text(node); if (value==null || value.isBlank()) return null;
        try { var result=new BigDecimal(value).stripTrailingZeros();
            if (result.signum()<0 || result.scale()>4 || result.precision()-(long)result.scale()>15) fail(INVALID_RESPONSE); return result;
        } catch (NumberFormatException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static Long minutes(JsonNode node) { var value=money(node); if (value==null) return null; try { return value.multiply(BigDecimal.valueOf(60)).longValueExact(); } catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); } }
    private static Long seconds(JsonNode node) { var value=money(node); if (value==null) return null; try { return value.longValueExact(); } catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); } }
    private static String currency(JsonNode node) { String value=text(node); if (value!=null && value.isBlank()) return null; if (value!=null && !value.matches("[A-Z]{3}")) fail(INVALID_RESPONSE); return value; }
    private static Set<String> fields(JsonNode node,Map<String,String> mapping) { var fields=new HashSet<String>(); mapping.forEach((key,value) -> { if (node.has(key)) fields.add(value); }); return fields; }
    private static String optionalId(JsonNode node) { return present(node) && !"0".equals(node.asString()) ? identifier(node) : null; }
    private static String identifier(JsonNode node) { if (!(node.isString() || node.isIntegralNumber())) fail(INVALID_RESPONSE); return id(node.asString()); }
    private static String id(String value) { if (value==null || value.isBlank() || value.length()>128) fail(INVALID_RESPONSE); return value; }
    private static String flag(JsonNode node) { String value=text(node); return switch (Objects.toString(value,"")) { case "0","false" -> "0"; case "1","true" -> "1"; default -> null; }; }
    private static String scalar(JsonNode node) { if (node.isContainer()) fail(INVALID_RESPONSE); String value=node.asString(); if (value.length()>128) fail(INVALID_RESPONSE); return value; }
    private static String text(JsonNode node) { if (!present(node)) return null; if (!(node.isString() || node.isNumber() || node.isBoolean())) fail(INVALID_RESPONSE); String value=node.asString(); if (value.length()>65536) fail(INVALID_RESPONSE); return value; }
    private static boolean present(JsonNode node) { return !node.isMissingNode() && !node.isNull(); }
    private static void put(Map<String,Object> map,String key,String value) { if (value!=null) map.put(key,value); }
    private static void fail(CrmFailure.Code code) { throw new CrmFailure(code); }
}
