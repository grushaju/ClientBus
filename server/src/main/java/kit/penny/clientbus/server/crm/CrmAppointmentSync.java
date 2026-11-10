package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.CrmSyncStateDto;
import kit.penny.clientbus.common.enums.CrmConnectionStatus;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.LongSupplier;

/** One durable writer shared by project journals and client history, across all periods. */
@Service
public class CrmAppointmentSync {
    private record Claim(CrmContext context, CrmAppointmentScope scope, CrmAppointmentQuery query,
                         UUID actorId,UUID token,long generation,long epoch,boolean force,Instant barrier) {}
    private record Subscription(UUID projectId, UUID organizationId, UUID actorId, CrmAppointmentScope scope) {}
    private final CrmStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final UiDomainEvents events;
    private final CrmAppointmentWriter writer;
    private final LongSupplier nanoTime;
    private final CrmJournalStore journals;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "crm-appointments"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public CrmAppointmentSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
                           CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmAppointmentWriter writer) {
        this(store,jdbc,manager,registry,cipher,events,writer,System::nanoTime);
    }
    CrmAppointmentSync(CrmStore store, JdbcTemplate jdbc, PlatformTransactionManager manager,
            CrmRegistry registry, CrmSecretCipher cipher, UiDomainEvents events, CrmAppointmentWriter writer,LongSupplier nanoTime) {
        this.store=store; this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.registry=registry;
        this.cipher=cipher; this.events=events; this.writer=writer; this.nanoTime=nanoTime;
        this.journals=new CrmJournalStore(jdbc);
    }
    public CrmSyncStateDto refresh(UUID projectId, String dataset, UUID crmClientId, LocalDate from, LocalDate to) {
        return refresh(projectId,dataset,crmClientId,from,to,false);
    }
    public CrmSyncStateDto refresh(UUID projectId,String dataset,UUID crmClientId,LocalDate from,LocalDate to,boolean force) {
        var scope = new CrmAppointmentScope(dataset, crmClientId, from, to);
        store.storage.requireReady();
        UUID organization=store.guard.organization(), actor=store.guard.actor();
        submit(claim(projectId,organization,actor,scope,0,force),false);
        return state(projectId,dataset,crmClientId,from,to);
    }
    /** Polls saved requests after restart as well; no request SecurityContext or JWT is retained. */
    int refreshDue(long intervalMs) {
        store.storage.requireReady();
        if (intervalMs<1000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        int capacity=executor.getMaximumPoolSize()-executor.getActiveCount();
        if (capacity<=0) return 0;
        // Choose one scope per project before spending worker capacity on the oldest requests globally.
        var subscriptions=jdbc.query("""
                select projectid,organizationid,employeeid,scope,dataset from (
                select distinct on (s.projectid) s.projectid,p.organizationid,e.employeeid,s.scope,s.dataset,s.lastattemptat,s.scopekey
                from crm.syncstate s join crm.project p on p.id=s.projectid join crm.connection c on c.id=p.connectionid
                    join dbo.employee e on e.employeeid=coalesce(cast(s.scope->>'refreshActorId' as uuid),(
                        select legacy.employeeid from dbo.employee legacy join dbo.users legacyuser on legacyuser.userid=legacy.userid
                        where legacy.organizationid=p.organizationid and legacyuser.isenabled and legacyuser.role='SUPER_ADMIN'
                        order by legacy.employeeid limit 1)) and e.organizationid=p.organizationid
                    join dbo.users u on u.userid=e.userid
                where s.dataset in ('APPOINTMENTS','CLIENT_HISTORY') and s.statekind='SCOPE' and p.enabled
                    and not (jsonb_exists(s.scope,'externalAppointmentId') and jsonb_exists(s.scope,'observationCompletedAt'))
                    and c.status not in ('DISABLED','REAUTH_REQUIRED') and (c.requestnotbefore is null or c.requestnotbefore<=clock_timestamp()) and u.isenabled
                    and (u.role='SUPER_ADMIN' or u.role='EMPLOYEE' and exists (
                        select 1 from dbo.employeeworkspace ew where ew.employeeid=e.employeeid and ew.workspaceid=p.workspaceid))
                    and s.lastattemptat<=clock_timestamp()-(
                        case when s.lasterrorcode='CRM_CONNECTION_BUSY' then least(?,10000) else ? end * interval '1 millisecond')
                    and not exists (select 1 from crm.syncstate control where control.projectid=s.projectid
                        and control.dataset='WRITER:APPOINTMENTS' and control.scopekey='CONTROL' and control.claimuntil>clock_timestamp())
                order by s.projectid,s.lastattemptat,s.scopekey
                ) due order by lastattemptat,projectid,scopekey limit ?
                """,(rs,index) -> {
            var saved=map(rs.getString("scope"));
            return new Subscription(rs.getObject("projectid",UUID.class),rs.getObject("organizationid",UUID.class),
                    rs.getObject("employeeid",UUID.class),new CrmAppointmentScope(rs.getString("dataset"),saved.get("crmClientId")==null ? null : UUID.fromString((String)saved.get("crmClientId")),
                    LocalDate.parse((String)saved.get("dateFrom")),LocalDate.parse((String)saved.get("dateTo")),(String)saved.get("externalAppointmentId")));
        },intervalMs,intervalMs,capacity);
        int started=0;
        for (var subscription : subscriptions) {
            try {
                if (submit(claim(subscription.projectId(),subscription.organizationId(),subscription.actorId(),subscription.scope(),intervalMs,false),true)) started++;
            } catch (CrmException | AccessDeniedException ignored) { /* Recheck configuration and access next poll. */ }
        }
        return started;
    }
    private Claim claim(UUID projectId,UUID organization,UUID actor,CrmAppointmentScope scope,long intervalMs,boolean force) {
        return tx.execute(status -> {
            var project=store.projects.findByIdAndOrganizationId(projectId,organization).orElseThrow(CrmStore::notFound);
            store.em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
            store.guard.workspace(actor,project.getWorkspace().getId());
            if (!project.isEnabled()) throw new CrmException(409,"CRM_CONFIGURATION_REQUIRED","Проект CRM отключён.");
            store.requireNetwork(project);store.credentials(project.getConnection()); registry.require(project.getProvider());
            if (intervalMs>0 && (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from crm.syncstate where projectid=? and dataset=? and scopekey=?
                        and (scope->>'refreshActorId'=? or scope->>'refreshActorId' is null)
                        and not (jsonb_exists(scope,'externalAppointmentId') and jsonb_exists(scope,'observationCompletedAt'))
                        and lastattemptat<=clock_timestamp()-(
                            case when lasterrorcode='CRM_CONNECTION_BUSY' then least(?,10000) else ? end * interval '1 millisecond'))
                    """,Boolean.class,projectId,scope.dataset(),scope.key(),actor.toString(),intervalMs,intervalMs))
                    || project.getConnection().getStatus()==CrmConnectionStatus.REAUTH_REQUIRED)) return null;
            var client=client(projectId, scope.crmClientId());
            CrmContext context=store.context(project);
            Map<String,Object> subscription=new HashMap<>(scope.fields()); subscription.put("refreshActorId",actor.toString());
            initialize(context, "CONTROL", "WRITER:APPOINTMENTS", "CONTROL", Map.of());
            initialize(context, "SCOPE", scope.dataset(), scope.key(), subscription);
            jdbc.update("update crm.syncstate set scope=cast(? as jsonb) where projectid=? and dataset=? and scopekey=?",
                    JSON.writeValueAsString(subscription),projectId,scope.dataset(),scope.key());
            UUID token=UUID.randomUUID();
            var generations=jdbc.query("""
                    update crm.syncstate set generation=generation+1,version=version+1,claimtoken=?,
                        claimuntil=clock_timestamp()+interval '120 seconds',connectionid=?,credentialversion=?,
                        contextversion=?,zonerevision=?,status='RUNNING',lastattemptat=clock_timestamp(),updatedat=clock_timestamp()
                    where projectid=? and dataset='WRITER:APPOINTMENTS' and scopekey='CONTROL'
                        and (claimuntil is null or claimuntil<=clock_timestamp()) returning generation
                    """, (rs,index) -> rs.getLong(1), token, context.connectionId(), context.credentialVersion(),
                    context.contextVersion(), context.zoneRevision(), projectId);
            if (generations.isEmpty()) {
                if (intervalMs>0) return null;
                Boolean same=jdbc.queryForObject("""
                        select exists(select 1 from crm.syncstate s join crm.syncstate c on c.projectid=s.projectid
                            and c.dataset='WRITER:APPOINTMENTS' and c.scopekey='CONTROL' and c.claimtoken=s.claimtoken
                            and c.generation=s.generation and c.claimuntil>clock_timestamp()
                            where s.projectid=? and s.dataset=? and s.scopekey=? and (not ? or s.attemptcoverage->>'force'='true'))
                        """, Boolean.class, projectId, scope.dataset(), scope.key(),force);
                if (!Boolean.TRUE.equals(same)) throw new CrmException(503,"CRM_CONNECTION_BUSY","Визиты проекта уже обновляются. Повторите позже.");
                return null;
            }
            long generation=generations.getFirst();
            jdbc.update("""
                    update crm.syncstate set generation=?,version=version+1,claimtoken=?,connectionid=?,credentialversion=?,
                        contextversion=?,zonerevision=?,status='QUEUED',lastattemptat=clock_timestamp(),
                        lasterrorcode=null,attemptcoverage=jsonb_build_object('force',cast(? as boolean)),updatedat=clock_timestamp()
                    where projectid=? and dataset=? and scopekey=?
                    """, generation,token,context.connectionId(),context.credentialVersion(),context.contextVersion(),
                    context.zoneRevision(),force,projectId,scope.dataset(),scope.key());
            return new Claim(context,scope,scope.query(client),actor,token,generation,journals.epoch(projectId),force,Instant.now());
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
    private CrmClientReference client(UUID projectId,UUID id) {
        if (id==null) return null;
        var client=store.em.find(CrmClientEntity.class,id);
        if (client==null || !client.getProject().getId().equals(projectId)) throw CrmStore.notFound();
        if (!"PROJECT".equals(client.getIdentityKind())) throw new CrmException(409,"CRM_CAPABILITY_UNVERIFIED","История доступна для клиентов проекта CRM.");
        return new CrmClientReference(client.getIdentityKind(),client.getExternalId());
    }
    private void initialize(CrmContext context,String kind,String dataset,String key,Map<String,Object> scope) {
        jdbc.update("""
                insert into crm.syncstate (id,version,createdat,updatedat,projectid,statekind,writedomain,dataset,scopekey,
                    scope,generation,connectionid,credentialversion,contextversion,zonerevision,status,successfulcoverage,attemptcoverage)
                values (?,0,clock_timestamp(),clock_timestamp(),?,?,'APPOINTMENTS',?,?,cast(? as jsonb),0,?,?,?,?,'IDLE','{}'::jsonb,'{}'::jsonb)
                on conflict (projectid,dataset,scopekey) do nothing
                """,UUID.randomUUID(),context.projectId(),kind,dataset,key,JSON.writeValueAsString(scope),context.connectionId(),
                context.credentialVersion(),context.contextVersion(),context.zoneRevision());
    }
    private void run(Claim claim) {
        long deadline=nanoTime.getAsLong()+TimeUnit.SECONDS.toNanos(90);
        var health=new AtomicReference<CrmHealthCheck>();
        try {
            CrmCredentials credentials=tx.execute(status -> {
                var project=store.requireContext(claim.context());
                store.guard.workspace(claim.actorId(),project.getWorkspace().getId()); requireClaim(claim);
                return store.credentials(project.getConnection());
            });
            String cursor=null; var cursors=new HashSet<String>(); var observed=new HashSet<String>();
            var origins=new HashMap<String,java.time.LocalDate>();boolean repaired=false;
            boolean allComplete=true,allLive=true; Instant oldestObservation=null; int total=0;
            for (int number=0;number<31;number++) {
                if (Thread.currentThread().isInterrupted() || expired(deadline)) {
                    limited(claim); return;
                }
                tx.executeWithoutResult(status -> {
                    var project=store.requireContext(claim.context());
                    store.guard.workspace(claim.actorId(),project.getWorkspace().getId()); requireClaim(claim);
                    health.set(CrmHealthCheck.snapshot(project.getConnection()));
                    jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()+interval '120 seconds' where projectid=? and dataset='WRITER:APPOINTMENTS' and scopekey='CONTROL' and claimtoken=? and generation=?",
                            project.getId(),claim.token(),claim.generation());
                });
                CrmAppointmentPage page;
                try (var access=credentials.open(cipher)) {
                    if (Thread.currentThread().isInterrupted() || expired(deadline)) { limited(claim); return; }
                    var provider=registry.require(credentials.provider);
                    var options=new CrmJournalOptions(claim.context().projectId(),claim.context().contextVersion(),claim.context().zoneRevision(),claim.epoch(),claim.force(),claim.barrier(),Instant.now().plusNanos(Math.max(1,deadline-nanoTime.getAsLong())));
                    page=claim.scope().crmClientId()==null
                            ? provider.readAppointments(access,claim.context().externalProjectId(),claim.query(),cursor,options)
                            : provider.readClientHistory(access,claim.context().externalProjectId(),claim.query(),cursor,options);
                }
                if (page==null || !claim.context().externalProjectId().equals(page.externalProjectId()) || !claim.query().equals(page.scope()))
                    throw new CrmFailure(CrmFailure.Code.CONTEXT_MISMATCH);
                if (page.observedAt()==null || page.completeness()==null || page.observedAt().isAfter(Instant.now().plusSeconds(300))
                        || page.items().size()>5000 || total+page.items().size()>20000)
                    throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                java.time.LocalDate previousDay=null;
                var pageIds=new HashSet<String>();
                for(var item:page.items()){
                    if(!pageIds.add(item.externalId()))throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                    if(observed.contains(item.externalId())){
                        if(page.observation()==null||origins.get(item.externalId())==null||origins.get(item.externalId()).equals(page.observation().day()))throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                        if(previousDay!=null&&!previousDay.equals(origins.get(item.externalId())))throw new CrmFailure(CrmFailure.Code.OBSERVATION_CONFLICT);
                        previousDay=origins.get(item.externalId());
                    }
                }
                final List<CrmAppointmentPage> batches;
                if(previousDay!=null){
                    if(repaired)throw new CrmFailure(CrmFailure.Code.OBSERVATION_CONFLICT);
                    batches=repair(claim,credentials,previousDay,page.observation().day(),deadline);repaired=true;
                    var days=Set.of(previousDay,page.observation().day());
                    observed.removeIf(id->days.contains(origins.get(id)));origins.entrySet().removeIf(entry->days.contains(entry.getValue()));
                }else batches=List.of(page);
                for(var batch:batches)for(var item:batch.items()){
                    if(!observed.add(item.externalId()))throw new CrmFailure(CrmFailure.Code.OBSERVATION_CONFLICT);
                    if(batch.observation()!=null)origins.put(item.externalId(),batch.observation().day());
                }
                total=observed.size();if(total>20000)throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                allComplete &= page.completeness()==CrmReadResult.Completeness.COMPLETE;
                allLive &= page.observation()==null;
                oldestObservation=oldestObservation==null || page.observedAt().isBefore(oldestObservation) ? page.observedAt() : oldestObservation;
                boolean terminal=page.nextCursor()==null;
                boolean limited=!terminal && (number==30 || expired(deadline));
                boolean finished=terminal || limited, complete=terminal && allComplete;
                // Reused observations must never infer absence using a newer worker generation.
                boolean markMissing=complete&&allLive;
                if (Thread.currentThread().isInterrupted()) throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
                var coverage=new HashMap<String,Object>(claim.scope().fields());
                if(!finished)coverage.put("force",claim.force());
                coverage.put("completeness",complete ? "COMPLETE" : terminal && page.completeness()==CrmReadResult.Completeness.UNKNOWN ? "UNKNOWN" : "PARTIAL");
                coverage.put("pages",number+1); coverage.put("items",total); coverage.put("observedAt",oldestObservation.toString());
                String document=JSON.writeValueAsString(coverage);
                Instant coverageObserved=oldestObservation;
                tx.executeWithoutResult(status -> {
                    var project=store.requireContext(claim.context());
                    store.guard.workspace(claim.actorId(),project.getWorkspace().getId()); requireClaim(claim);
                    if(page.observation()!=null && (page.observation().epoch()!=journals.epoch(project.getId())||page.observation().epoch()!=claim.epoch()||page.observation().sequence()<1
                            ||page.observation().day()==null||page.observation().day().isBefore(claim.query().dateFrom())||page.observation().day().isAfter(claim.query().dateTo())))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                    for(var batch:batches){
                        if(batch.observation()!=null&&batch.observation().epoch()!=journals.epoch(project.getId()))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                        writer.apply(project,batch,claim.generation());
                    }
                    if (markMissing) writer.missing(project,claim.query(),claim.generation(),coverageObserved);
                    if(page.observation()==null)health.get().apply(project.getConnection(),CrmConnectionStatus.CONNECTED,null);
                    else if(!page.observation().cacheHit()){
                        var sourceHealth=CrmHealthCheck.source(page.observation().health());
                        if(sourceHealth!=null)sourceHealth.apply(project.getConnection(),CrmConnectionStatus.CONNECTED,null);
                    }
                    store.em.flush(); requireClaim(claim);
                    if (finished && release(claim,"SUCCEEDED")!=1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                    int changed=jdbc.update("""
                            update crm.syncstate set status=?,version=version+1,lastsuccessat=?,lasterrorcode=?,
                                claimtoken=case when ? then null else claimtoken end,
                                successfulcoverage=case when ? then cast(? as jsonb) else successfulcoverage end,
                                attemptcoverage=cast(? as jsonb),updatedat=clock_timestamp()
                            where projectid=? and dataset=? and scopekey=? and generation=? and claimtoken=?
                            """,!finished ? "RUNNING" : complete ? "SUCCEEDED" : "PARTIAL",Timestamp.from(coverageObserved),
                            limited ? "CRM_SYNC_LIMIT_REACHED" : null,finished,complete,document,document,
                            project.getId(),claim.scope().dataset(),claim.scope().key(),claim.generation(),claim.token());
                    if (changed!=1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                    // Exact-object reads are durable one-shot tasks. Unknown completeness is normal for
                    // this endpoint; only an actually returned and committed object completes the task.
                    if(terminal && claim.query().externalAppointmentId()!=null && observed.contains(claim.query().externalAppointmentId())) {
                        jdbc.update("update crm.syncstate set scope=scope || jsonb_build_object('observationCompletedAt',clock_timestamp()),version=version+1 where projectid=? and dataset=? and scopekey=? and generation=?",
                                project.getId(),claim.scope().dataset(),claim.scope().key(),claim.generation());
                    }
                    jdbc.update("update crm.syncstate set version=version+1 where projectid=? and dataset='WRITER:APPOINTMENTS' and scopekey='CONTROL'",project.getId());
                    if (finished) events.workspace(project.getWorkspace(),"crm.invalidated");
                });
                if (finished) return;
                if (page.nextCursor().isBlank() || page.nextCursor().length()>512 || !cursors.add(page.nextCursor()))
                    throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
                cursor=page.nextCursor();
            }
        } catch (CrmFailure error) { fail(claim,CrmStore.failure(error).code(),error.journalOrigin()?CrmHealthCheck.source(error.sourceHealth()):health.get()); }
        catch (CrmException error) { fail(claim,error.code(),health.get()); }
        catch (AccessDeniedException error) { fail(claim,"ACCESS_DENIED",health.get()); }
        catch (RuntimeException error) { fail(claim,"CRM_TEMPORARILY_UNAVAILABLE",health.get()); }
    }
    private boolean expired(long deadline) { return nanoTime.getAsLong()-deadline>=0; }
    private List<CrmAppointmentPage> repair(Claim claim,CrmCredentials credentials,java.time.LocalDate first,java.time.LocalDate second,long deadline){
        var pages=new ArrayList<CrmAppointmentPage>();var ids=new HashSet<String>();Instant barrier=Instant.now();
        for(var day:List.of(first,second)){
            if(expired(deadline)||Thread.currentThread().isInterrupted())throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
            var query=new CrmAppointmentQuery(day,day,claim.query().client());
            var options=new CrmJournalOptions(claim.context().projectId(),claim.context().contextVersion(),claim.context().zoneRevision(),claim.epoch(),true,barrier,Instant.now().plusNanos(Math.max(1,deadline-nanoTime.getAsLong())));
            CrmAppointmentPage page;
            try(var access=credentials.open(cipher)){
                var provider=registry.require(credentials.provider);
                page=query.client()==null?provider.readAppointments(access,claim.context().externalProjectId(),query,null,options):provider.readClientHistory(access,claim.context().externalProjectId(),query,null,options);
            }
            if(page==null||page.nextCursor()!=null||!query.equals(page.scope())||!claim.context().externalProjectId().equals(page.externalProjectId())||page.observedAt()==null
                    ||page.observedAt().isAfter(Instant.now().plusSeconds(5))||page.observation()==null||page.observation().cacheHit()||page.observation().epoch()!=claim.epoch()
                    ||!day.equals(page.observation().day())||page.observation().sequence()<1||page.items().size()>5000)throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
            for(var item:page.items())if(!ids.add(item.externalId()))throw new CrmFailure(CrmFailure.Code.OBSERVATION_CONFLICT);
            pages.add(page);
        }
        return pages;
    }
    private void limited(Claim claim) {
        tx.executeWithoutResult(status -> {
            var project=store.requireContext(claim.context());
            store.guard.workspace(claim.actorId(),project.getWorkspace().getId());
            if (release(claim,"SUCCEEDED")!=1) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            jdbc.update("""
                    update crm.syncstate set status='PARTIAL',lasterrorcode='CRM_SYNC_LIMIT_REACHED',claimtoken=null,
                        version=version+1,updatedat=clock_timestamp(),attemptcoverage=attemptcoverage || '{"completeness":"PARTIAL"}'::jsonb
                    where projectid=? and dataset=? and scopekey=? and generation=? and claimtoken=?
                    """,project.getId(),claim.scope().dataset(),claim.scope().key(),claim.generation(),claim.token());
            events.workspace(project.getWorkspace(),"crm.invalidated");
        });
    }
    private void requireClaim(Claim claim) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from crm.syncstate where projectid=? and dataset='WRITER:APPOINTMENTS' and scopekey='CONTROL'
                    and generation=? and claimtoken=? and claimuntil>clock_timestamp())
                """,Boolean.class,claim.context().projectId(),claim.generation(),claim.token())))
            throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private int release(Claim claim,String status) {
        return jdbc.update("""
                update crm.syncstate set status=?,claimtoken=null,claimuntil=null,version=version+1,updatedat=clock_timestamp()
                where projectid=? and dataset='WRITER:APPOINTMENTS' and scopekey='CONTROL' and generation=? and claimtoken=?
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
                        where projectid=? and dataset=? and scopekey=? and generation=? and claimtoken=?
                        """,code,project.getId(),claim.scope().dataset(),claim.scope().key(),claim.generation(),claim.token());
                if (health!=null && "CRM_REAUTH_REQUIRED".equals(code)) health.apply(project.getConnection(),CrmConnectionStatus.REAUTH_REQUIRED,code);
                events.workspace(project.getWorkspace(),"crm.invalidated");
            });
        } catch (RuntimeException ignored) { /* The old context/lease can no longer publish a result. */ }
    }
    public CrmSyncStateDto state(UUID projectId,String dataset,UUID crmClientId,LocalDate from,LocalDate to) {
        var scope=new CrmAppointmentScope(dataset,crmClientId,from,to);
        store.storage.requireReady();
        return tx.execute(status -> {
            var project=store.project(projectId,store.guard.organization());
            store.em.refresh(project,LockModeType.PESSIMISTIC_READ); client(projectId,crmClientId);
            return state(project,scope);
        });
    }
    CrmSyncStateDto state(CrmProjectEntity project,CrmAppointmentScope scope) {
        var rows=jdbc.query("""
                select s.*,exists(select 1 from crm.syncstate c where c.projectid=s.projectid and c.dataset='WRITER:APPOINTMENTS'
                    and c.claimtoken=s.claimtoken and c.generation=s.generation and c.claimuntil>clock_timestamp()) as active
                from crm.syncstate s where s.projectid=? and s.dataset=? and s.scopekey=?
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
            return new CrmSyncStateDto(scope.dataset(),status,attempt,success,success == null ? "NEVER_LOADED" : current ? "CURRENT" : "STALE",
                    completeness,error,rs.getLong("generation"),scope.fields(),successful,attempted);
        },project.getId(),scope.dataset(),scope.key());
        return store.pauseState(project,rows.isEmpty() ? new CrmSyncStateDto(scope.dataset(),"IDLE",null,null,"NEVER_LOADED","UNKNOWN",null,0,scope.fields(),Map.of(),Map.of()) : rows.getFirst());
    }
    private static Map<String,Object> map(String json) { return JSON.readValue(json,new TypeReference<>() {}); }
    long revision(UUID projectId) {
        return jdbc.query("select appointmentdatarevision from crm.project where id=?",
                (rs,index) -> rs.getLong(1),projectId).stream().findFirst().orElse(0L);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
