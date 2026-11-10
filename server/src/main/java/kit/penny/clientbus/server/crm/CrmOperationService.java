package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable local acceptance. No database transaction spans external I/O. */
@Service
public class CrmOperationService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String SELECT="select o.*,p.organizationid,p.workspaceid,p.provider from crm.operation o join crm.project p on p.id=o.projectid";
    private final CrmStore store;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    record Row(CrmOperationDto dto, CrmContext context, String request, CrmCommand command, String hash) {}
    private record Prepared(Row row,CrmCredentials credentials,CrmCommand command,Instant deadline) {}
    public CrmOperationService(CrmStore store,CrmRegistry registry,CrmSecretCipher cipher,UiDomainEvents events,
            JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.store=store; this.registry=registry; this.cipher=cipher; this.events=events; this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);
    }
    public CrmOperationDto create(UUID projectId,UUID key,CrmCreateAppointmentRequest request) {
        requireKey(key); request=normalize(request);
        String body=JSON.writeValueAsString(request),hash=hash("CREATE/"+body);
        var normalized=request;
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID actor=store.guard.actor();
            store.lockMappingEmployees(actor,null);
            var project=project(projectId);
            var previous=find(projectId,actor,key);
            if (previous!=null) return replay(previous,hash);
            configured(project,normalized.contextVersion(),normalized.zoneRevision(),CrmCommand.Type.CREATE);
            UUID id=UUID.randomUUID();
            var command=createCommand(project,id,key,normalized);
            return insert(project,actor,key,id,hash,body,command,null,null);
        });
    }
    public CrmOperationDto change(UUID appointmentId,UUID key,CrmChangeAppointmentRequest request,boolean cancel) {
        requireKey(key); request=normalize(request,cancel);
        var type=cancel ? CrmCommand.Type.CANCEL : CrmCommand.Type.MOVE;
        String body=JSON.writeValueAsString(request),hash=hash(type+"/"+appointmentId+"/"+body);
        var normalized=request;
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID actor=store.guard.actor(); store.lockMappingEmployees(actor,null);
            var target=store.em.find(CrmAppointmentEntity.class,appointmentId);
            if (target==null) throw CrmStore.notFound();
            var project=project(target.getProject().getId());
            var previous=find(project.getId(),actor,key);
            if (previous!=null) return replay(previous,hash);
            configured(project,normalized.contextVersion(),normalized.zoneRevision(),type);
            if (jdbc.queryForObject("select count(*) from crm.operation where targetappointmentid=? and status in ('REQUESTED','IN_FLIGHT','UNKNOWN')",Long.class,appointmentId)>0)
                throw CrmException.conflict("CRM_OPERATION_CONFLICT");
            UUID id=UUID.randomUUID();
            var command=changeCommand(project,id,key,appointmentId,normalized,type);
            return insert(project,actor,key,id,hash,body,command,appointmentId,normalized.expectedVersion());
        });
    }
    public CrmOperationDto get(UUID id) {
        store.storage.requireReady(); return tx.execute(status -> authorized(id).dto());
    }
    public CrmOperationDto byKey(UUID projectId,UUID key) {
        requireKey(key); store.storage.requireReady();
        return tx.execute(status -> {
            project(projectId); var row=find(projectId,store.guard.actor(),key);
            if (row==null) throw CrmStore.notFound(); return row.dto();
        });
    }
    public CrmOperationPageDto list(UUID projectId,boolean own,boolean active,String cursor) {
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=project(projectId); UUID actor=store.guard.actor();
            if (!own) store.guard.workspace(project.getWorkspace().getId(),true);
            String scope=hash(projectId+"/"+actor+"/"+own+"/"+active);
            var parameters=new ArrayList<Object>(); parameters.add(projectId);
            String sql=SELECT+" where o.projectid=?";
            if (own) { sql+=" and o.initiatoremployeeid=?"; parameters.add(actor); }
            if (active) sql+=" and o.status in ('REQUESTED','IN_FLIGHT','UNKNOWN')";
            if (cursor!=null) {
                try {
                    if (cursor.length()>512) throw CrmException.invalid();
                    String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n");
                    if (parts.length!=3 || !scope.equals(parts[0])) throw CrmException.invalid();
                    var time=Timestamp.from(Instant.parse(parts[1])); var id=UUID.fromString(parts[2]);
                    sql+=" and (o.createdat<? or o.createdat=? and o.id<?)"; parameters.add(time); parameters.add(time); parameters.add(id);
                } catch (IllegalArgumentException | DateTimeException error) { throw CrmException.invalid(); }
            }
            var rows=jdbc.query(sql+" order by o.createdat desc,o.id desc limit 51",this::map,parameters.toArray());
            var items=rows.stream().limit(50).map(Row::dto).toList(); boolean more=rows.size()>50;
            String next=more ? Base64.getUrlEncoder().withoutPadding().encodeToString((scope+"\n"+items.getLast().createdAt()+"\n"+items.getLast().id()).getBytes(StandardCharsets.UTF_8)) : null;
            return new CrmOperationPageDto(items,next,more);
        });
    }
    public CrmAvailabilityDto availability(UUID projectId,CrmAvailabilityRequest request) {
        store.storage.requireReady();
        if (request==null || request.date()==null || request.date().getYear()<2000 || request.date().getYear()>2100) throw CrmException.invalid();
        var input=normalize(new CrmCreateAppointmentRequest(request.contextVersion(),request.zoneRevision(),new UUID(0,0),request.masterId(),request.offers(),request.date().atStartOfDay(),null));
        record Read(CrmContext context,CrmCredentials credentials,String master,List<CrmCommand.Offer> offers,String zone) {}
        var read=tx.execute(status -> {
            var project=project(projectId);
            var type=registry.require(project.getProvider()).commandCapabilities().contains(CrmCommand.Type.CREATE) ? CrmCommand.Type.CREATE : CrmCommand.Type.MOVE;
            configured(project,input.contextVersion(),input.zoneRevision(),type);
            return new Read(store.context(project),store.credentials(project.getConnection()),master(project,input.masterId()).getExternalId(),offers(project,input.masterId(),input.offers()),project.getZoneId());
        });
        CrmAvailability result;
        try (var access=read.credentials().open(cipher)) {
            result=registry.require(read.credentials().provider).availability(access,read.context().externalProjectId(),read.master(),serviceIds(read.offers()),request.date(),Instant.now().plusSeconds(30));
        } catch (CrmFailure failure) { throw CrmStore.failure(failure); }
        validateAvailability(result,read.context().externalProjectId(),read.master(),read.offers(),request.date());
        tx.executeWithoutResult(status -> { store.requireContext(read.context()); store.guard.workspace(read.context().workspaceId(),false); });
        return new CrmAvailabilityDto(projectId,input.masterId(),result.starts(),read.zone(),input.contextVersion(),input.zoneRevision(),result.observedAt());
    }
    /** Availability for a move uses the original appointment's services, not today's catalogue quotes. */
    public CrmAvailabilityDto moveAvailability(UUID appointmentId,CrmMoveAvailabilityRequest request) {
        store.storage.requireReady();
        if(request==null || request.date()==null || request.date().getYear()<2000 || request.date().getYear()>2100)throw CrmException.invalid();
        var input=normalize(new CrmChangeAppointmentRequest(request.contextVersion(),request.zoneRevision(),request.expectedVersion(),request.date().atTime(12,0),null),false);
        record Read(CrmContext context,CrmCredentials credentials,CrmCommand command,UUID masterId,String zone) {}
        var read=tx.execute(status -> {
            var target=store.em.find(CrmAppointmentEntity.class,appointmentId);if(target==null)throw CrmStore.notFound();
            var project=project(target.getProject().getId());
            configured(project,input.contextVersion(),input.zoneRevision(),CrmCommand.Type.MOVE);
            var command=changeCommand(project,UUID.randomUUID(),UUID.randomUUID(),appointmentId,input,CrmCommand.Type.MOVE);
            var master=store.em.createQuery("from CrmMasterEntity where project.id=:project and externalId=:external",CrmMasterEntity.class)
                    .setParameter("project",project.getId()).setParameter("external",command.externalMasterId()).getResultStream().findFirst().orElseThrow(CrmStore::notFound);
            return new Read(store.context(project),store.credentials(project.getConnection()),command,master.getId(),project.getZoneId());
        });
        CrmAvailability result;
        try(var access=read.credentials().open(cipher)) {
            result=registry.require(read.credentials().provider).availability(access,read.context().externalProjectId(),read.command().externalMasterId(),
                    serviceIds(read.command().offers()),request.date(),Instant.now().plusSeconds(30));
        } catch(CrmFailure failure) { throw CrmStore.failure(failure); }
        validateAvailability(result,read.context().externalProjectId(),read.command().externalMasterId(),read.command().offers(),request.date());
        tx.executeWithoutResult(status -> {
            store.requireContext(read.context());store.guard.workspace(read.context().workspaceId(),false);
            var target=store.em.find(CrmAppointmentEntity.class,appointmentId);if(target==null)throw CrmStore.notFound();
            store.em.refresh(target,LockModeType.PESSIMISTIC_READ);
            if(target.getVersion()!=input.expectedVersion())throw CrmException.conflict("CRM_VERSION_CONFLICT");
        });
        return new CrmAvailabilityDto(read.context().projectId(),read.masterId(),result.starts(),read.zone(),input.contextVersion(),input.zoneRevision(),result.observedAt());
    }
    /** Independent workers may preflight twice; the committed CAS grants only one effect. */
    void run(UUID id) {
        Prepared initial;
        try { initial=tx.execute(status -> prepare(id,false)); }
        catch (RuntimeException failure) { reject(id,code(failure),retryAt(failure)); return; }
        if (initial==null) return;
        try (var access=initial.credentials().open(cipher)) {
            var command=initial.command(); var provider=registry.require(initial.credentials().provider);
            CrmAvailability observed=null;
            if(command.type()==CrmCommand.Type.CANCEL)provider.prepareCommand(access,initial.row().context().externalProjectId(),command,Instant.now().plusSeconds(30));
            if (command.type()!=CrmCommand.Type.CANCEL) {
                var available=provider.availability(access,initial.row().context().externalProjectId(),command.externalMasterId(),
                        serviceIds(command.offers()),command.localStart().toLocalDate(),Instant.now().plusSeconds(30));
                validateAvailability(available,initial.row().context().externalProjectId(),command.externalMasterId(),command.offers(),command.localStart().toLocalDate());
                if (!available.starts().contains(command.localStart())) { reject(id,"CRM_SLOT_UNAVAILABLE"); return; }
                observed=available;
            }
            Prepared acquired;
            var preflight=observed;
            try { acquired=tx.execute(status -> {
                var prepared=prepare(id,true);
                // Lock waits must not turn an old availability observation into permission to write.
                if (prepared!=null && preflight!=null) validateAvailability(preflight,prepared.row().context().externalProjectId(),
                        prepared.command().externalMasterId(),prepared.command().offers(),prepared.command().localStart().toLocalDate());
                return prepared;
            }); }
            catch (RuntimeException failure) { reject(id,code(failure),retryAt(failure)); return; }
            if (acquired==null) return;
            // After this checkpoint ALL failures are uncertain unless the adapter proves rejection.
            try {
                if (!acquired.deadline().isAfter(Instant.now())) {
                    finish(id,acquired.command(),new CrmCommandResult(CrmCommandResult.Outcome.REJECTED,null),"CRM_OPERATION_EXPIRED"); return;
                }
                var result=provider.command(access,acquired.row().context().externalProjectId(),acquired.command(),acquired.deadline(),new CrmCommandPort.Progress() {
                    @Override public void reserved(String reference) { reservation(id,reference); }
                    @Override public void observed(String snapshot) { observation(id,snapshot); }
                });
                finish(id,acquired.command(),result,null);
            } catch (RuntimeException failure) { finish(id,acquired.command(),null,code(failure)); }
        } catch (RuntimeException failure) { reject(id,code(failure),retryAt(failure)); }
    }
    public CrmOperationDto reconcile(UUID id) {
        store.storage.requireReady();
        var prepared=tx.execute(status -> {
            var row=authorized(id);
            if (!"UNKNOWN".equals(row.dto().status())) return null;
            var project=project(row.dto().projectId());
            if (!project.getExternalId().equals(row.context().externalProjectId())) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            var credentials=store.credentials(project.getConnection());
            var leases=jdbc.query("update crm.operation set reconcileuntil=clock_timestamp()+interval '90 seconds' where id=? and status='UNKNOWN' and (reconcileuntil is null or reconcileuntil<clock_timestamp()) returning reconcileuntil",(rs,index) -> rs.getTimestamp(1).toInstant(),id);
            if (leases.isEmpty()) return null;
            var command=row.command();
            if (command.type()==CrmCommand.Type.CREATE && row.dto().externalAppointmentId()!=null) command=command.withExternalReference(row.dto().externalAppointmentId());
            return new Prepared(row,credentials,command,leases.getFirst().minusSeconds(15));
        });
        if (prepared==null) return get(id);
        try (var access=prepared.credentials().open(cipher)) {
            var result=registry.require(prepared.credentials().provider).reconcile(access,prepared.row().context().externalProjectId(),prepared.command(),prepared.deadline());
            finish(id,prepared.command(),result,null);
        } catch (RuntimeException failure) { finish(id,prepared.command(),null,code(failure)); }
        finally { jdbc.update("update crm.operation set reconcileuntil=null where id=? and reconcileuntil=?",id,Timestamp.from(prepared.deadline().plusSeconds(15))); }
        return get(id);
    }
    List<UUID> due() {
        store.storage.requireReady();
        var expiredProjects=jdbc.queryForList("select distinct projectid from crm.operation where status='IN_FLIGHT' and deadlineat<clock_timestamp() or status='REQUESTED' and createdat<clock_timestamp()-interval '5 minutes' order by projectid limit 32",UUID.class);
        for(var project:expiredProjects)tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from crm.project where id=? for update",UUID.class,project);
            int changed=jdbc.update("update crm.operation set status='UNKNOWN',errorcode='CRM_RESULT_UNKNOWN',updatedat=clock_timestamp(),version=version+1 where projectid=? and status='IN_FLIGHT' and deadlineat<clock_timestamp()",project);
            if(changed>0)new CrmJournalStore(jdbc).invalidate(project);
            int expired=jdbc.update("update crm.operation set status='REJECTED',errorcode='CRM_OPERATION_EXPIRED',updatedat=clock_timestamp(),version=version+1 where projectid=? and status='REQUESTED' and createdat<clock_timestamp()-interval '5 minutes'",project);
            if(changed+expired>0)invalidate(project);
        });
        return jdbc.queryForList("select id from crm.operation where status='REQUESTED' order by createdat,id limit 32",UUID.class);
    }
    /** Durable post-effect observation. Old-context results never start reads under a new binding. */
    void queueConfirmedReads() {
        store.storage.requireReady();
        for (UUID id:jdbc.queryForList("select id from crm.operation where status='CONFIRMED' and refreshprocessedat is null order by updatedat,id limit 16",UUID.class)) {
            try {
                tx.executeWithoutResult(status -> {
                    var row=row(id); var project=store.requireContext(row.context());
                    store.guard.workspace(row.dto().initiatorEmployeeId(),row.context().workspaceId());
                    if (jdbc.queryForObject("select refreshprocessedat from crm.operation where id=? for update",Timestamp.class,id)!=null) return;
                    if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from crm.syncstate where projectid=? and dataset='WRITER:APPOINTMENTS' and claimuntil>clock_timestamp())",Boolean.class,project.getId()))) return;
                    var days=new HashSet<LocalDate>(); days.add(row.command().localStart().toLocalDate());
                    if (row.command().type()==CrmCommand.Type.MOVE) {
                        String original=JSON.readTree(row.command().preservedSnapshot()).path("appointment").path("startlocal").asString("");
                        if (original.length()>=10) days.add(LocalDate.parse(original.substring(0,10)));
                    }
                    var scopes=new ArrayList<CrmAppointmentScope>();
                    for(var day:days)scopes.add(new CrmAppointmentScope("APPOINTMENTS",null,day,day));
                    if(row.dto().externalAppointmentId()!=null) {
                        var day=row.command().localStart().toLocalDate();
                        scopes.add(new CrmAppointmentScope("APPOINTMENTS",null,day,day,row.dto().externalAppointmentId()));
                    }
                    for (var scope:scopes) {
                        var fields=new LinkedHashMap<>(scope.fields()); fields.put("refreshActorId",row.dto().initiatorEmployeeId().toString());
                        jdbc.update("""
                                insert into crm.syncstate(id,version,createdat,updatedat,projectid,statekind,writedomain,dataset,scopekey,scope,
                                    generation,connectionid,credentialversion,contextversion,zonerevision,status,successfulcoverage,attemptcoverage,lastattemptat)
                                values (?,0,clock_timestamp(),clock_timestamp(),?,'SCOPE','APPOINTMENTS','APPOINTMENTS',?,cast(? as jsonb),0,?,?,?,?,'IDLE','{}'::jsonb,'{}'::jsonb,clock_timestamp()-interval '1 day')
                                on conflict(projectid,dataset,scopekey) do update set scope=excluded.scope,lastattemptat=excluded.lastattemptat,updatedat=clock_timestamp(),version=crm.syncstate.version+1
                                """,UUID.randomUUID(),project.getId(),scope.key(),JSON.writeValueAsString(fields),row.context().connectionId(),row.context().credentialVersion(),row.context().contextVersion(),row.context().zoneRevision());
                    }
                    jdbc.update("update crm.operation set refreshprocessedat=clock_timestamp() where id=?",id);
                });
            } catch (CrmException | AccessDeniedException oldContext) {
                // Reading the original operation remains possible. No command is replayed or projected into a new context.
                jdbc.update("update crm.operation set refreshprocessedat=clock_timestamp() where id=?",id);
            }
        }
    }
    private Prepared prepare(UUID id,boolean beginEffect) {
        var row=row(id); if (!"REQUESTED".equals(row.dto().status())) return null;
        var project=store.requireContext(row.context());
        store.guard.workspace(row.dto().initiatorEmployeeId(),project.getWorkspace().getId());
        if (row.dto().createdAt().isBefore(Instant.now().minusSeconds(300))) throw CrmException.conflict("CRM_OPERATION_EXPIRED");
        configured(project,row.dto().contextVersion(),row.dto().zoneRevision(),row.command().type());
        CrmCommand command=row.command().type()==CrmCommand.Type.CREATE
                ? createCommand(project,id,row.dto().idempotencyKey(),JSON.readValue(row.request(),CrmCreateAppointmentRequest.class))
                : changeCommand(project,id,row.dto().idempotencyKey(),row.dto().appointmentId(),JSON.readValue(row.request(),CrmChangeAppointmentRequest.class),row.command().type());
        Instant deadline=null;
        if (beginEffect) {
            var acquired=jdbc.query("update crm.operation set status='IN_FLIGHT',effectstartedat=clock_timestamp(),deadlineat=clock_timestamp()+interval '90 seconds',updatedat=clock_timestamp(),version=version+1 where id=? and status='REQUESTED' returning deadlineat",(rs,index) -> rs.getTimestamp(1).toInstant(),id);
            if (acquired.isEmpty()) return null;
            new CrmJournalStore(jdbc).invalidate(project.getId());
            deadline=acquired.getFirst().minusSeconds(15);
        }
        return new Prepared(row,store.credentials(project.getConnection()),command,deadline);
    }
    private void finish(UUID id,CrmCommand command,CrmCommandResult result,String error) {
        String state="UNKNOWN",reference=null;
        if (result!=null && result.outcome()!=null) {
            if(result.errorCode()!=null) {
                try{error=CrmStore.failure(new CrmFailure(CrmFailure.Code.valueOf(result.errorCode()),result.retryAt(),true)).code();}
                catch(IllegalArgumentException invalid){error="CRM_INVALID_RESPONSE";}
            }
            reference=result.externalAppointmentId();
            if (reference!=null && (reference.isBlank() || reference.length()>128 || command.type()!=CrmCommand.Type.CREATE && !reference.equals(command.externalAppointmentId()))) { error="CRM_INVALID_RESPONSE"; reference=null; }
            else if (result.outcome()==CrmCommandResult.Outcome.CONFIRMED && reference!=null
                    && (command.type()==CrmCommand.Type.CREATE || reference.equals(command.externalAppointmentId()))) state="CONFIRMED";
            else if (result.outcome()==CrmCommandResult.Outcome.REJECTED) state="REJECTED";
        }
        if ("UNKNOWN".equals(state) && error==null) error="CRM_RESULT_UNKNOWN";
        final String outcome=state,ref=reference,code=error;
        final Instant retryAt=result==null?null:result.retryAt();
        tx.executeWithoutResult(status -> {
            UUID projectId=jdbc.queryForObject("select projectid from crm.operation where id=?",UUID.class,id);
            jdbc.queryForObject("select id from crm.project where id=? for update",UUID.class,projectId);
            String known=jdbc.queryForObject("select externalreference from crm.operation where id=? for update",String.class,id);
            boolean mismatch=known!=null && ref!=null && !known.equals(ref);
            var projects=jdbc.queryForList("update crm.operation set status=?,externalreference=coalesce(?,externalreference),errorcode=?,retryat=?,updatedat=clock_timestamp(),version=version+1 where id=? and status in ('IN_FLIGHT','UNKNOWN') returning projectid",UUID.class,mismatch?"UNKNOWN":outcome,mismatch?null:ref,mismatch?"CRM_INVALID_RESPONSE":code,retryAt==null?null:Timestamp.from(retryAt),id);
            projects.forEach(project->{new CrmJournalStore(jdbc).invalidate(project);invalidate(project);});
        });
    }
    private void reservation(UUID id,String reference) {
        if(reference==null || !reference.matches("[1-9][0-9]{0,19}")) throw CrmException.conflict("CRM_INVALID_RESPONSE");
        tx.executeWithoutResult(status -> {
            int updated=jdbc.update("update crm.operation set externalreference=?,updatedat=clock_timestamp(),version=version+1 where id=? and type='CREATE' and status='IN_FLIGHT' and deadlineat>clock_timestamp() and (externalreference is null or externalreference=?)",reference,id,reference);
            if(updated!=1)throw CrmException.conflict("CRM_OPERATION_CONFLICT");
        });
    }
    private void observation(UUID id,String snapshot) {
        if(snapshot==null || snapshot.length()>1_000_000)throw CrmException.conflict("CRM_INVALID_RESPONSE");
        var observed=JSON.readTree(snapshot);
        if(!observed.isObject())throw CrmException.conflict("CRM_INVALID_RESPONSE");
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from crm.operation where id=? for update",UUID.class,id);
            var current=row(id);
            if(current.command().type()==CrmCommand.Type.CREATE)throw CrmException.conflict("CRM_OPERATION_CONFLICT");
            var document=(tools.jackson.databind.node.ObjectNode)JSON.readTree(current.command().preservedSnapshot());
            if(document.has("providerBefore") && !document.path("providerBefore").equals(observed))throw CrmException.conflict("CRM_OPERATION_CONFLICT");
            document.set("providerBefore",observed);
            var command=current.command().withPreservedSnapshot(document.toString());
            int changed=jdbc.update("update crm.operation set payload=cast(? as jsonb),updatedat=clock_timestamp(),version=version+1 where id=? and status='IN_FLIGHT' and deadlineat>clock_timestamp()",
                    JSON.writeValueAsString(command),id);
            if(changed!=1)throw CrmException.conflict("CRM_OPERATION_CONFLICT");
        });
    }
    private void reject(UUID id,String code) {
        reject(id,code,null);
    }
    private void reject(UUID id,String code,Instant retryAt) {
        tx.executeWithoutResult(status -> jdbc.queryForList("update crm.operation set status='REJECTED',errorcode=?,retryat=?,updatedat=clock_timestamp(),version=version+1 where id=? and status='REQUESTED' returning projectid",UUID.class,code,retryAt==null?null:Timestamp.from(retryAt),id).forEach(this::invalidate));
    }
    private CrmOperationDto insert(CrmProjectEntity project,UUID actor,UUID key,UUID id,String hash,String body,CrmCommand command,UUID target,Long expected) {
        var context=store.context(project);
        jdbc.update("""
                insert into crm.operation(id,version,createdat,updatedat,projectid,initiatoremployeeid,idempotencykey,requesthash,type,
                    targetappointmentid,expectedversion,connectionid,credentialversion,contextversion,zonerevision,externalprojectid,requestbody,payload,status)
                values (?,0,clock_timestamp(),clock_timestamp(),?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),'REQUESTED')
                """,id,project.getId(),actor,key,hash,command.type().name(),target,expected,context.connectionId(),context.credentialVersion(),context.contextVersion(),context.zoneRevision(),context.externalProjectId(),body,JSON.writeValueAsString(command));
        invalidate(project.getId()); return row(id).dto();
    }
    private CrmCommand createCommand(CrmProjectEntity project,UUID id,UUID key,CrmCreateAppointmentRequest request) {
        time(request.localStart(),project.getZoneId());
        var client=store.em.find(CrmClientEntity.class,request.crmClientId());
        if (client==null || !client.getProject().getId().equals(project.getId())) throw CrmStore.notFound();
        if (!"PROJECT".equals(client.getIdentityKind())) throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
        return new CrmCommand(CrmCommand.Type.CREATE,id,key,null,new CrmClientReference("PROJECT",client.getExternalId()),
                master(project,request.masterId()).getExternalId(),offers(project,request.masterId(),request.offers()),request.localStart(),project.getZoneId(),request.comment(),"{}");
    }
    private CrmCommand changeCommand(CrmProjectEntity project,UUID id,UUID key,UUID target,CrmChangeAppointmentRequest request,CrmCommand.Type type) {
        var row=store.em.find(CrmAppointmentEntity.class,target); if (row==null || !row.getProject().getId().equals(project.getId())) throw CrmStore.notFound();
        store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
        if (row.getVersion()!=request.expectedVersion()) throw CrmException.conflict("CRM_VERSION_CONFLICT");
        if (!"APPOINTMENT".equals(row.getKind()) || !"SCHEDULED".equals(row.getAppointmentState()) || !"PROJECT".equals(row.getClientIdentityKind())) throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
        var services=store.em.createQuery("from CrmAppointmentServiceEntity s where s.appointment.id=:id order by s.position",CrmAppointmentServiceEntity.class).setParameter("id",target).getResultList();
        var masters=store.em.createQuery("from CrmAppointmentMasterEntity m where m.appointment.id=:id order by m.position",CrmAppointmentMasterEntity.class).setParameter("id",target).getResultList();
        if (type==CrmCommand.Type.MOVE && (!Boolean.TRUE.equals(row.getSourceAttributes().get("servicesComplete"))
                || !Boolean.TRUE.equals(row.getSourceAttributes().get("mastersComplete")) || masters.size()!=1 || services.isEmpty())) throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
        var offers=new ArrayList<CrmCommand.Offer>();
        for (var service:services) {
            if (type==CrmCommand.Type.MOVE && (service.getExternalServiceId()==null || service.getPrice()==null || service.getDurationSeconds()==null)) throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
            if (service.getExternalServiceId()!=null && service.getPrice()!=null && service.getDurationSeconds()!=null) offers.add(new CrmCommand.Offer(service.getExternalServiceId(),service.getPrice(),service.getCurrency(),service.getDurationSeconds()));
        }
        var start=type==CrmCommand.Type.MOVE ? request.localStart() : row.getStartLocal(); time(start,project.getZoneId());
        // Raw numeric JSON is retained as text: no untyped Double conversion of financial values.
        String snapshot=jdbc.queryForObject("""
                select jsonb_build_object('appointment',to_jsonb(a),'services',coalesce((select jsonb_agg(to_jsonb(s) order by position) from crm.appointmentservice s where s.appointmentid=a.id),'[]'::jsonb),
                    'masters',coalesce((select jsonb_agg(to_jsonb(m) order by position) from crm.appointmentmaster m where m.appointmentid=a.id),'[]'::jsonb))::text from crm.appointment a where a.id=?
                """,String.class,target);
        return new CrmCommand(type,id,key,row.getExternalId(),new CrmClientReference(row.getClientIdentityKind(),row.getExternalClientId()),
                masters.size()==1 ? masters.getFirst().getExternalMasterId() : null,offers,start,project.getZoneId(),request.reason(),snapshot);
    }
    private CrmMasterEntity master(CrmProjectEntity project,UUID id) {
        var row=store.em.find(CrmMasterEntity.class,id);
        if (row==null || !row.getProject().getId().equals(project.getId())) throw CrmStore.notFound();
        if (!"OBSERVED".equals(row.getObservationState())) throw CrmException.conflict("CRM_VERSION_CONFLICT"); return row;
    }
    private List<CrmCommand.Offer> offers(CrmProjectEntity project,UUID master,List<CrmCreateAppointmentRequest.Offer> choices) {
        var ids=new HashSet<UUID>(); var result=new ArrayList<CrmCommand.Offer>();
        for (var choice:choices) {
            if (!ids.add(choice.id())) throw CrmException.invalid();
            var offer=store.em.find(CrmMasterServiceEntity.class,choice.id());
            if (offer==null || !offer.getProject().getId().equals(project.getId()) || !offer.getMaster().getId().equals(master)) throw CrmStore.notFound();
            if (offer.getVersion()!=choice.expectedVersion() || !"OBSERVED".equals(offer.getObservationState())) throw CrmException.conflict("CRM_VERSION_CONFLICT");
            if (offer.getPrice()==null || offer.getCurrency()==null || offer.getDurationSeconds()==null || offer.getDurationSeconds()<=0
                    || Boolean.FALSE.equals(offer.getOfferState().get("active"))) throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");
            result.add(new CrmCommand.Offer(offer.getService().getExternalId(),offer.getPrice(),offer.getCurrency(),offer.getDurationSeconds()));
        }
        return List.copyOf(result);
    }
    private CrmProjectEntity project(UUID id) {
        var project=store.project(id,store.guard.organization());
        UUID connection=project.getConnection().getId();
        store.em.refresh(project.getConnection(),LockModeType.PESSIMISTIC_READ);
        store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
        if (!connection.equals(project.getConnection().getId())) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        store.guard.workspace(project.getWorkspace().getId(),false); return project;
    }
    private void configured(CrmProjectEntity project,long context,long zone,CrmCommand.Type type) {
        CrmStore.requireMutable(project);
        if (project.getContextVersion()!=context || project.getZoneRevision()!=zone) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        if (!project.isEnabled() || project.getZoneId()==null) throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");
        if (project.getConnection().getStatus()!=kit.penny.clientbus.common.enums.CrmConnectionStatus.CONNECTED) throw CrmException.conflict("CRM_REAUTH_REQUIRED");
        if (!registry.require(project.getProvider()).commandCapabilities().contains(type)) throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
        store.requireNetwork(project);
    }
    private Row authorized(UUID id) {
        var row=row(id); var project=project(row.dto().projectId());
        if (!row.dto().initiatorEmployeeId().equals(store.guard.actor())) store.guard.workspace(project.getWorkspace().getId(),true);
        return row;
    }
    private Row find(UUID project,UUID actor,UUID key) {
        return jdbc.query(SELECT+" where o.projectid=? and o.initiatoremployeeid=? and o.idempotencykey=?",this::map,project,actor,key).stream().findFirst().orElse(null);
    }
    private Row row(UUID id) { return jdbc.query(SELECT+" where o.id=?",this::map,id).stream().findFirst().orElseThrow(CrmStore::notFound); }
    private Row map(ResultSet rs,int index) throws SQLException {
        UUID project=rs.getObject("projectid",UUID.class),actor=rs.getObject("initiatoremployeeid",UUID.class);
        var dto=new CrmOperationDto(rs.getObject("id",UUID.class),project,rs.getString("type"),rs.getObject("targetappointmentid",UUID.class),actor,rs.getObject("idempotencykey",UUID.class),
                rs.getLong("contextversion"),rs.getLong("zonerevision"),rs.getString("status"),rs.getTimestamp("createdat").toInstant(),rs.getTimestamp("updatedat").toInstant(),rs.getString("errorcode"),rs.getString("externalreference"),rs.getTimestamp("retryat")==null?null:rs.getTimestamp("retryat").toInstant());
        var context=new CrmContext(project,rs.getObject("organizationid",UUID.class),rs.getObject("workspaceid",UUID.class),rs.getString("provider"),rs.getString("externalprojectid"),rs.getObject("connectionid",UUID.class),rs.getLong("credentialversion"),dto.contextVersion(),dto.zoneRevision());
        return new Row(dto,context,rs.getString("requestbody"),JSON.readValue(rs.getString("payload"),CrmCommand.class),rs.getString("requesthash"));
    }
    private CrmOperationDto replay(Row previous,String hash) {
        if (!previous.hash().equals(hash)) throw CrmException.conflict("CRM_IDEMPOTENCY_CONFLICT"); return previous.dto();
    }
    private void invalidate(UUID project) {
        var row=store.em.find(CrmProjectEntity.class,project); if (row!=null) events.workspace(row.getWorkspace(),"crm.invalidated");
    }
    private static void requireKey(UUID key) { if (key==null) throw CrmException.invalid(); }
    private static String text(String value) { return value==null || value.isBlank() ? null : value.strip(); }
    private static CrmCreateAppointmentRequest normalize(CrmCreateAppointmentRequest r) {
        if (r==null || r.contextVersion()==null || r.contextVersion()<0 || r.zoneRevision()==null || r.zoneRevision()<0 || r.crmClientId()==null || r.masterId()==null
                || r.localStart()==null || r.offers()==null || r.offers().isEmpty() || r.offers().size()>10 || r.comment()!=null && r.comment().length()>2000
                || r.offers().stream().anyMatch(o -> o==null || o.id()==null || o.expectedVersion()==null || o.expectedVersion()<0)) throw CrmException.invalid();
        return new CrmCreateAppointmentRequest(r.contextVersion(),r.zoneRevision(),r.crmClientId(),r.masterId(),List.copyOf(r.offers()),r.localStart(),text(r.comment()));
    }
    private static CrmChangeAppointmentRequest normalize(CrmChangeAppointmentRequest r,boolean cancel) {
        if (r==null || r.contextVersion()==null || r.contextVersion()<0 || r.zoneRevision()==null || r.zoneRevision()<0 || r.expectedVersion()==null || r.expectedVersion()<0
                || cancel && r.localStart()!=null || !cancel && (r.localStart()==null || r.reason()!=null) || r.reason()!=null && r.reason().length()>2000) throw CrmException.invalid();
        return new CrmChangeAppointmentRequest(r.contextVersion(),r.zoneRevision(),r.expectedVersion(),r.localStart(),text(r.reason()));
    }
    private static void time(LocalDateTime time,String zone) {
        if (time==null || time.getYear()<2000 || time.getYear()>2100 || time.getNano()!=0 || zone==null
                || ZoneId.of(zone).getRules().getValidOffsets(time).size()!=1) throw CrmException.invalid();
    }
    private static List<String> serviceIds(List<CrmCommand.Offer> offers) { return offers.stream().map(CrmCommand.Offer::externalServiceId).toList(); }
    private static void validateAvailability(CrmAvailability a,String project,String master,List<CrmCommand.Offer> offers,LocalDate date) {
        if (a==null || !project.equals(a.externalProjectId()) || !master.equals(a.externalMasterId()) || !serviceIds(offers).equals(a.externalServiceIds()) || !date.equals(a.date())
                || a.observedAt()==null || a.observedAt().isBefore(Instant.now().minusSeconds(30)) || a.observedAt().isAfter(Instant.now().plusSeconds(5)) || a.starts().size()>5000
                || a.starts().stream().anyMatch(start -> !date.equals(start.toLocalDate()) || start.getNano()!=0)) throw CrmException.conflict("CRM_INVALID_RESPONSE");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String code(RuntimeException failure) {
        if (failure instanceof CrmException crm) return crm.code();
        if (failure instanceof CrmFailure crm) return CrmStore.failure(crm).code();
        if (failure instanceof AccessDeniedException) return "ACCESS_DENIED";
        return "CRM_TEMPORARILY_UNAVAILABLE";
    }
    private static Instant retryAt(RuntimeException failure){
        if(failure instanceof CrmException crm)return crm.retryAt();
        return failure instanceof CrmFailure crm?crm.retryAt():null;
    }
}
