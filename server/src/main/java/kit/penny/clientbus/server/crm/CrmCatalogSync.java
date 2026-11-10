package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** A single durable CATALOG writer. No transaction is held while calling DIKIDI. */
@Service
public class CrmCatalogSync {
    private record Claim(CrmContext context, UUID actorId, UUID token, long generation) {}
    private record Candidate(UUID projectId, UUID organizationId, UUID actorId) {}
    private final CrmStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final CrmCatalogWriter writer;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "crm-catalog"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    public CrmCatalogSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                          CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmCatalogWriter writer) {
        this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.registry = registry; this.cipher = cipher; this.events = events;
        this.writer = writer;
    }
    public CrmSyncStateDto refresh(UUID projectId) {
        return refresh(projectId, "MASTERS");
    }
    public CrmSyncStateDto refresh(UUID projectId, String dataset) {
        if (!Set.of("MASTERS", "SERVICES", "CATALOG").contains(dataset)) throw CrmException.invalid();
        store.storage.requireReady();
        UUID organization = store.guard.organization(), actor = store.guard.actor();
        submit(claim(projectId, organization, actor, 0), false);
        return state(projectId, dataset);
    }
    /** Saved actors and legacy projects are rechecked without retaining a request SecurityContext. */
    int refreshDue(long intervalMs) {
        store.storage.requireReady();
        if (intervalMs < 1000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        int capacity = executor.getMaximumPoolSize() - executor.getActiveCount();
        if (capacity <= 0) return 0;
        var candidates = jdbc.query("""
                select p.id,p.organizationid,e.employeeid from crm.project p
                    join crm.connection c on c.id=p.connectionid
                    left join crm.syncstate s on s.projectid=p.id and s.dataset='CATALOG' and s.scopekey='ALL'
                    join dbo.employee e on e.employeeid=coalesce(cast(s.scope->>'refreshActorId' as uuid),(
                        select legacy.employeeid from dbo.employee legacy join dbo.users legacyuser on legacyuser.userid=legacy.userid
                        where legacy.organizationid=p.organizationid and legacyuser.isenabled and legacyuser.role='SUPER_ADMIN'
                        order by legacy.employeeid limit 1)) and e.organizationid=p.organizationid
                    join dbo.users u on u.userid=e.userid
                where p.enabled and c.status not in ('DISABLED','REAUTH_REQUIRED') and (c.requestnotbefore is null or c.requestnotbefore<=clock_timestamp()) and u.isenabled
                    and (u.role='SUPER_ADMIN' or u.role='EMPLOYEE' and exists (
                        select 1 from dbo.employeeworkspace ew where ew.employeeid=e.employeeid and ew.workspaceid=p.workspaceid))
                    and (s.lastattemptat is null or s.lastattemptat<=clock_timestamp()-(
                        case when s.lasterrorcode='CRM_CONNECTION_BUSY'
                            or (s.lasterrorcode is null and s.attemptcoverage->>'projectErrorCode'='CRM_CONNECTION_BUSY')
                        then least(?,10000) else ? end * interval '1 millisecond'))
                    and not exists(select 1 from crm.syncstate control where control.projectid=p.id
                        and control.dataset='WRITER:CATALOG' and control.scopekey='CONTROL' and control.claimuntil>clock_timestamp())
                order by s.lastattemptat nulls first,p.id limit ?
                """, (rs, row) -> new Candidate(rs.getObject("id", UUID.class),rs.getObject("organizationid", UUID.class),
                        rs.getObject("employeeid", UUID.class)), intervalMs, intervalMs, capacity);
        int started = 0;
        for (var candidate : candidates) {
            try {
                if (submit(claim(candidate.projectId(),candidate.organizationId(),candidate.actorId(),intervalMs),true)) started++;
            } catch (CrmException | AccessDeniedException ignored) { /* Configuration/access changed; recheck next poll. */ }
        }
        return started;
    }
    private Claim claim(UUID projectId, UUID organization, UUID actor, long intervalMs) {
        return tx.execute(status -> {
            var project = store.projects.findByIdAndOrganizationId(projectId, organization).orElseThrow(CrmStore::notFound);
            store.em.refresh(project, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            store.guard.workspace(actor, project.getWorkspace().getId());
            if (!project.isEnabled()) throw new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Проект CRM отключён.");
            store.requireNetwork(project);store.credentials(project.getConnection()); registry.require(project.getProvider());
            if (intervalMs > 0 && (project.getConnection().getStatus() == CrmConnectionStatus.REAUTH_REQUIRED
                    || Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from crm.syncstate where projectid=? and dataset='CATALOG' and scopekey='ALL'
                            and (lastattemptat>clock_timestamp()-(
                                    case when lasterrorcode='CRM_CONNECTION_BUSY'
                                        or (lasterrorcode is null and attemptcoverage->>'projectErrorCode'='CRM_CONNECTION_BUSY')
                                    then least(?,10000) else ? end * interval '1 millisecond')
                                or scope->>'refreshActorId' is not null and scope->>'refreshActorId'<>?))
                        """, Boolean.class, projectId, intervalMs, intervalMs, actor.toString())))) return null;
            CrmContext context = store.context(project);
            initialize(context, "CONTROL", "WRITER:CATALOG", "CONTROL");
            initialize(context, "SCOPE", "CATALOG", "ALL");
            UUID token = UUID.randomUUID();
            var generations = jdbc.query("""
                    update crm.syncstate set generation=generation+1, version=version+1,
                        claimtoken=?, claimuntil=clock_timestamp()+interval '120 seconds',
                        connectionid=?, credentialversion=?, contextversion=?, zonerevision=?,
                        status='RUNNING', lastattemptat=clock_timestamp(), updatedat=clock_timestamp()
                    where projectid=? and dataset='WRITER:CATALOG' and scopekey='CONTROL'
                        and (claimuntil is null or claimuntil<=clock_timestamp())
                    returning generation
                    """, (rs, row) -> rs.getLong(1), token, context.connectionId(), context.credentialVersion(),
                    context.contextVersion(), context.zoneRevision(), projectId);
            if (generations.isEmpty()) return null;
            long generation = generations.getFirst();
            jdbc.update("""
                    update crm.syncstate set generation=?, version=version+1, claimtoken=?, claimuntil=null,
                        connectionid=?, credentialversion=?, contextversion=?, zonerevision=?, status='QUEUED',
                        scope=jsonb_build_object('refreshActorId',cast(? as text)),
                        lastattemptat=clock_timestamp(), lasterrorcode=null, updatedat=clock_timestamp()
                    where projectid=? and dataset='CATALOG' and scopekey='ALL'
                    """, generation, token, context.connectionId(), context.credentialVersion(), context.contextVersion(),
                    context.zoneRevision(), actor.toString(), projectId);
            return new Claim(context, actor, token, generation);
        });
    }
    private boolean submit(Claim claim, boolean periodic) {
        if (claim != null) {
            try { executor.execute(() -> run(claim)); return true; }
            catch (RejectedExecutionException error) {
                var busy = CrmStore.failure(new CrmFailure(CrmFailure.Code.CONNECTION_BUSY));
                fail(claim, busy.code());
                if (!periodic) throw busy;
            }
        }
        return false;
    }
    private void initialize(CrmContext context, String kind, String dataset, String scopeKey) {
        jdbc.update("""
                insert into crm.syncstate
                    (id,version,createdat,updatedat,projectid,statekind,writedomain,dataset,scopekey,scope,generation,
                     connectionid,credentialversion,contextversion,zonerevision,status,successfulcoverage,attemptcoverage)
                values (?,0,clock_timestamp(),clock_timestamp(),?,?,'CATALOG',?,?,'{}'::jsonb,0,?,?,?,?,'IDLE','{}'::jsonb,'{}'::jsonb)
                on conflict (projectid,dataset,scopekey) do nothing
                """, UUID.randomUUID(), context.projectId(), kind, dataset, scopeKey, context.connectionId(),
                context.credentialVersion(), context.contextVersion(), context.zoneRevision());
    }
    private void run(Claim claim) {
        var health=new AtomicReference<CrmHealthCheck>();
        try {
            CrmCredentials credentials = tx.execute(status -> {
                var project = store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                requireClaim(claim);
                jdbc.update("update crm.syncstate set status='RUNNING',updatedat=clock_timestamp(),version=version+1 where projectid=? and dataset='CATALOG' and generation=? and claimtoken=?",
                        project.getId(), claim.generation(), claim.token());
                health.set(CrmHealthCheck.begin(project.getConnection()));
                return store.credentials(project.getConnection());
            });
            CrmCatalogSnapshot result;
            try (var access = credentials.open(cipher)) {
                result = registry.require(credentials.provider).readCatalog(access, claim.context().externalProjectId());
            }
            if (result == null || !(claim.context().externalProjectId() + ":CATALOG").equals(result.scope()))
                throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
            if (result.observedAt() == null || result.completeness() == null)
                throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
            tx.executeWithoutResult(status -> {
                var project = store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                requireClaim(claim);
                writer.apply(project, result, claim.generation());
                var connection = project.getConnection();
                health.get().apply(connection,CrmConnectionStatus.CONNECTED,null);
                store.em.flush();
                boolean complete = result.completeness() == CrmReadResult.Completeness.COMPLETE;
                if (release(claim, "SUCCEEDED") != 1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                jdbc.update("""
                        update crm.syncstate set status=?, version=version+1, updatedat=clock_timestamp(),
                            lastsuccessat=?, lasterrorcode=null, claimtoken=null,
                            successfulcoverage=case when ? then '{"dataset":"CATALOG","complete":true}'::jsonb else successfulcoverage end,
                            attemptcoverage=jsonb_strip_nulls(jsonb_build_object('dataset','CATALOG','completeness',cast(? as text),
                                'projectErrorCode',cast(? as text)))
                        where projectid=? and dataset='CATALOG' and scopekey='ALL' and generation=? and claimtoken=?
                        """, complete ? "SUCCEEDED" : "PARTIAL", Timestamp.from(result.observedAt()), complete,
                        result.completeness().name(), result.projectError() == null ? null : CrmStore.failure(new CrmFailure(result.projectError())).code(),
                        project.getId(), claim.generation(), claim.token());
                events.workspace(project.getWorkspace(), "crm.invalidated");
            });
        } catch (CrmFailure error) { fail(claim,CrmStore.failure(error).code(),health.get()); }
        catch (CrmException error) { fail(claim,error.code(),health.get()); }
        catch (AccessDeniedException error) { fail(claim,"ACCESS_DENIED",health.get()); }
        catch (RuntimeException error) { fail(claim,"CRM_TEMPORARILY_UNAVAILABLE",health.get()); }
    }
    private void requireClaim(Claim claim) {
        Boolean valid = jdbc.queryForObject("""
                select exists(select 1 from crm.syncstate where projectid=? and dataset='WRITER:CATALOG'
                    and scopekey='CONTROL' and generation=? and claimtoken=? and claimuntil>clock_timestamp())
                """, Boolean.class, claim.context().projectId(), claim.generation(), claim.token());
        if (!Boolean.TRUE.equals(valid)) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private int release(Claim claim, String status) {
        return jdbc.update("""
                update crm.syncstate set status=?, version=version+1, claimtoken=null,claimuntil=null,updatedat=clock_timestamp()
                where projectid=? and dataset='WRITER:CATALOG' and scopekey='CONTROL'
                    and generation=? and claimtoken=? and claimuntil>clock_timestamp()
                """, status, claim.context().projectId(), claim.generation(), claim.token());
    }
    private void fail(Claim claim, String code) {
        fail(claim,code,null);
    }
    private void fail(Claim claim,String code,CrmHealthCheck health) {
        try {
            tx.executeWithoutResult(status -> {
                var project = store.requireContext(claim.context());
                if (release(claim, "FAILED") != 1) return;
                jdbc.update("""
                        update crm.syncstate set status='FAILED', lasterrorcode=?, claimtoken=null,version=version+1,updatedat=clock_timestamp()
                        where projectid=? and dataset='CATALOG' and scopekey='ALL' and generation=? and claimtoken=?
                        """, code, project.getId(), claim.generation(), claim.token());
                if (health!=null && "CRM_REAUTH_REQUIRED".equals(code)) health.apply(project.getConnection(),CrmConnectionStatus.REAUTH_REQUIRED,code);
                events.workspace(project.getWorkspace(), "crm.invalidated");
            });
        } catch (RuntimeException ignored) {
            // Context/lease changed or storage is unavailable. Never publish a late result into the new context.
        }
    }
    /** Participates in the caller's configuration transaction. */
    public void invalidateProject(UUID projectId) {
        jdbc.update("""
                update crm.syncstate set generation=generation+1, version=version+1, claimtoken=null,claimuntil=null,
                    status='FAILED', lasterrorcode='CRM_CONTEXT_CHANGED', updatedat=clock_timestamp()
                where projectid=?
                """, projectId);
    }
    public CrmSyncStateDto state(UUID projectId) {
        return state(projectId, "MASTERS");
    }
    public CrmSyncStateDto state(UUID projectId, String dataset) {
        if (!Set.of("MASTERS", "SERVICES", "CATALOG").contains(dataset)) throw CrmException.invalid();
        store.storage.requireReady();
        return tx.execute(status -> {
            var project = store.project(projectId, store.guard.organization());
            var rows = jdbc.query("""
                    select s.*, (s.successfulcoverage->>'complete')='true' as complete,
                        s.attemptcoverage->>'completeness' as attemptcompleteness,
                        s.attemptcoverage->>'projectErrorCode' as projecterrorcode,
                        exists(select 1 from crm.syncstate c where c.projectid=s.projectid and c.dataset='WRITER:CATALOG'
                            and c.claimtoken=s.claimtoken and c.generation=s.generation and c.claimuntil>clock_timestamp()) as active
                    from crm.syncstate s where s.projectid=? and (s.dataset='CATALOG' or s.dataset='MASTERS' and ?='MASTERS') and s.scopekey='ALL'
                    order by case when s.dataset='CATALOG' then 0 else 1 end
                    """, (rs, index) -> {
                Instant attempt = rs.getTimestamp("lastattemptat") == null ? null : rs.getTimestamp("lastattemptat").toInstant();
                Instant success = rs.getTimestamp("lastsuccessat") == null ? null : rs.getTimestamp("lastsuccessat").toInstant();
                String state = rs.getString("status"), code = rs.getString("lasterrorcode");
                if (Set.of("QUEUED", "RUNNING").contains(state) && !rs.getBoolean("active")) { state = "FAILED"; code = "CRM_REFRESH_EXPIRED"; }
                boolean current = project.isEnabled() && project.getConnection().getStatus() != CrmConnectionStatus.DISABLED
                        && rs.getObject("connectionid", UUID.class).equals(project.getConnection().getId())
                        && rs.getLong("credentialversion") == project.getConnection().getCredentialVersion()
                        && rs.getLong("contextversion") == project.getContextVersion() && rs.getLong("zonerevision") == project.getZoneRevision()
                        && "SUCCEEDED".equals(state) && success != null && success.isAfter(Instant.now().minusSeconds(300));
                String completeness = rs.getString("attemptcompleteness");
                if (completeness == null) completeness = rs.getBoolean("complete") ? "COMPLETE" : "UNKNOWN";
                String projectError = rs.getString("projecterrorcode");
                return new CrmSyncStateDto(dataset, state, attempt, success, success == null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",
                        completeness, code, rs.getLong("generation"), Map.of(), Map.of(),
                        projectError == null ? Map.of() : Map.of("projectErrorCode", projectError));
            }, projectId, dataset);
            if (rows.isEmpty()) return store.pauseState(project,new CrmSyncStateDto(dataset, "IDLE", null, null, "NEVER_LOADED", "UNKNOWN", null, 0));
            var latest = rows.getFirst();
            if ("MASTERS".equals(dataset) && rows.size() == 2 && latest.lastSuccessAt() == null && rows.getLast().lastSuccessAt() != null) {
                // The catalog attempt owns execution/error state, while the old master observation
                // remains valid history. Never lend that observation to services or CATALOG.
                var legacy = rows.getLast();
                return store.pauseState(project,new CrmSyncStateDto(dataset, latest.status(), latest.lastAttemptAt(), legacy.lastSuccessAt(), "STALE",
                        legacy.completeness(), latest.errorCode(), latest.generation()));
            }
            return store.pauseState(project,latest);
        });
    }
    public long revision(UUID projectId) {
        return jdbc.query("select coalesce(sum(version),0) from crm.syncstate where projectid=? and dataset in ('CATALOG','MASTERS') and scopekey='ALL'",
                        (rs, row) -> rs.getLong(1), projectId)
                .stream().findFirst().orElse(0L);
    }
    /** Mapping changes affect filtered pages without changing the source observation. Caller locks the project. */
    static void bumpRevision(CrmStore store, UUID projectId) {
        store.em.createNativeQuery("update crm.syncstate set version=version+1,updatedat=clock_timestamp() "
                        +"where projectid=:project and dataset in ('CATALOG','MASTERS') and scopekey='ALL'")
                .setParameter("project",projectId).executeUpdate();
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
