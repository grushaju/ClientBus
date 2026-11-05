package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.INVALID_RESPONSE;

@Component
@Transactional(propagation=Propagation.MANDATORY)
public class CrmAppointmentWriter {
    private final CrmStore store;
    public CrmAppointmentWriter(CrmStore store) { this.store=store; }
    public void apply(CrmProjectEntity project,CrmAppointmentPage page,long generation) {
        if (page.items().size()>5000) invalid();
        var ids=new HashSet<String>();
        for (var source : page.items()) {
            validate(source,page.scope()); if (!ids.add(source.externalId())) invalid();
            var existing=store.em.createQuery("from CrmAppointmentEntity where project.id=:project and externalId=:external",CrmAppointmentEntity.class)
                    .setParameter("project",project.getId()).setParameter("external",source.externalId()).getResultList();
            boolean created=existing.isEmpty(); var row=created ? new CrmAppointmentEntity() : existing.getFirst();
            if (created) { row.setProject(project); row.setExternalId(source.externalId()); }
            boolean authoritative="JOURNAL".equals(source.source());
            boolean replace=authoritative || !"JOURNAL".equals(row.getSourceAttributes().get("source"));
            if (replace) {
                if (created || source.presentFields().contains("time")) { row.setStartLocal(source.localStart()); row.setEndLocal(source.localEnd()); }
                if (created || source.presentFields().contains("kind")) row.setKind(source.kind());
                if (created || source.presentFields().contains("client")) {
                    boolean different=!Objects.equals(row.getClientIdentityKind(),source.client()==null ? null : source.client().identityKind())
                            || !Objects.equals(row.getExternalClientId(),source.client()==null ? null : source.client().externalId());
                    row.setClientIdentityKind(source.client()==null ? null : source.client().identityKind());
                    row.setExternalClientId(source.client()==null ? null : source.client().externalId());
                    if (different) row.setClientSnapshot(Map.of());
                    row.setCrmClient(source.client()==null ? null : client(project.getId(),source.client()));
                }
                var snapshot=new LinkedHashMap<>(row.getClientSnapshot()); snapshot.putAll(source.clientSnapshot());
                Map.of("clientName","name","clientPhone","phone","participants","participants").forEach((field,key) -> {
                    if (source.presentFields().contains(field) && !source.clientSnapshot().containsKey(key)) snapshot.remove(key);
                });
                row.setClientSnapshot(snapshot);
                if (source.presentFields().contains("appointmentState")) row.setAppointmentState(source.appointmentState());
                if (source.presentFields().contains("confirmationState")) row.setConfirmationState(source.confirmationState());
                if (source.presentFields().contains("attendanceState")) row.setAttendanceState(source.attendanceState());
                if (source.presentFields().contains("comment")) row.setComment(source.comment());
                var state=new LinkedHashMap<>(row.getProviderState()); state.putAll(source.providerState()); row.setProviderState(state);
            } else {
                if (row.getComment()==null && !journalFields(row).contains("comment") && source.presentFields().contains("comment")) row.setComment(source.comment());
            }
            var attributes=new LinkedHashMap<>(row.getSourceAttributes());
            if (replace) {
                if (authoritative) {
                    var observed=journalFields(row); observed.addAll(source.presentFields());
                    attributes.put("journalObservedFields",new ArrayList<>(observed));
                }
                attributes.put("source",source.source()); attributes.put("presentFields",new ArrayList<>(source.presentFields()));
                attributes.put("servicesComplete",source.servicesComplete()); attributes.put("mastersComplete",source.mastersComplete());
            } else attributes.put("historyObservedAt",page.observedAt().toString());
            row.setSourceAttributes(attributes);
            if (created || authoritative) { row.setObservationState(authoritative ? "OBSERVED" : "UNKNOWN"); row.setLastObservedAt(page.observedAt()); }
            row.setLastObservedGeneration(generation); row.setObservationCheckedAt(page.observedAt());
            if (authoritative) row.setMissingGeneration(null);
            CrmAppointmentTimes.derive(row,project);
            if (created) store.em.persist(row);
            if (replace) { services(row,source); masters(row,source); }
        }
        store.em.flush();
    }
    private static Set<String> journalFields(CrmAppointmentEntity row) {
        var result=new HashSet<String>();
        Object fields=row.getSourceAttributes().get("journalObservedFields");
        if (fields==null && "JOURNAL".equals(row.getSourceAttributes().get("source"))) fields=row.getSourceAttributes().get("presentFields");
        if (fields instanceof Collection<?> values) for (Object value : values) if (value instanceof String name) result.add(name);
        return result;
    }
    private CrmClientEntity client(UUID project,CrmClientReference ref) {
        return store.em.createQuery("from CrmClientEntity where project.id=:project and identityKind=:kind and externalId=:external",CrmClientEntity.class)
                .setParameter("project",project).setParameter("kind",ref.identityKind()).setParameter("external",ref.externalId()).getResultList().stream().findFirst().orElse(null);
    }
    private void services(CrmAppointmentEntity row,CrmAppointment source) {
        var old=store.em.createQuery("from CrmAppointmentServiceEntity where appointment.id=:id order by position",CrmAppointmentServiceEntity.class)
                .setParameter("id",row.getId()).getResultList();
        var remaining=new ArrayList<>(old);
        var selected=new ArrayList<CrmAppointmentServiceEntity>();
        var incoming=new LinkedHashMap<CrmAppointmentServiceEntity,CrmAppointmentService>();
        var exact=new HashMap<Integer,CrmAppointmentServiceEntity>();
        // Reserve all proven line identities before a weaker service/position fallback can consume them.
        for (int index=0;index<source.services().size();index++) {
            String lineId=source.services().get(index).externalLineId();
            if (lineId==null) continue;
            var item=uniqueService(remaining.stream().filter(previous -> lineId.equals(previous.getExternalLineId())).toList());
            if (item!=null) { exact.put(index,item); remaining.remove(item); }
        }
        for (int index=0;index<source.services().size();index++) {
            var value=source.services().get(index);
            CrmAppointmentServiceEntity item=exact.get(index);
            if (item==null && value.externalServiceId()!=null && source.services().stream().filter(other -> value.externalServiceId().equals(other.externalServiceId())).count()==1)
                item=uniqueService(remaining.stream().filter(previous -> value.externalServiceId().equals(previous.getExternalServiceId())
                        && (value.externalLineId()==null || previous.getExternalLineId()==null)).toList());
            if (item==null && source.servicesComplete() && value.externalLineId()==null && value.externalServiceId()==null && index<old.size()) {
                var previous=old.get(index);
                if (remaining.contains(previous) && previous.getExternalLineId()==null && previous.getExternalServiceId()==null
                        && Objects.equals(previous.getDisplayName(),value.displayName())) item=previous;
            }
            if (item==null) {
                // A partial collection cannot distinguish anonymous/repeated lines already retained in storage.
                if (!source.servicesComplete() && !old.isEmpty() && value.externalLineId()==null
                        && (value.externalServiceId()==null || remaining.stream().anyMatch(previous -> value.externalServiceId().equals(previous.getExternalServiceId())))) continue;
                item=new CrmAppointmentServiceEntity(); item.setAppointment(row);
            } else remaining.remove(item);
            selected.add(item); incoming.put(item,value);
        }
        var ordered=source.servicesComplete() ? selected : new ArrayList<>(old);
        if (!source.servicesComplete()) for (var item : selected) if (item.getId()==null) ordered.add(item);
        if (ordered.size()>200) invalid();
        int nextPosition=old.stream().mapToInt(CrmAppointmentServiceEntity::getPosition).max().orElse(-1)+1;
        if (source.servicesComplete() && !ordered.equals(old)) {
            // Move retained rows out of the final position range before swapping their order.
            int temporary=Math.max(ordered.size(),nextPosition);
            for (var previous : old) previous.setPosition(temporary++);
            remaining.forEach(store.em::remove);
            store.em.flush();
        }
        for (int index=0;index<ordered.size();index++) {
            var item=ordered.get(index);
            var value=incoming.get(item); if (value==null) continue;
            if (source.servicesComplete()) item.setPosition(index);
            else if (item.getId()==null) item.setPosition(nextPosition++);
            if (value.externalServiceId()!=null && item.getExternalServiceId()!=null && !value.externalServiceId().equals(item.getExternalServiceId())) {
                item.setDisplayName(null); item.setPrice(null); item.setCurrency(null); item.setDurationSeconds(null);
            }
            if (value.externalServiceId()!=null) item.setExternalServiceId(value.externalServiceId());
            if (value.externalLineId()!=null) item.setExternalLineId(value.externalLineId());
            item.setService(item.getExternalServiceId()==null ? null : store.em.createQuery("from CrmServiceEntity where project.id=:project and externalId=:external",CrmServiceEntity.class)
                    .setParameter("project",row.getProject().getId()).setParameter("external",item.getExternalServiceId()).getResultList().stream().findFirst().orElse(null));
            if (value.presentFields().contains("displayName")) item.setDisplayName(value.displayName());
            if (value.presentFields().contains("durationSeconds")) item.setDurationSeconds(value.durationSeconds());
            if (value.presentFields().contains("price")) item.setPrice(value.price());
            if (value.presentFields().contains("currency")) item.setCurrency(value.currency());
            item.setAttributes(Map.of("presentFields",new ArrayList<>(value.presentFields())));
            if (item.getId()==null) store.em.persist(item);
        }
    }
    private static CrmAppointmentServiceEntity uniqueService(List<CrmAppointmentServiceEntity> matches) { return matches.size()==1 ? matches.getFirst() : null; }
    private void masters(CrmAppointmentEntity row,CrmAppointment source) {
        var old=store.em.createQuery("from CrmAppointmentMasterEntity where appointment.id=:id order by position",CrmAppointmentMasterEntity.class)
                .setParameter("id",row.getId()).getResultList();
        var observed=new HashSet<String>();
        for (var value : source.masters()) {
            observed.add(value.externalMasterId());
            var item=old.stream().filter(current -> current.getExternalMasterId().equals(value.externalMasterId())).findFirst().orElse(null);
            boolean created=item==null;
            if (created) { item=new CrmAppointmentMasterEntity(); item.setAppointment(row); item.setExternalMasterId(value.externalMasterId()); item.setPosition(old.stream().mapToInt(CrmAppointmentMasterEntity::getPosition).max().orElse(-1)+1); old.add(item); }
            item.setMaster(store.em.createQuery("from CrmMasterEntity where project.id=:project and externalId=:external",CrmMasterEntity.class)
                    .setParameter("project",row.getProject().getId()).setParameter("external",value.externalMasterId()).getResultList().stream().findFirst().orElse(null));
            if (value.presentFields().contains("displayName")) item.setDisplayName(value.displayName());
            boolean startChanged=value.presentFields().contains("localStart") && !Objects.equals(item.getStartLocal(),value.localStart());
            boolean endChanged=value.presentFields().contains("localEnd") && !Objects.equals(item.getEndLocal(),value.localEnd());
            if (value.presentFields().contains("localStart")) item.setStartLocal(value.localStart());
            else if (endChanged) item.setStartLocal(null);
            if (value.presentFields().contains("localEnd")) item.setEndLocal(value.localEnd());
            else if (startChanged) item.setEndLocal(null);
            if (value.presentFields().contains("durationSeconds")) item.setDurationSeconds(value.durationSeconds());
            if (value.presentFields().contains("price")) item.setPrice(value.price());
            if (value.presentFields().contains("currency")) item.setCurrency(value.currency());
            item.setAttributes(Map.of("presentFields",new ArrayList<>(value.presentFields())));
            if (created) store.em.persist(item);
        }
        if (source.mastersComplete()) for (var item : old) if (!observed.contains(item.getExternalMasterId())) store.em.remove(item);
    }
    public void missing(CrmProjectEntity project,CrmAppointmentQuery scope,long generation,Instant observedAt) {
        var query=store.em.createQuery("update CrmAppointmentEntity a set a.observationState='NOT_OBSERVED',a.observationCheckedAt=:observed,a.missingGeneration=:generation,a.version=a.version+1 where a.project.id=:project and a.startLocal>=:start and a.startLocal<:end "
                +"and a.lastObservedGeneration<:generation"+(scope.client()==null ? "" : " and a.kind='APPOINTMENT' and a.clientIdentityKind=:kind and a.externalClientId=:client"))
                .setParameter("project",project.getId()).setParameter("start",scope.dateFrom().atStartOfDay()).setParameter("end",scope.dateTo().plusDays(1).atStartOfDay())
                .setParameter("generation",generation).setParameter("observed",observedAt);
        if (scope.client()!=null) query.setParameter("kind",scope.client().identityKind()).setParameter("client",scope.client().externalId());
        query.executeUpdate();
    }
    private static void validate(CrmAppointment source,CrmAppointmentQuery scope) {
        id(source.externalId());
        if (!allowed(source.kind(),"APPOINTMENT","RESERVATION","BLOCK","UNKNOWN") || source.localStart()==null
                || source.localStart().toLocalDate().isBefore(scope.dateFrom()) || source.localStart().toLocalDate().isAfter(scope.dateTo())
                || source.localStart().getNano()%1000!=0 || source.localEnd()!=null && (source.localEnd().getNano()%1000!=0
                    || !source.localEnd().isAfter(source.localStart()) || source.localEnd().isAfter(source.localStart().plusDays(31)))
                || !allowed(source.source(),"JOURNAL","HISTORY") || source.services().size()>200 || source.masters().size()>100
                || !Set.of("time","kind","client","clientName","clientPhone","participants","comment","appointmentState","confirmationState","attendanceState").containsAll(source.presentFields())) invalid();
        if (!allowed(source.appointmentState(),"UNKNOWN","SCHEDULED","CANCELLED","DELETED")
                || !allowed(source.confirmationState(),"UNKNOWN","UNCONFIRMED","CONFIRMED")
                || !allowed(source.attendanceState(),"UNKNOWN","NOT_RECORDED","ARRIVED","NO_SHOW")) invalid();
        if (source.client()!=null) { id(source.client().externalId()); if (!"PROJECT".equals(source.client().identityKind())) invalid(); }
        if (scope.client()!=null && (!scope.client().equals(source.client()) || !"APPOINTMENT".equals(source.kind()))) invalid();
        text(source.comment()); snapshot(source.clientSnapshot());
        if (source.providerState().size()>10 || source.providerState().entrySet().stream().anyMatch(entry ->
                !Set.of("status_id","is_deleted","is_accept","client_came_id","is_time_reservation","is_group_session").contains(entry.getKey()) || entry.getValue().length()>128)) invalid();
        var lines=new HashSet<String>();
        for (var service : source.services()) { if (service.externalServiceId()!=null) id(service.externalServiceId()); if (service.externalLineId()!=null) { id(service.externalLineId()); if (!lines.add(service.externalLineId())) invalid(); }
            text(service.displayName()); money(service.price()); terms(service.durationSeconds(),service.currency());
            if (!Set.of("displayName","durationSeconds","price","currency").containsAll(service.presentFields())) invalid(); }
        var masters=new HashSet<String>();
        for (var master : source.masters()) { id(master.externalMasterId()); if (!masters.add(master.externalMasterId())) invalid(); text(master.displayName()); money(master.price()); terms(master.durationSeconds(),master.currency());
            if (!validLocal(master.localStart()) || !validLocal(master.localEnd())
                    || master.localEnd()!=null && master.localStart()!=null && !master.localEnd().isAfter(master.localStart())
                    || !Set.of("displayName","localStart","localEnd","durationSeconds","price","currency").containsAll(master.presentFields())) invalid(); }
    }
    private static boolean validLocal(LocalDateTime local) { return local==null || local.getYear()>=2000 && local.getYear()<=2100 && local.getNano()%1000==0; }
    private static boolean allowed(String value,String... values) { return value!=null && Set.of(values).contains(value); }
    private static void snapshot(Map<String,Object> value) {
        if (!Set.of("name","phone","participants").containsAll(value.keySet())) invalid();
        for (var field : value.entrySet()) {
            if (field.getKey().equals("participants")) {
                if (!(field.getValue() instanceof List<?> list) || list.size()>100) invalid();
                for (Object item : (List<?>)field.getValue()) {
                    if (!(item instanceof Map<?,?> map) || !Set.of("name","phone").containsAll(map.keySet())) invalid();
                    for (Object text : ((Map<?,?>)item).values()) if (!(text instanceof String string) || string.length()>65536) invalid();
                }
            } else if (!(field.getValue() instanceof String string) || string.length()>65536) invalid();
        }
    }
    private static void terms(Long seconds,String currency) { if (seconds!=null && (seconds<0 || seconds>31L*86400) || currency!=null && !currency.matches("[A-Z]{3}")) invalid(); }
    private static void money(BigDecimal value) { if (value!=null) { var stripped=value.stripTrailingZeros(); if (stripped.signum()<0 || stripped.scale()>4 || stripped.precision()-(long)stripped.scale()>15) invalid(); } }
    private static void id(String value) { if (value==null || value.isBlank() || value.length()>128) invalid(); }
    private static void text(String value) { if (value!=null && value.length()>65536) invalid(); }
    private static void invalid() { throw new CrmFailure(INVALID_RESPONSE); }
}
