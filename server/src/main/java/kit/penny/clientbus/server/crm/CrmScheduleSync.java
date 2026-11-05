package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.CrmSyncStateDto;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One durable SCHEDULE writer per project, shared by all master/period scopes. */
@Service
public class CrmScheduleSync {
    private record Claim(CrmContext context, CrmScheduleScope scope, CrmWorkIntervalQuery query,
                         UUID actorId, UUID token, long generation) {}
    private record Subscription(UUID projectId, UUID organizationId, UUID actorId, CrmScheduleScope scope) {}
    private final CrmStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final CrmScheduleWriter writer;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "crm-schedule"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    public CrmScheduleSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                           CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmScheduleWriter writer) {
        this.store=store; this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.registry=registry;
        this.cipher=cipher; this.events=events; this.writer=writer;
    }
    public CrmSyncStateDto refresh(UUID projectId, UUID masterId, LocalDate from, LocalDate to) {
        var scope = new CrmScheduleScope(masterId, from, to);
        store.storage.requireReady();
        UUID organization=store.guard.organization(), actor=store.guard.actor();
        submit(claim(projectId,organization,actor,scope,0),false);
        return state(projectId,masterId,from,to);
    }
    /** Polls saved requests after restart as well; no request SecurityContext or JWT is retained. */
    int refreshDue(long intervalMs) {
        store.storage.requireReady();
        if (intervalMs<1000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        int capacity=executor.getMaximumPoolSize()-executor.getActiveCount();
        if (capacity<=0) return 0;
        // Choose one scope per project before spending worker capacity on the oldest requests globally.
        var subscriptions=jdbc.query("""
                select projectid,organizationid,employeeid,scope from (
                select distinct on (s.projectid) s.projectid,p.organizationid,e.employeeid,s.scope,s.lastattemptat,s.scopekey
                from crm.syncstate s join crm.project p on p.id=s.projectid join crm.connection c on c.id=p.connectionid
                    join dbo.employee e on e.employeeid=coalesce(cast(s.scope->>'refreshActorId' as uuid),(
                        select legacy.employeeid from dbo.employee legacy join dbo.users legacyuser on legacyuser.userid=legacy.userid
                        where legacy.organizationid=p.organizationid and legacyuser.isenabled and legacyuser.role='SUPER_ADMIN'
                        order by legacy.employeeid limit 1)) and e.organizationid=p.organizationid
                    join dbo.users u on u.userid=e.userid
                where s.dataset='WORK_INTERVALS' and s.statekind='SCOPE' and p.enabled
                    and c.status not in ('DISABLED','REAUTH_REQUIRED') and u.isenabled
                    and (u.role='SUPER_ADMIN' or u.role='EMPLOYEE' and exists (
                        select 1 from dbo.employeeworkspace ew where ew.employeeid=e.employeeid and ew.workspaceid=p.workspaceid))
                    and s.lastattemptat<=clock_timestamp()-(
                        case when s.lasterrorcode='CRM_CONNECTION_BUSY' then least(?,10000) else ? end * interval '1 millisecond')
                    and not exists (select 1 from crm.syncstate control where control.projectid=s.projectid
                        and control.dataset='WRITER:SCHEDULE' and control.scopekey='CONTROL' and control.claimuntil>clock_timestamp())
                order by s.projectid,s.lastattemptat,s.scopekey
                ) due order by lastattemptat,projectid,scopekey limit ?
                """,(rs,index) -> {
            var saved=map(rs.getString("scope"));
            return new Subscription(rs.getObject("projectid",UUID.class),rs.getObject("organizationid",UUID.class),
                    rs.getObject("employeeid",UUID.class),new CrmScheduleScope(UUID.fromString((String)saved.get("masterId")),
                    LocalDate.parse((String)saved.get("dateFrom")),LocalDate.parse((String)saved.get("dateTo"))));
        },intervalMs,intervalMs,capacity);
        int started=0;
        for (var subscription : subscriptions) {
            try {
                if (submit(claim(subscription.projectId(),subscription.organizationId(),subscription.actorId(),subscription.scope(),intervalMs),true)) started++;
            } catch (CrmException | AccessDeniedException ignored) { /* Recheck configuration and access next poll. */ }
        }
        return started;
    }
    private Claim claim(UUID projectId,UUID organization,UUID actor,CrmScheduleScope scope,long intervalMs) {
        return tx.execute(status -> {
            var project=store.projects.findByIdAndOrganizationId(projectId,organization).orElseThrow(CrmStore::notFound);
            store.em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
            store.guard.workspace(actor,project.getWorkspace().getId());
            if (!project.isEnabled()) throw new CrmException(409,"CRM_CONFIGURATION_REQUIRED","Проект CRM отключён.");
            store.credentials(project.getConnection()); registry.require(project.getProvider());
            if (intervalMs>0 && (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from crm.syncstate where projectid=? and dataset='WORK_INTERVALS' and scopekey=?
                        and (scope->>'refreshActorId'=? or scope->>'refreshActorId' is null)
                        and lastattemptat<=clock_timestamp()-(
                            case when lasterrorcode='CRM_CONNECTION_BUSY' then least(?,10000) else ? end * interval '1 millisecond'))
                    """,Boolean.class,projectId,scope.key(),actor.toString(),intervalMs,intervalMs))
                    || project.getConnection().getStatus()==CrmConnectionStatus.REAUTH_REQUIRED)) return null;
            var master=master(projectId, scope.masterId());
            CrmContext context=store.context(project);
            Map<String,Object> subscription=new HashMap<>(scope.fields()); subscription.put("refreshActorId",actor.toString());
            initialize(context, "CONTROL", "WRITER:SCHEDULE", "CONTROL", Map.of());
            initialize(context, "SCOPE", "WORK_INTERVALS", scope.key(), subscription);
            jdbc.update("update crm.syncstate set scope=cast(? as jsonb) where projectid=? and dataset='WORK_INTERVALS' and scopekey=?",
                    JSON.writeValueAsString(subscription),projectId,scope.key());
            UUID token=UUID.randomUUID();
            var generations=jdbc.query("""
                    update crm.syncstate set generation=generation+1,version=version+1,claimtoken=?,
                        claimuntil=clock_timestamp()+interval '120 seconds',connectionid=?,credentialversion=?,
                        contextversion=?,zonerevision=?,status='RUNNING',lastattemptat=clock_timestamp(),updatedat=clock_timestamp()
                    where projectid=? and dataset='WRITER:SCHEDULE' and scopekey='CONTROL'
                        and (claimuntil is null or claimuntil<=clock_timestamp()) returning generation
                    """, (rs,index) -> rs.getLong(1), token, context.connectionId(), context.credentialVersion(),
                    context.contextVersion(), context.zoneRevision(), projectId);
            if (generations.isEmpty()) {
                if (intervalMs>0) return null;
                Boolean same=jdbc.queryForObject("""
                        select exists(select 1 from crm.syncstate s join crm.syncstate c on c.projectid=s.projectid
                            and c.dataset='WRITER:SCHEDULE' and c.scopekey='CONTROL' and c.claimtoken=s.claimtoken
                            and c.generation=s.generation and c.claimuntil>clock_timestamp()
                            where s.projectid=? and s.dataset='WORK_INTERVALS' and s.scopekey=?)
                        """, Boolean.class, projectId, scope.key());
                if (!Boolean.TRUE.equals(same)) throw new CrmException(429,"CRM_RATE_LIMITED","График проекта уже обновляется. Повторите позже.");
                return null;
            }
            long generation=generations.getFirst();
            jdbc.update("""
                    update crm.syncstate set generation=?,version=version+1,claimtoken=?,connectionid=?,credentialversion=?,
                        contextversion=?,zonerevision=?,status='QUEUED',lastattemptat=clock_timestamp(),
                        lasterrorcode=null,attemptcoverage='{}'::jsonb,updatedat=clock_timestamp()
                    where projectid=? and dataset='WORK_INTERVALS' and scopekey=?
                    """, generation,token,context.connectionId(),context.credentialVersion(),context.contextVersion(),
                    context.zoneRevision(),projectId,scope.key());
            return new Claim(context,scope,scope.query(master.getExternalId()),actor,token,generation);
        });
    }
    private boolean submit(Claim claim,boolean periodic) {
        if (claim != null) {
            try { executor.execute(() -> run(claim)); return true; }
            catch (RejectedExecutionException error) {
                var busy=CrmStore.failure(new CrmFailure(CrmFailure.Code.CONNECTION_BUSY));
                fail(claim,busy.code());
                if (!periodic) throw busy;
            }
        }
        return false;
    }
    CrmMasterEntity master(UUID projectId, UUID id) {
        return store.masters.findById(id).filter(row -> row.getProject().getId().equals(projectId)).orElseThrow(CrmStore::notFound);
    }
    private void initialize(CrmContext context,String kind,String dataset,String key,Map<String,Object> scope) {
        jdbc.update("""
                insert into crm.syncstate (id,version,createdat,updatedat,projectid,statekind,writedomain,dataset,scopekey,
                    scope,generation,connectionid,credentialversion,contextversion,zonerevision,status,successfulcoverage,attemptcoverage)
                values (?,0,clock_timestamp(),clock_timestamp(),?,?,'SCHEDULE',?,?,cast(? as jsonb),0,?,?,?,?,'IDLE','{}'::jsonb,'{}'::jsonb)
                on conflict (projectid,dataset,scopekey) do nothing
                """,UUID.randomUUID(),context.projectId(),kind,dataset,key,JSON.writeValueAsString(scope),context.connectionId(),
                context.credentialVersion(),context.contextVersion(),context.zoneRevision());
    }
    private void run(Claim claim) {
        var health=new AtomicReference<CrmHealthCheck>();
        try {
            CrmCredentials credentials=tx.execute(status -> {
                var project=store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(),project.getWorkspace().getId()); requireClaim(claim);
                jdbc.update("update crm.syncstate set status='RUNNING',version=version+1,updatedat=clock_timestamp() where projectid=? and dataset='WORK_INTERVALS' and scopekey=? and generation=? and claimtoken=?",
                        project.getId(),claim.scope().key(),claim.generation(),claim.token());
                health.set(CrmHealthCheck.begin(project.getConnection()));
                return store.credentials(project.getConnection());
            });
            CrmScheduleSnapshot result;
            try (var access=credentials.open(cipher)) {
                if (Thread.currentThread().isInterrupted()) throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
                result=registry.require(credentials.provider).readWorkIntervals(access,claim.context().externalProjectId(),claim.query());
            }
            if (result == null || !claim.context().externalProjectId().equals(result.externalProjectId()) || !claim.query().equals(result.scope()))
                throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
            if (result.observedAt() == null || result.completeness() == null || result.observedAt().isAfter(Instant.now().plusSeconds(300)))
                throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
            if (Thread.currentThread().isInterrupted()) throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
            tx.executeWithoutResult(status -> {
                var project=store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(),project.getWorkspace().getId()); requireClaim(claim);
                writer.apply(project,master(project.getId(),claim.scope().masterId()),result,claim.generation());
                health.get().apply(project.getConnection(),CrmConnectionStatus.CONNECTED,null);
                store.em.flush(); requireClaim(claim);
                boolean complete=result.completeness() == CrmReadResult.Completeness.COMPLETE;
                Map<String,Object> coverage=new HashMap<>(claim.scope().fields());
                coverage.put("completeness",result.completeness().name()); coverage.put("observedAt",result.observedAt().toString());
                coverage.put("items",result.items().size());
                String document=JSON.writeValueAsString(coverage);
                if (release(claim,"SUCCEEDED") != 1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                int changed=jdbc.update("""
                        update crm.syncstate set status=?,version=version+1,lastsuccessat=?,lasterrorcode=null,claimtoken=null,
                            successfulcoverage=case when ? then cast(? as jsonb) else successfulcoverage end,
                            attemptcoverage=cast(? as jsonb),updatedat=clock_timestamp()
                        where projectid=? and dataset='WORK_INTERVALS' and scopekey=? and generation=? and claimtoken=?
                        """,complete ? "SUCCEEDED" : "PARTIAL",Timestamp.from(result.observedAt()),complete,document,document,
                        project.getId(),claim.scope().key(),claim.generation(),claim.token());
                if (changed != 1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                events.workspace(project.getWorkspace(),"crm.invalidated");
            });
        } catch (CrmFailure error) { fail(claim,CrmStore.failure(error).code(),health.get()); }
        catch (CrmException error) { fail(claim,error.code(),health.get()); }
        catch (AccessDeniedException error) { fail(claim,"ACCESS_DENIED",health.get()); }
        catch (RuntimeException error) { fail(claim,"CRM_TEMPORARILY_UNAVAILABLE",health.get()); }
    }
    private void requireClaim(Claim claim) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from crm.syncstate where projectid=? and dataset='WRITER:SCHEDULE' and scopekey='CONTROL'
                    and generation=? and claimtoken=? and claimuntil>clock_timestamp())
                """,Boolean.class,claim.context().projectId(),claim.generation(),claim.token())))
            throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private int release(Claim claim,String status) {
        return jdbc.update("""
                update crm.syncstate set status=?,claimtoken=null,claimuntil=null,version=version+1,updatedat=clock_timestamp()
                where projectid=? and dataset='WRITER:SCHEDULE' and scopekey='CONTROL' and generation=? and claimtoken=?
                    and claimuntil>clock_timestamp()
                """,status,claim.context().projectId(),claim.generation(),claim.token());
    }
    private void fail(Claim claim,String code) {
        fail(claim,code,null);
    }
    private void fail(Claim claim,String code,CrmHealthCheck health) {
        try {
            tx.executeWithoutResult(status -> {
                var project=store.requireContext(claim.context());
                if (release(claim,"FAILED") != 1) return;
                jdbc.update("""
                        update crm.syncstate set status='FAILED',lasterrorcode=?,claimtoken=null,version=version+1,updatedat=clock_timestamp()
                        where projectid=? and dataset='WORK_INTERVALS' and scopekey=? and generation=? and claimtoken=?
                        """,code,project.getId(),claim.scope().key(),claim.generation(),claim.token());
                if (health!=null && "CRM_REAUTH_REQUIRED".equals(code)) health.apply(project.getConnection(),CrmConnectionStatus.REAUTH_REQUIRED,code);
                events.workspace(project.getWorkspace(),"crm.invalidated");
            });
        } catch (RuntimeException ignored) { /* The old context/lease can no longer publish a result. */ }
    }
    public CrmSyncStateDto state(UUID projectId,UUID masterId,LocalDate from,LocalDate to) {
        var scope=new CrmScheduleScope(masterId,from,to);
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=store.project(projectId,store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ); master(projectId,masterId);
            return state(project,scope);
        });
    }
    CrmSyncStateDto state(CrmProjectEntity project,CrmScheduleScope scope) {
        var rows=jdbc.query("""
                select s.*,exists(select 1 from crm.syncstate c where c.projectid=s.projectid and c.dataset='WRITER:SCHEDULE'
                    and c.claimtoken=s.claimtoken and c.generation=s.generation and c.claimuntil>clock_timestamp()) as active
                from crm.syncstate s where s.projectid=? and s.dataset='WORK_INTERVALS' and s.scopekey=?
                """,(rs,index) -> {
            Instant attempt=rs.getTimestamp("lastattemptat") == null ? null : rs.getTimestamp("lastattemptat").toInstant();
            Instant success=rs.getTimestamp("lastsuccessat") == null ? null : rs.getTimestamp("lastsuccessat").toInstant();
            String status=rs.getString("status"),error=rs.getString("lasterrorcode");
            if (Set.of("QUEUED","RUNNING").contains(status) && !rs.getBoolean("active")) { status="FAILED"; error="CRM_REFRESH_EXPIRED"; }
            Map<String,Object> successful=map(rs.getString("successfulcoverage")), attempted=map(rs.getString("attemptcoverage"));
            String completeness=String.valueOf(attempted.getOrDefault("completeness","UNKNOWN"));
            var connection=project.getConnection();
            boolean current=project.isEnabled() && connection.getStatus() == CrmConnectionStatus.CONNECTED
                    && connection.getId().equals(rs.getObject("connectionid",UUID.class))
                    && connection.getCredentialVersion() == rs.getLong("credentialversion")
                    && project.getContextVersion() == rs.getLong("contextversion") && project.getZoneRevision() == rs.getLong("zonerevision")
                    && "SUCCEEDED".equals(status) && success != null && success.isAfter(Instant.now().minusSeconds(300));
            return new CrmSyncStateDto("WORK_INTERVALS",status,attempt,success,success == null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",
                    completeness,error,rs.getLong("generation"),scope.fields(),successful,attempted);
        },project.getId(),scope.key());
        return rows.isEmpty() ? new CrmSyncStateDto("WORK_INTERVALS","IDLE",null,null,"NEVER_LOADED","UNKNOWN",null,0,scope.fields(),Map.of(),Map.of()) : rows.getFirst();
    }
    private static Map<String,Object> map(String json) { return JSON.readValue(json,new TypeReference<>() {}); }
    long revision(UUID projectId) {
        return jdbc.query("select version from crm.syncstate where projectid=? and dataset='WRITER:SCHEDULE' and scopekey='CONTROL'",
                (rs,index) -> rs.getLong(1),projectId).stream().findFirst().orElse(0L);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
