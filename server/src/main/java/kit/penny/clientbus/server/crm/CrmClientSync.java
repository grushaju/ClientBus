package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.common.dto.crm.CrmSyncStateDto;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** A single durable CLIENTS writer. No transaction is held while calling DIKIDI. */
@Service
public class CrmClientSync {
    private record Claim(CrmContext context, UUID actorId, UUID token, long generation) {}
    private final CrmStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final CrmClientWriter writer;
    private final LongSupplier nanoTime;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "crm-clients"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public CrmClientSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                          CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmClientWriter writer) {
        this(store, jdbc, manager, registry, cipher, events, writer, System::nanoTime);
    }
    CrmClientSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                  CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmClientWriter writer, LongSupplier nanoTime) {
        this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.registry = registry; this.cipher = cipher; this.events = events;
        this.writer = writer; this.nanoTime = nanoTime;
    }
    public CrmSyncStateDto refresh(UUID projectId) {
        return refresh(projectId, "CLIENTS");
    }
    public CrmSyncStateDto refresh(UUID projectId, String dataset) {
        if (!Set.of("CLIENTS").contains(dataset)) throw CrmException.invalid();
        store.storage.requireReady();
        UUID organization = store.guard.organization(), actor = store.guard.actor();
        Claim claim = tx.execute(status -> {
            var project = store.project(projectId, organization);
            store.em.refresh(project, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (!project.isEnabled()) throw new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Проект CRM отключён.");
            store.credentials(project.getConnection()); registry.require(project.getProvider());
            CrmContext context = store.context(project);
            initialize(context, "CONTROL", "WRITER:CLIENTS", "CONTROL");
            initialize(context, "SCOPE", "CLIENTS", "ALL");
            UUID token = UUID.randomUUID();
            var generations = jdbc.query("""
                    update crm.syncstate set generation=generation+1, version=version+1,
                        claimtoken=?, claimuntil=clock_timestamp()+interval '120 seconds',
                        connectionid=?, credentialversion=?, contextversion=?, zonerevision=?,
                        status='RUNNING', lastattemptat=clock_timestamp(), updatedat=clock_timestamp()
                    where projectid=? and dataset='WRITER:CLIENTS' and scopekey='CONTROL'
                        and (claimuntil is null or claimuntil<=clock_timestamp())
                    returning generation
                    """, (rs, row) -> rs.getLong(1), token, context.connectionId(), context.credentialVersion(),
                    context.contextVersion(), context.zoneRevision(), projectId);
            if (generations.isEmpty()) return null;
            long generation = generations.getFirst();
            jdbc.update("""
                    update crm.syncstate set generation=?, version=version+1, claimtoken=?, claimuntil=null,
                        connectionid=?, credentialversion=?, contextversion=?, zonerevision=?, status='QUEUED',
                        lastattemptat=clock_timestamp(), lasterrorcode=null, attemptcoverage='{}'::jsonb, updatedat=clock_timestamp()
                    where projectid=? and dataset='CLIENTS' and scopekey='ALL'
                    """, generation, token, context.connectionId(), context.credentialVersion(), context.contextVersion(),
                    context.zoneRevision(), projectId);
            return new Claim(context, actor, token, generation);
        });
        if (claim != null) {
            try { executor.execute(() -> run(claim)); }
            catch (RejectedExecutionException error) {
                fail(claim, "CRM_RATE_LIMITED");
                throw new CrmException(429, "CRM_RATE_LIMITED", "Загрузка CRM занята. Повторите позже.");
            }
        }
        return state(projectId, dataset);
    }
    private void initialize(CrmContext context, String kind, String dataset, String scopeKey) {
        jdbc.update("""
                insert into crm.syncstate
                    (id,version,createdat,updatedat,projectid,statekind,writedomain,dataset,scopekey,scope,generation,
                     connectionid,credentialversion,contextversion,zonerevision,status,successfulcoverage,attemptcoverage)
                values (?,0,clock_timestamp(),clock_timestamp(),?,?,'CLIENTS',?,?,'{}'::jsonb,0,?,?,?,?,'IDLE','{}'::jsonb,'{}'::jsonb)
                on conflict (projectid,dataset,scopekey) do nothing
                """, UUID.randomUUID(), context.projectId(), kind, dataset, scopeKey, context.connectionId(),
                context.credentialVersion(), context.contextVersion(), context.zoneRevision());
    }
    private void run(Claim claim) {
        try {
            CrmCredentials credentials = tx.execute(status -> {
                var project = store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                requireClaim(claim);
                jdbc.update("update crm.syncstate set status='RUNNING',updatedat=clock_timestamp(),version=version+1 where projectid=? and dataset='CLIENTS' and generation=? and claimtoken=?",
                        project.getId(), claim.generation(), claim.token());
                return store.credentials(project.getConnection());
            });
            long deadline = nanoTime.getAsLong() + TimeUnit.MINUTES.toNanos(5);
            String cursor = null;
            Set<String> cursors = new HashSet<>();
            Set<List<String>> identities = new HashSet<>();
            boolean allPagesComplete = true;
            for (int pageNumber=0; pageNumber<100; pageNumber++) {
                if (expired(deadline)) { finishLimited(claim); return; }
                // Renew only while we still own the previous lease; never resurrect an expired claim.
                tx.executeWithoutResult(status -> {
                    var project = store.requireContext(claim.context());
                    store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                    requireClaim(claim);
                    jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()+interval '120 seconds' where projectid=? and dataset='WRITER:CLIENTS' and generation=? and claimtoken=?",
                            project.getId(), claim.generation(), claim.token());
                });
                CrmClientPage result;
                try (var access = credentials.open(cipher)) {
                    // Acquiring DB locks or opening credentials may have consumed the remaining budget.
                    if (expired(deadline)) { finishLimited(claim); return; }
                    result = registry.require(credentials.provider).readClients(access, claim.context().externalProjectId(), cursor);
                }
                if (result == null || !(claim.context().externalProjectId()+":CLIENTS").equals(result.scope()))
                    throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                if (result.observedAt()==null || result.completeness()==null) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                for (var item : result.items()) {
                    if (item.identityKind()==null || item.externalId()==null
                            || !identities.add(List.of(item.identityKind(), item.externalId())))
                        throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                }
                int loadedPages = pageNumber+1;
                boolean limited = pageNumber==99 || expired(deadline);
                boolean terminal = result.nextCursor()==null;
                boolean finished = terminal || limited;
                allPagesComplete &= result.completeness()==CrmReadResult.Completeness.COMPLETE;
                boolean complete = terminal && allPagesComplete;
                tx.executeWithoutResult(status -> {
                    var project = store.requireContext(claim.context());
                    store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                    requireClaim(claim);
                    writer.apply(project, result);
                    var connection=project.getConnection();
                    connection.setStatus(CrmConnectionStatus.CONNECTED); connection.setLastCheckedAt(Instant.now()); connection.setLastErrorCode(null);
                    store.em.flush();
                    requireClaim(claim);
                    if (finished && release(claim, "SUCCEEDED")!=1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                    jdbc.update("""
                            update crm.syncstate set status=?, version=version+1,updatedat=clock_timestamp(),
                                lastsuccessat=?,lasterrorcode=?,claimtoken=case when ? then null else claimtoken end,
                                successfulcoverage=case when ? then '{"dataset":"CLIENTS","complete":true}'::jsonb else successfulcoverage end,
                                attemptcoverage=jsonb_build_object('dataset','CLIENTS','completeness',cast(? as text),'pages',cast(? as int))
                            where projectid=? and dataset='CLIENTS' and scopekey='ALL' and generation=? and claimtoken=?
                            """, !finished ? "RUNNING" : complete ? "SUCCEEDED" : "PARTIAL", Timestamp.from(result.observedAt()),
                            limited && !terminal ? "CRM_SYNC_LIMIT_REACHED" : null, finished, complete,
                            complete ? "COMPLETE" : "PARTIAL", loadedPages, project.getId(),claim.generation(),claim.token());
                    events.workspace(project.getWorkspace(), "crm.invalidated");
                });
                if (finished) return;
                if (result.nextCursor().isBlank() || result.nextCursor().length()>512 || !cursors.add(result.nextCursor()))
                    throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                cursor=result.nextCursor();
            }
        } catch (CrmFailure error) { fail(claim, CrmStore.failure(error).code()); }
        catch (CrmException error) { fail(claim, error.code()); }
        catch (RuntimeException error) { fail(claim, "CRM_TEMPORARILY_UNAVAILABLE"); }
    }
    private boolean expired(long deadline) { return nanoTime.getAsLong()-deadline>=0; }
    private void finishLimited(Claim claim) {
        tx.executeWithoutResult(status -> {
            var project = store.requireContext(claim.context());
            store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
            if (release(claim, "SUCCEEDED")!=1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            jdbc.update("""
                    update crm.syncstate set status='PARTIAL',lasterrorcode='CRM_SYNC_LIMIT_REACHED',claimtoken=null,
                        version=version+1,updatedat=clock_timestamp(),
                        attemptcoverage=attemptcoverage || '{"dataset":"CLIENTS","completeness":"PARTIAL"}'::jsonb
                    where projectid=? and dataset='CLIENTS' and scopekey='ALL' and generation=? and claimtoken=?
                    """, project.getId(),claim.generation(),claim.token());
            events.workspace(project.getWorkspace(), "crm.invalidated");
        });
    }
    private void requireClaim(Claim claim) {
        Boolean valid = jdbc.queryForObject("""
                select exists(select 1 from crm.syncstate where projectid=? and dataset='WRITER:CLIENTS'
                    and scopekey='CONTROL' and generation=? and claimtoken=? and claimuntil>clock_timestamp())
                """, Boolean.class, claim.context().projectId(), claim.generation(), claim.token());
        if (!Boolean.TRUE.equals(valid)) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private int release(Claim claim, String status) {
        return jdbc.update("""
                update crm.syncstate set status=?, version=version+1, claimtoken=null,claimuntil=null,updatedat=clock_timestamp()
                where projectid=? and dataset='WRITER:CLIENTS' and scopekey='CONTROL'
                    and generation=? and claimtoken=? and claimuntil>clock_timestamp()
                """, status, claim.context().projectId(), claim.generation(), claim.token());
    }
    private void fail(Claim claim, String code) {
        try {
            tx.executeWithoutResult(status -> {
                var project = store.requireContext(claim.context());
                if (release(claim, "FAILED") != 1) return;
                jdbc.update("""
                        update crm.syncstate set status='FAILED', lasterrorcode=?, claimtoken=null,version=version+1,updatedat=clock_timestamp()
                        where projectid=? and dataset='CLIENTS' and scopekey='ALL' and generation=? and claimtoken=?
                        """, code, project.getId(), claim.generation(), claim.token());
                if ("CRM_REAUTH_REQUIRED".equals(code)) {
                    project.getConnection().setStatus(CrmConnectionStatus.REAUTH_REQUIRED);
                    project.getConnection().setLastErrorCode(code);
                    project.getConnection().setLastCheckedAt(Instant.now());
                }
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
        return state(projectId, "CLIENTS");
    }
    public CrmSyncStateDto state(UUID projectId, String dataset) {
        if (!Set.of("CLIENTS").contains(dataset)) throw CrmException.invalid();
        store.storage.requireReady();
        return tx.execute(status -> {
            var project = store.project(projectId, store.guard.organization());
            var rows = jdbc.query("""
                    select s.*, (s.successfulcoverage->>'complete')='true' as complete,
                        s.attemptcoverage->>'completeness' as attemptcompleteness,
                        exists(select 1 from crm.syncstate c where c.projectid=s.projectid and c.dataset='WRITER:CLIENTS'
                            and c.claimtoken=s.claimtoken and c.generation=s.generation and c.claimuntil>clock_timestamp()) as active
                    from crm.syncstate s where s.projectid=? and s.dataset='CLIENTS' and s.scopekey='ALL'
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
                return new CrmSyncStateDto(dataset, state, attempt, success, success == null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",
                        completeness, code, rs.getLong("generation"));
            }, projectId);
            if (rows.isEmpty()) return new CrmSyncStateDto(dataset, "IDLE", null, null, "NEVER_LOADED", "UNKNOWN", null, 0);
            return rows.getFirst();
        });
    }
    public long revision(UUID projectId) {
        return jdbc.query("select version from crm.syncstate where projectid=? and dataset='CLIENTS' and scopekey='ALL'",
                        (rs, row) -> rs.getLong(1), projectId)
                .stream().findFirst().orElse(0L);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
