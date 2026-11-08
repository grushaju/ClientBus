package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Database reads only; current mappings and workspace access are resolved on every request. */
@Service
public class CrmAppointmentReader {
    private record Cursor(LocalDateTime start,UUID id) {}
    private final CrmStore store;
    private final CrmAppointmentSync sync;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public CrmAppointmentReader(CrmStore store,CrmAppointmentSync sync,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.store=store; this.sync=sync; this.jdbc=jdbc; tx=new TransactionTemplate(manager);
    }
    public CrmAppointmentPageDto appointments(UUID projectId,CrmAppointmentFilter filter,String cursor) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=store.project(projectId,store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ);
            store.guard.workspace(project.getWorkspace().getId(),false);
            if (filter.crmClientId()!=null) {
                var client=store.em.find(CrmClientEntity.class,filter.crmClientId());
                if (client==null || !client.getProject().getId().equals(projectId)) throw CrmStore.notFound();
            }
            if (filter.masterId()!=null) {
                var master=store.em.find(CrmMasterEntity.class,filter.masterId());
                if (master==null || !master.getProject().getId().equals(projectId)) throw CrmStore.notFound();
            }
            return page(List.of(project),null,filter,cursor);
        });
    }
    public CrmAppointmentDto appointment(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var row=store.em.find(CrmAppointmentEntity.class,id);
            if (row==null) throw CrmStore.notFound();
            var project=store.project(row.getProject().getId(),store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ);
            store.guard.workspace(project.getWorkspace().getId(),false);
            store.em.refresh(row);
            return dto(row);
        });
    }
    public CrmAppointmentClientPageDto clients(UUID projectId,LocalDate from,LocalDate to,String query,String externalClientId,String cursor) {
        var filter=new CrmAppointmentFilter(from,to,null,null,null,null,null,null,null,externalClientId);
        String search=CrmSearch.normalize(query);
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=store.project(projectId,store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ);
            store.guard.workspace(project.getWorkspace().getId(),false);
            String revision=CrmSearch.scope(projectId+"/"+store.guard.actor()+"/"+store.context(project)+"/"+filter+"/"+search+"/"+sync.revision(projectId));
            String after=null;
            if (cursor!=null) {
                try {
                    if (cursor.length()>1024) throw CrmException.invalid();
                    String[] fields=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",2);
                    if (fields.length!=2 || fields[1].isBlank() || fields[1].length()>128) throw CrmException.invalid();
                    if (!revision.equals(fields[0])) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                    after=fields[1];
                } catch (IllegalArgumentException error) { throw CrmException.invalid(); }
            }
            var parameters=new ArrayList<Object>();
            parameters.add(projectId); parameters.add(from.atStartOfDay()); parameters.add(to.plusDays(1).atStartOfDay());
            String sql="""
                    select c.externalclientid,c.displayname,c.phone from (
                        select distinct on (a.externalclientid) a.externalclientid,
                            coalesce(nullif(a.clientsnapshot->>'name',''),'Клиент #'||a.externalclientid) as displayname,
                            nullif(a.clientsnapshot->>'phone','') as phone
                        from crm.appointment a where a.projectid=? and a.startlocal>=? and a.startlocal<?
                            and a.kind='APPOINTMENT' and a.clientidentitykind='PROJECT' and a.externalclientid is not null
                        order by a.externalclientid,a.startlocal desc,a.id desc
                    ) c where 1=1
                    """;
            if (!search.isEmpty()) {
                sql+=" and (lower(c.displayname) like ? escape '!' or lower(coalesce(c.phone,'')) like ? escape '!'"
                        +" or regexp_replace(coalesce(c.phone,''),'[^0-9]','','g') like ? escape '!')";
                parameters.add(CrmSearch.pattern(search)); parameters.add(CrmSearch.pattern(search));
                parameters.add(CrmSearch.pattern(Optional.ofNullable(kit.penny.clientbus.crm.api.CrmPhone.normalize(search)).orElse(search)));
            }
            if (externalClientId!=null) { sql+=" and c.externalclientid=?"; parameters.add(externalClientId); }
            if (after!=null) { sql+=" and c.externalclientid>?"; parameters.add(after); }
            var rows=jdbc.query(sql+" order by c.externalclientid limit 101",(rs,index) ->
                    new CrmAppointmentClientDto(rs.getString("externalclientid"),rs.getString("displayname"),rs.getString("phone")),parameters.toArray());
            boolean more=rows.size()>100;
            var items=List.copyOf(rows.subList(0,Math.min(100,rows.size())));
            String next=more ? Base64.getUrlEncoder().withoutPadding().encodeToString((revision+"\n"+items.getLast().id()).getBytes(StandardCharsets.UTF_8)) : null;
            return new CrmAppointmentClientPageDto(items,next,more,sync.state(project,new CrmAppointmentScope("APPOINTMENTS",null,from,to)),revision);
        });
    }
    public CrmAppointmentPageDto history(UUID clientId,UUID workspaceId,CrmAppointmentFilter filter,String cursor) {
        if (filter.crmClientId()!=null || filter.masterId()!=null || filter.externalClientId()!=null) throw CrmException.invalid();
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization=store.guard.organization(),actor=store.guard.actor();
            var client=store.em.find(ClientEntity.class,clientId);
            if (client==null || !client.getOrganization().getId().equals(organization)) throw CrmStore.notFound();
            if (workspaceId!=null) store.guard.workspace(workspaceId,false);
            // Filter authorization in SQL, before pagination or coverage is calculated.
            var ids=jdbc.queryForList("""
                    select p.id from crm.project p join dbo.employee e on e.employeeid=? and e.organizationid=p.organizationid
                    join dbo.users u on u.userid=e.userid
                    where p.organizationid=? and u.isenabled and (u.role='SUPER_ADMIN' or u.role='EMPLOYEE' and exists
                        (select 1 from dbo.employeeworkspace ew where ew.employeeid=e.employeeid and ew.workspaceid=p.workspaceid))
                        and exists(select 1 from crm.client c where c.projectid=p.id and c.clientid=?)
                    """+(workspaceId==null ? "" : " and p.workspaceid=?")+" order by p.id limit 101",UUID.class,
                    workspaceId==null ? new Object[]{actor,organization,clientId} : new Object[]{actor,organization,clientId,workspaceId});
            if (ids.size()>100) throw new CrmException(422,"CRM_QUERY_LIMIT_REACHED","Уточните рабочее пространство.");
            var projects=new ArrayList<CrmProjectEntity>();
            for (UUID id : ids) {
                var project=store.project(id,organization); store.em.refresh(project,LockModeType.PESSIMISTIC_READ);
                store.guard.workspace(project.getWorkspace().getId(),false); projects.add(project);
            }
            return page(projects,clientId,filter,cursor);
        });
    }
    private CrmAppointmentPageDto page(List<CrmProjectEntity> projects,UUID clientId,CrmAppointmentFilter filter,String cursor) {
        StringBuilder vector=new StringBuilder(store.guard.organization()+"/"+store.guard.actor()+"/"+clientId+"/"+filter);
        for (var project : projects) {
            vector.append('/').append(store.context(project)).append('/').append(project.getVersion())
                    .append('/').append(project.getConnection().getStatus()).append('/').append(project.isEnabled());
            var revisions=jdbc.queryForList("select dataset,scopekey,version from crm.syncstate where projectid=? and (dataset='WRITER:APPOINTMENTS' or dataset='WRITER:FINANCE' or dataset='CLIENTS' or dataset='WRITER:CATALOG') order by dataset,scopekey",project.getId());
            vector.append('/').append(revisions);
        }
        String revision=CrmSearch.scope(vector.toString());
        Cursor after=decode(cursor,revision);
        var coverage=coverage(projects,clientId,filter);
        if (projects.isEmpty()) return new CrmAppointmentPageDto(List.of(),null,false,coverage,revision);
        var parameters=new LinkedHashMap<String,Object>();
        parameters.put("projects",projects.stream().map(CrmProjectEntity::getId).toList());
        parameters.put("start",filter.dateFrom().atStartOfDay()); parameters.put("end",filter.dateTo().plusDays(1).atStartOfDay());
        String hql="from CrmAppointmentEntity a where a.project.id in :projects and a.startLocal>=:start and a.startLocal<:end";
        if (clientId!=null || filter.crmClientId()!=null) {
            hql+=" and a.kind='APPOINTMENT' and exists(select 1 from CrmClientEntity c where c.project=a.project and c.identityKind=a.clientIdentityKind and c.externalId=a.externalClientId"
                    +(clientId==null ? " and c.id=:crmClient)" : " and c.client.id=:client)");
            parameters.put(clientId==null ? "crmClient" : "client",clientId==null ? filter.crmClientId() : clientId);
        }
        if (filter.externalClientId()!=null) {
            hql+=" and a.kind='APPOINTMENT' and a.clientIdentityKind='PROJECT' and a.externalClientId=:externalClient";
            parameters.put("externalClient",filter.externalClientId());
        }
        if (filter.masterId()!=null) {
            hql+=" and exists(select 1 from CrmAppointmentMasterEntity m,CrmMasterEntity catalog where m.appointment=a and catalog.id=:master and catalog.project=a.project and catalog.externalId=m.externalMasterId)";
            parameters.put("master",filter.masterId());
        }
        var values=new String[]{filter.kind(),filter.appointmentState(),filter.confirmationState(),filter.attendanceState(),filter.paymentState()};
        var names=new String[]{"kind","appointmentState","confirmationState","attendanceState","paymentState"};
        for (int i=0;i<values.length;i++) if (values[i]!=null) { hql+=" and a."+names[i]+"=:"+names[i]; parameters.put(names[i],values[i]); }
        if (after!=null) { hql+=" and (a.startLocal>:lastStart or a.startLocal=:lastStart and a.id>:lastId)"; parameters.put("lastStart",after.start()); parameters.put("lastId",after.id()); }
        var query=store.em.createQuery(hql+" order by a.startLocal,a.id",CrmAppointmentEntity.class).setMaxResults(101);
        parameters.forEach(query::setParameter);
        var rows=query.getResultList(); boolean more=rows.size()>100;
        var selected=rows.subList(0,Math.min(100,rows.size()));
        var items=selected.stream().map(this::dto).toList();
        String next=more ? encode(selected.getLast(),revision) : null;
        return new CrmAppointmentPageDto(items,next,more,coverage,revision);
    }
    private List<CrmAppointmentCoverageDto> coverage(List<CrmProjectEntity> projects,UUID clientId,CrmAppointmentFilter filter) {
        return projects.stream().map(project -> {
            var states=new ArrayList<CrmSyncStateDto>();
            states.add(sync.state(project,new CrmAppointmentScope("APPOINTMENTS",null,filter.dateFrom(),filter.dateTo())));
            var clients=store.em.createQuery("select c.id from CrmClientEntity c where c.project.id=:project"
                    +(clientId!=null ? " and c.client.id=:client" : filter.crmClientId()!=null ? " and c.id=:crmClient"
                        : filter.externalClientId()!=null ? " and c.externalId=:externalClient" : " and 1=0")
                    +" and c.identityKind='PROJECT' order by c.id",UUID.class).setParameter("project",project.getId()).setMaxResults(101);
            if (clientId!=null) clients.setParameter("client",clientId);
            else if (filter.crmClientId()!=null) clients.setParameter("crmClient",filter.crmClientId());
            else if (filter.externalClientId()!=null) clients.setParameter("externalClient",filter.externalClientId());
            var ids=clients.getResultList();
            if (ids.size()>100) throw new CrmException(422,"CRM_QUERY_LIMIT_REACHED","Слишком много источников истории клиента.");
            for (UUID id : ids) states.add(sync.state(project,new CrmAppointmentScope("CLIENT_HISTORY",id,filter.dateFrom(),filter.dateTo())));
            return new CrmAppointmentCoverageDto(project.getId(),project.getWorkspace().getId(),List.copyOf(states));
        }).toList();
    }
    private CrmAppointmentDto dto(CrmAppointmentEntity row) {
        var project=row.getProject();
        // Resolve external references even if the catalog/client was imported after the appointment.
        var client=store.em.createQuery("from CrmClientEntity c where c.project.id=:project and c.identityKind=:kind and c.externalId=:external",CrmClientEntity.class)
                .setParameter("project",project.getId()).setParameter("kind",row.getClientIdentityKind()).setParameter("external",row.getExternalClientId()).getResultList().stream().findFirst().orElse(null);
        var serviceRows=store.em.createQuery("from CrmAppointmentServiceEntity s where s.appointment.id=:id order by s.position",CrmAppointmentServiceEntity.class)
                .setParameter("id",row.getId()).getResultList();
        var serviceIds=catalogIds("CrmServiceEntity",project.getId(),serviceRows.stream().map(CrmAppointmentServiceEntity::getExternalServiceId).toList());
        var services=serviceRows.stream().map(item -> {
                    var service=serviceIds.get(item.getExternalServiceId());
                    return new CrmAppointmentServiceDto(item.getId(),service,item.getExternalServiceId(),item.getExternalLineId(),item.getDisplayName(),item.getDurationSeconds(),money(item.getPrice()),item.getCurrency(),fields(item.getAttributes()));
                }).toList();
        var masterRows=store.em.createQuery("from CrmAppointmentMasterEntity m where m.appointment.id=:id order by m.position",CrmAppointmentMasterEntity.class)
                .setParameter("id",row.getId()).getResultList();
        var masterIds=catalogIds("CrmMasterEntity",project.getId(),masterRows.stream().map(CrmAppointmentMasterEntity::getExternalMasterId).toList());
        var masters=masterRows.stream().map(item -> {
                    var master=masterIds.get(item.getExternalMasterId());
                    return new CrmAppointmentMasterDto(item.getId(),master,item.getExternalMasterId(),item.getDisplayName(),item.getStartLocal(),item.getEndLocal(),item.getDurationSeconds(),money(item.getPrice()),item.getCurrency(),fields(item.getAttributes()));
                }).toList();
        boolean current=Objects.equals(row.getDerivedZoneRevision(),project.getZoneRevision()) && Objects.equals(row.getZoneId(),project.getZoneId());
        var warnings=new ArrayList<String>();
        if (project.getZoneId()==null) warnings.add("TIMEZONE_UNKNOWN");
        else if (!current) warnings.add("TIMEZONE_RECALC_REQUIRED");
        else if (row.getStartAt()==null || row.getEndLocal()!=null && row.getEndAt()==null) warnings.add("LOCAL_TIME_AMBIGUOUS_OR_INVALID");
        boolean servicesComplete=Boolean.TRUE.equals(row.getSourceAttributes().get("servicesComplete"));
        boolean mastersComplete=Boolean.TRUE.equals(row.getSourceAttributes().get("mastersComplete"));
        if (!servicesComplete) warnings.add("SERVICES_PARTIAL");
        if (!mastersComplete) warnings.add("MASTERS_PARTIAL");
        boolean verify=!"OBSERVED".equals(row.getObservationState()) || "UNKNOWN".equals(row.getKind()) || "UNKNOWN".equals(row.getAppointmentState())
                || !servicesComplete || !mastersComplete || !fields(row.getSourceAttributes()).containsAll(Set.of("time","kind","client","appointmentState"));
        if (verify) warnings.add("APPOINTMENT_REQUIRES_VERIFICATION");
        return new CrmAppointmentDto(row.getId(),project.getId(),project.getWorkspace().getId(),row.getExternalId(),row.getKind(),
                client==null ? null : client.getId(),client==null || client.getClient()==null ? null : client.getClient().getId(),row.getClientIdentityKind(),row.getExternalClientId(),Map.copyOf(row.getClientSnapshot()),
                row.getStartLocal(),row.getEndLocal(),project.getZoneId(),project.getZoneRevision(),row.getDerivedZoneRevision(),current ? row.getStartAt() : null,current ? row.getEndAt() : null,
                row.getAppointmentState(),row.getConfirmationState(),row.getAttendanceState(),row.getPaymentState(),Map.copyOf(row.getProviderState()),row.getComment(),services,masters,
                servicesComplete,mastersComplete,row.getObservationState(),row.getLastObservedAt(),row.getObservationCheckedAt(),verify,List.copyOf(warnings),fields(row.getSourceAttributes()),Objects.toString(row.getSourceAttributes().get("source"),"UNKNOWN"),row.getVersion());
    }
    private Map<String,UUID> catalogIds(String entity,UUID project,List<String> externalIds) {
        var ids=externalIds.stream().filter(Objects::nonNull).distinct().toList();
        var result=new HashMap<String,UUID>();
        if (!ids.isEmpty()) for (var tuple : store.em.createQuery("select c.externalId,c.id from "+entity+" c where c.project.id=:project and c.externalId in :ids",Object[].class)
                .setParameter("project",project).setParameter("ids",ids).getResultList()) result.put((String)tuple[0],(UUID)tuple[1]);
        return result;
    }
    private static String money(BigDecimal value) { return value==null ? null : value.stripTrailingZeros().toPlainString(); }
    private static Set<String> fields(Map<String,Object> attributes) {
        var value=attributes.get("presentFields"); if (!(value instanceof Collection<?> list)) return Set.of();
        var fields=new HashSet<String>(); for (Object item : list) if (item instanceof String text) fields.add(text);
        return Set.copyOf(fields);
    }
    private static String encode(CrmAppointmentEntity row,String revision) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+revision+"|"+row.getStartLocal()+"|"+row.getId()).getBytes(StandardCharsets.UTF_8));
    }
    private static Cursor decode(String cursor,String revision) {
        if (cursor==null) return null;
        if (cursor.length()>1024) throw CrmException.invalid();
        try {
            String[] fields=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if (fields.length!=4 || !"1".equals(fields[0])) throw CrmException.invalid();
            if (!revision.equals(fields[1])) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            LocalDateTime start=LocalDateTime.parse(fields[2]);
            if (start.getYear()<2000 || start.getYear()>2100 || start.getNano()%1000!=0) throw CrmException.invalid();
            return new Cursor(start,UUID.fromString(fields[3]));
        } catch (IllegalArgumentException | DateTimeException error) { throw CrmException.invalid(); }
    }
}
