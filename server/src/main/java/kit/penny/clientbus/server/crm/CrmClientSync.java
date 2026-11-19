package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.common.dto.crm.CrmSyncStateDto;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** A single durable CLIENTS writer. No transaction is held while calling DIKIDI. */
@Service
public class CrmClientSync {
    private record Claim(CrmContext context, UUID actorId, UUID token, long generation, List<String> statisticsClients, String cursor, boolean precedingComplete) {}
    private record Resume(String cursor,boolean complete) {}
    private final CrmStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final CrmClientWriter writer;
    private final CrmClientAvatars clientAvatars;
    private final LongSupplier nanoTime;
    private final int maxPages;
    @Autowired(required=false) private CrmSyncProperties syncProperties;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "crm-clients"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public CrmClientSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                          CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmClientWriter writer,
                          CrmClientAvatars clientAvatars) {
        this(store, jdbc, manager, registry, cipher, events, writer, clientAvatars, System::nanoTime);
    }
    CrmClientSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                  CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmClientWriter writer,
                  CrmClientAvatars clientAvatars, LongSupplier nanoTime) {
        this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.registry = registry; this.cipher = cipher; this.events = events;
        this.writer = writer; this.clientAvatars = clientAvatars; this.nanoTime = nanoTime; this.maxPages = 1000;
    }
    public CrmSyncStateDto refresh(UUID projectId) {
        return refresh(projectId, "CLIENTS");
    }
    public CrmSyncStateDto refresh(UUID projectId, String dataset) {
        return refresh(projectId,dataset,false);
    }
    public CrmSyncStateDto refreshStatistics(UUID projectId) {
        return refresh(projectId,"CLIENTS",true);
    }
    private CrmSyncStateDto refresh(UUID projectId, String dataset, boolean statisticsOnly) {
        if (!Set.of("CLIENTS").contains(dataset)) throw CrmException.invalid();
        store.storage.requireReady();
        UUID organization = store.guard.organization(), actor = store.guard.actor();
        Claim claim = claim(projectId,organization,actor,statisticsOnly,false);
        if (claim != null) {
            try { executor.execute(() -> run(claim)); }
            catch (RejectedExecutionException error) { fail(claim,"CRM_CONNECTION_BUSY"); throw CrmException.conflict("CRM_CONNECTION_BUSY"); }
        }
        return state(projectId, dataset);
    }
    long runBackground(UUID projectId,UUID organization,boolean statisticsOnly,UUID actor) {
        var claim=claim(projectId,organization,actor,statisticsOnly,actor==null);
        if(claim==null)throw CrmException.conflict("CRM_CONNECTION_BUSY");
        run(claim);
        return claim.generation();
    }
    private Claim claim(UUID projectId,UUID organization,UUID actor,boolean statisticsOnly,boolean background) {
        return tx.execute(status -> {
            var project = store.projects.findByIdAndOrganizationId(projectId, organization).orElseThrow(CrmStore::notFound);
            if(actor!=null) store.guard.workspace(actor,project.getWorkspace().getId());
            store.em.refresh(project, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (!project.isEnabled()) throw new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Проект CRM отключён.");
            store.requireNetwork(project);store.credentials(project.getConnection()); registry.require(project.getProvider());
            CrmContext context = store.context(project);
            initialize(context, "CONTROL", "WRITER:CLIENTS", "CONTROL");
            initialize(context, "SCOPE", "CLIENTS", "ALL");
            Resume resume=statisticsOnly ? new Resume(null,true) : jdbc.query("""
                    select scope->>'continuationCursor',coalesce((scope->>'continuationComplete')::boolean,false) from crm.syncstate where projectid=? and dataset='CLIENTS' and scopekey='ALL'
                      and scope->>'continuationCursor' is not null and connectionid=? and credentialversion=? and contextversion=? and zonerevision=?
                    """,(rs,i)->new Resume(rs.getString(1),rs.getBoolean(2)),projectId,context.connectionId(),context.credentialVersion(),context.contextVersion(),context.zoneRevision()).stream().findFirst().orElse(new Resume(null,true));
            jdbc.update("update crm.syncstate set scope=scope-'continuationCursor'-'continuationComplete' where projectid=? and dataset='CLIENTS' and scopekey='ALL' and (connectionid<>? or credentialversion<>? or contextversion<>? or zonerevision<>?)",
                projectId,context.connectionId(),context.credentialVersion(),context.contextVersion(),context.zoneRevision());
            UUID token = UUID.randomUUID();
            var generations = jdbc.query(lease("""
                    update crm.syncstate set generation=generation+1, version=version+1,
                        claimtoken=?, claimuntil=clock_timestamp()+interval '120 seconds',
                        connectionid=?, credentialversion=?, contextversion=?, zonerevision=?,
                        status='RUNNING', lastattemptat=clock_timestamp(), updatedat=clock_timestamp()
                    where projectid=? and dataset='WRITER:CLIENTS' and scopekey='CONTROL'
                        and (claimuntil is null or claimuntil<=clock_timestamp())
                    returning generation
                    """), (rs, row) -> rs.getLong(1), token, context.connectionId(), context.credentialVersion(),
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
            List<String> statisticsClients=statisticsOnly ? store.em.createQuery("""
                    select c.externalId from CrmClientEntity c where c.project.id=:project and c.identityKind='PROJECT'
                      and (:background=false or c.averageCheck is null or coalesce(c.statisticsUpdatedAt,c.lastObservedAt)<:staleBefore)
                      and (c.statisticsRetryAt is null or c.statisticsRetryAt<:now)
                    order by case when c.averageCheck is null then null else coalesce(c.statisticsUpdatedAt,c.lastObservedAt) end asc nulls first, c.id
                    """,String.class).setParameter("project",projectId).setParameter("background",background).setParameter("staleBefore",Instant.now().minus(policy(true).getStaleAfter())).setParameter("now",Instant.now()).setMaxResults(policy(true).getBatchSize()).getResultList() : null;
            return new Claim(context, actor, token, generation, statisticsClients,resume.cursor(),resume.complete());
        });
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
        var health=new AtomicReference<CrmHealthCheck>();
        try {
            CrmCredentials credentials = tx.execute(status -> {
                var project = store.requireContext(claim.context());
                if(claim.actorId()!=null) store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                requireClaim(claim);
                jdbc.update("update crm.syncstate set status='RUNNING',updatedat=clock_timestamp(),version=version+1 where projectid=? and dataset='CLIENTS' and generation=? and claimtoken=?",
                        project.getId(), claim.generation(), claim.token());
                return store.credentials(project.getConnection());
            });
            long deadline = nanoTime.getAsLong() + policy(claim.statisticsClients()!=null).getMaxRunDuration().toNanos();
            String cursor = claim.cursor();
            Set<String> cursors = new HashSet<>();
            Set<List<String>> identities = new HashSet<>();
            boolean allPagesComplete = claim.precedingComplete();
            for (int pageNumber=0; pageNumber<pageLimit(); pageNumber++) {
                if(Thread.currentThread().isInterrupted())throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
                if (expired(deadline)) { finishLimited(claim); return; }
                // Renew only while we still own the previous lease; never resurrect an expired claim.
                tx.executeWithoutResult(status -> {
                    var project = store.requireContext(claim.context());
                    if(claim.actorId()!=null) store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                    requireClaim(claim);
                    health.set(CrmHealthCheck.begin(project.getConnection()));
                    jdbc.update(lease("update crm.syncstate set claimuntil=clock_timestamp()+interval '120 seconds' where projectid=? and dataset='WRITER:CLIENTS' and generation=? and claimtoken=?"),
                            project.getId(), claim.generation(), claim.token());
                });
                CrmClientPage result;
                try (var access = credentials.open(cipher)) {
                    // Acquiring DB locks or opening credentials may have consumed the remaining budget.
                    if (expired(deadline)) { finishLimited(claim); return; }
                    var provider=registry.require(credentials.provider);
                    result = claim.statisticsClients()==null
                            ? provider.readClients(access, claim.context().externalProjectId(), cursor)
                            : claim.statisticsClients().isEmpty()
                            ? new CrmClientPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,claim.context().externalProjectId()+":CLIENTS")
                            : provider.readClientStatistics(access,claim.context().externalProjectId(),claim.statisticsClients());
                }
                if (result == null || !(claim.context().externalProjectId()+":CLIENTS").equals(result.scope()))
                    throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                if (result.observedAt()==null || result.completeness()==null) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                if (claim.statisticsClients()!=null && (result.nextCursor()!=null || result.items().size()!=claim.statisticsClients().size()
                        || !new HashSet<>(claim.statisticsClients()).equals(result.items().stream().map(CrmClient::externalId).collect(java.util.stream.Collectors.toSet()))
                        || result.items().stream().anyMatch(item -> !item.presentFields().containsAll(Set.of("visitCount","totalSpent","averageCheck","statisticsCurrency"))
                            || item.visitCount()==null || item.averageCheck()==null || item.totalSpent()==null || item.statisticsCurrency()==null)))
                    throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                for (var item : result.items()) {
                    if (item.identityKind()==null || item.externalId()==null
                            || !identities.add(List.of(item.identityKind(), item.externalId())))
                        throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                }
                if(Thread.currentThread().isInterrupted())throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
                int loadedPages = pageNumber+1;
                if(result.nextCursor()!=null && (result.nextCursor().isBlank() || result.nextCursor().length()>512 || !cursors.add(result.nextCursor())))
                    throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                // Аватары зеркалятся до транзакции: сеть не должна держать строки списка заблокированными.
                // Страница без аватаров не стоит ни чтения зеркала, ни сети; сеть ограничена и общим
                // бюджетом синхронизации, и отдельным слотом, чтобы не просрочить аренду писателя (120 с).
                final Map<String,String> avatarKeys;
                if (result.items().stream().anyMatch(item -> item.presentFields().contains("avatar"))) {
                    tx.executeWithoutResult(status->{
                        store.requireContext(claim.context());requireClaim(claim);
                        jdbc.update(lease("update crm.syncstate set claimuntil=clock_timestamp()+interval '120 seconds' where projectid=? and dataset='WRITER:CLIENTS' and generation=? and claimtoken=?"),claim.context().projectId(),claim.generation(),claim.token());
                    });
                    var mirrored = new HashMap<String, CrmClientAvatars.Previous>();
                    jdbc.query("select externalid, avatarorigin, avatarurl from crm.client where projectid = ?",
                            (RowCallbackHandler) row -> mirrored.put(row.getString(1),
                                    new CrmClientAvatars.Previous(row.getString(2), row.getString(3))),
                            claim.context().projectId());
                    // Бюджет отдаём относительным: у зеркала свои часы, и абстрактный дедлайн
                    // синхронизации в них ничего не значит. Слот — минута, чтобы не просрочить аренду.
                    avatarKeys = clientAvatars.materialize(result.items(), mirrored, CrmClientAvatars.MAX_FILES_PER_PAGE,
                            Math.min(deadline-nanoTime.getAsLong(), TimeUnit.SECONDS.toNanos(60)));
                } else avatarKeys = Map.of();
                boolean limited = pageNumber==pageLimit()-1 || expired(deadline);
                boolean terminal = result.nextCursor()==null;
                boolean finished = terminal || limited;
                allPagesComplete &= result.completeness()==CrmReadResult.Completeness.COMPLETE;
                boolean complete = terminal && allPagesComplete;
                boolean chainComplete=allPagesComplete;
                tx.executeWithoutResult(status -> {
                    var project = store.requireContext(claim.context());
                    if(claim.actorId()!=null) store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
                    requireClaim(claim);
                    writer.apply(project, result, avatarKeys);
                    if(claim.statisticsClients()==null) jdbc.update("update crm.syncstate set scope=(scope-'continuationCursor'-'continuationComplete') || cast(? as jsonb) where projectid=? and dataset='CLIENTS' and scopekey='ALL'",
                        result.nextCursor()==null ? "{}" : tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("continuationCursor",result.nextCursor(),"continuationComplete",chainComplete)),project.getId());
                    var connection=project.getConnection();
                    health.get().apply(connection,CrmConnectionStatus.CONNECTED,null);
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
                cursor=result.nextCursor();
            }
            finishLimited(claim);
        } catch (CrmFailure error) { fail(claim,CrmStore.failure(error).code(),health.get()); }
        catch (CrmException error) { fail(claim,error.code(),health.get()); }
        catch (RuntimeException error) { fail(claim,"CRM_TEMPORARILY_UNAVAILABLE",health.get()); }
        catch (Error fatal) {
            // Ошибка уровня JVM не должна переживать аренду: мимо fail() строка синхронизации
            // оставалась бы занятой до истечения claimuntil, а проект — без ответа и без кода.
            fail(claim,"CRM_TEMPORARILY_UNAVAILABLE"); throw fatal;
        }
    }
    private CrmSyncProperties.Policy policy(boolean statistics) {
        if(syncProperties!=null)return syncProperties.policy(statistics ? CrmSyncProperties.Dataset.CLIENT_STATISTICS : CrmSyncProperties.Dataset.CLIENTS);
        var fallback=new CrmSyncProperties.Policy();fallback.setMaxRunDuration(java.time.Duration.ofMinutes(5));fallback.setMaxPagesPerRun(maxPages);return fallback;
    }
    private int pageLimit(){return syncProperties==null ? maxPages : policy(false).getMaxPagesPerRun();}
    private boolean expired(long deadline) { return nanoTime.getAsLong()-deadline>=0; }
    private void finishLimited(Claim claim) {
        tx.executeWithoutResult(status -> {
            var project = store.requireContext(claim.context());
            if(claim.actorId()!=null) store.guard.workspace(claim.actorId(), project.getWorkspace().getId());
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
        fail(claim,code,null);
    }
    private void fail(Claim claim,String code,CrmHealthCheck health) {
        try {
            tx.executeWithoutResult(status -> {
                var project = store.requireContext(claim.context());
                if (release(claim, "FAILED") != 1) return;
                if(claim.statisticsClients()==null && "CRM_INVALID_RESPONSE".equals(code))
                    jdbc.update("update crm.syncstate set scope=scope-'continuationCursor'-'continuationComplete' where projectid=? and dataset='CLIENTS' and scopekey='ALL' and generation=? and claimtoken=?",project.getId(),claim.generation(),claim.token());
                jdbc.update("""
                        update crm.syncstate set status='FAILED', lasterrorcode=?, claimtoken=null,version=version+1,updatedat=clock_timestamp()
                        where projectid=? and dataset='CLIENTS' and scopekey='ALL' and generation=? and claimtoken=?
                        """, code, project.getId(), claim.generation(), claim.token());
                if(claim.statisticsClients()!=null && !claim.statisticsClients().isEmpty()) {
                    for(String id:claim.statisticsClients())jdbc.update("update crm.client set statisticsretryat=clock_timestamp()+(? * interval '1 millisecond') where projectid=? and externalid=? and identitykind='PROJECT'",
                        policy(true).getItemRetryDelay().toMillis(),project.getId(),id);
                }
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
                        && "SUCCEEDED".equals(state) && success != null && success.isAfter(Instant.now().minus(syncProperties==null ? java.time.Duration.ofSeconds(300) : syncProperties.policy(CrmSyncProperties.Dataset.CLIENTS).getStaleAfter()));
                String completeness = rs.getString("attemptcompleteness");
                if (completeness == null) completeness = rs.getBoolean("complete") ? "COMPLETE" : "UNKNOWN";
                return new CrmSyncStateDto(dataset, state, attempt, success, success == null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",
                        completeness, code, rs.getLong("generation"));
            }, projectId);
            if (rows.isEmpty()) return store.pauseState(project,new CrmSyncStateDto(dataset, "IDLE", null, null, "NEVER_LOADED", "UNKNOWN", null, 0));
            return store.pauseState(project,rows.getFirst());
        });
    }
    public long revision(UUID projectId) {
        return jdbc.query("select version from crm.syncstate where projectid=? and dataset='CLIENTS' and scopekey='ALL'",
                        (rs, row) -> rs.getLong(1), projectId)
                .stream().findFirst().orElse(0L);
    }
    private String lease(String sql){return sql.replace("interval '120 seconds'", "("+(syncProperties==null ? 120000L : syncProperties.getLeaseDuration().toMillis())+" * interval '1 millisecond')");}
    @PreDestroy public void close() { executor.shutdownNow(); }
}
