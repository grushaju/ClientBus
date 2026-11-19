package kit.penny.clientbus.server.crm;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static kit.penny.clientbus.server.crm.CrmSyncProperties.Dataset;

/** Plans bounded scopes independently of browser activity and drains the durable queue. */
@Component
public class CrmSyncCoordinator {
    private static final Logger LOG=LoggerFactory.getLogger(CrmSyncCoordinator.class);
    private final CrmSyncProperties settings;
    private final CrmSyncQueue queue;
    private final JdbcTemplate jdbc;
    private final CrmStorage storage;
    private final CrmCatalogSync catalog;
    private final CrmClientSync clients;
    private final CrmAppointmentSync appointments;
    private final CrmScheduleSync schedules;
    private final CrmFinanceService finance;
    private final CrmAppointmentTimes times;
    private final CrmAccessGuard guard;
    private final ThreadPoolExecutor workers;
    private final Map<UUID,CrmSyncQueue.Job> active=new ConcurrentHashMap<>();
    private final Map<UUID,Thread> runningThreads=new ConcurrentHashMap<>();
    private Instant nextPlan=Instant.MIN;
    public CrmSyncCoordinator(CrmSyncProperties settings,CrmSyncQueue queue,JdbcTemplate jdbc,CrmStorage storage,
            CrmCatalogSync catalog,CrmClientSync clients,CrmAppointmentSync appointments,CrmScheduleSync schedules,CrmFinanceService finance,CrmAppointmentTimes times,CrmAccessGuard guard){
        this.settings=settings;this.queue=queue;this.jdbc=jdbc;this.storage=storage;this.catalog=catalog;this.clients=clients;
        this.appointments=appointments;this.schedules=schedules;this.finance=finance;this.times=times;this.guard=guard;
        workers=new ThreadPoolExecutor(settings.getWorkers(),settings.getWorkers(),30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"crm-sync");t.setDaemon(true);return t;});
    }
    boolean enabled(){return settings.isEnabled();}
    kit.penny.clientbus.common.dto.crm.CrmSyncStateDto request(UUID project,kit.penny.clientbus.common.dto.crm.RefreshCrmRequest request){
        Dataset dataset;String scope="ALL";
        switch(request.dataset()){
            case "MASTERS","SERVICES","CATALOG" -> dataset=Dataset.CATALOG;
            case "CLIENTS" -> dataset=Dataset.CLIENTS;
            case "CLIENT_STATISTICS" -> dataset=Dataset.CLIENT_STATISTICS;
            case "WORK_INTERVALS" -> {dataset=Dataset.SCHEDULES;scope=new CrmScheduleScope(request.masterId(),request.dateFrom(),request.dateTo()).key();}
            case "APPOINTMENTS","CLIENT_HISTORY" -> {dataset=Dataset.valueOf(request.dataset());scope=new CrmAppointmentScope(request.dataset(),request.crmClientId(),request.dateFrom(),request.dateTo()).key();}
            default -> throw CrmException.invalid();
        }
        if(dataset==Dataset.APPOINTMENTS || dataset==Dataset.CLIENT_HISTORY)
            queue.requestPeriod(project,dataset,request.crmClientId(),request.dateFrom(),request.dateTo(),request.force(),guard.actor());
        else queue.request(project,dataset,scope,request.masterId(),request.crmClientId(),request.dateFrom(),request.dateTo(),request.force(),guard.actor());
        return new kit.penny.clientbus.common.dto.crm.CrmSyncStateDto(request.dataset(),"QUEUED",Instant.now(),null,"STALE","UNKNOWN",null,0);
    }
    List<Map<String,Object>> state(UUID project){return queue.state(project);}
    Map<String,Object> overview(UUID project){return queue.overview(project);}
    @Scheduled(fixedDelayString="${clientbus.crm.sync.scheduler-tick:10s}",scheduler="crmScheduleScheduler")
    public void poll(){
        if(!settings.isEnabled())return;
        try{
            storage.requireReady();
            if(!Instant.now().isBefore(nextPlan)){plan();times.recalculate();nextPlan=Instant.now().plus(settings.getPlanningInterval());}
            while(active.size()<settings.getWorkers()){
                var job=queue.claim();if(job==null)break;
                if(!job.manual()&&!settings.policy(job.dataset()).isEnabled()){queue.finish(job,"CRM_SYNC_DISABLED",false);break;}
                active.put(job.token(),job);
                try{workers.execute(()->execute(job));}catch(RejectedExecutionException busy){active.remove(job.token());queue.finish(job,"CRM_CONNECTION_BUSY",false);break;}
            }
        }catch(RuntimeException error){
            Throwable cause=error;
            while(cause.getCause()!=null && !(cause instanceof java.sql.SQLException))cause=cause.getCause();
            if(cause instanceof java.sql.SQLException sql)LOG.warn("CRM synchronization polling failed: {} (SQL state {}): {}",error.getClass().getSimpleName(),sql.getSQLState(),sql.getMessage()==null?"unknown":sql.getMessage().lines().findFirst().orElse("unknown"));
            else LOG.warn("CRM synchronization polling failed: {}",error.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelayString="${clientbus.crm.sync.heartbeat-interval:20s}",scheduler="crmSyncHeartbeatScheduler")
    public void heartbeat(){for(var job:active.values())try{
        if(!queue.heartbeat(job)){var thread=runningThreads.get(job.token());if(thread!=null)thread.interrupt();}
    }catch(RuntimeException error){LOG.warn("CRM synchronization heartbeat failed: {}",error.getClass().getSimpleName());}}
    void plan(){queue.plan(()->{
        queue.expandPendingPeriods();
        queue.importObservations();
        for(var project:jdbc.queryForList("select p.id,p.zoneid from crm.project p join crm.connection c on c.id=p.connectionid where p.enabled and not p.archived and c.status<>'DISABLED'")){
            UUID id=(UUID)project.get("id");
            for(var dataset:List.of(Dataset.CATALOG,Dataset.CLIENTS,Dataset.CLIENT_STATISTICS))if(settings.policy(dataset).isEnabled())queue.put(id,dataset,"ALL",null,null,null,null,false);
            if(project.get("zoneid")==null)continue; // No guessed zone for calendar scopes.
            LocalDate today=LocalDate.now(ZoneId.of((String)project.get("zoneid")));
            for(var dataset:List.of(Dataset.APPOINTMENTS,Dataset.HISTORY,Dataset.SCHEDULES)){
                var policy=settings.policy(dataset);if(!policy.isEnabled())continue;
                LocalDate start=today.minusDays(policy.getPastDays()),end=today.plusDays(policy.getFutureDays());
                if(dataset==Dataset.HISTORY)end=today.minusDays(1);
                // DIKIDI journal pagination is by day; persist that protocol unit even with a one-page budget.
                int chunkDays=dataset==Dataset.SCHEDULES ? policy.getChunkDays():1;
                for(LocalDate from=LocalDate.ofEpochDay(Math.floorDiv(start.toEpochDay(),chunkDays)*(long)chunkDays);!from.isAfter(end);from=from.plusDays(chunkDays)){
                    LocalDate to=from.plusDays(chunkDays-1L);
                    LocalDate scopeFrom=from.isBefore(start)?start:from,scopeTo=to.isAfter(end)?end:to;
                    if(dataset==Dataset.SCHEDULES){
                        for(UUID master:jdbc.queryForList("select id from crm.employee where projectid=? and ismaster and coalesce(observationstate,'OBSERVED')<>'MISSING'",UUID.class,id))queue.put(id,dataset,master+"/"+scopeFrom+"/"+scopeTo,master,null,scopeFrom,scopeTo,false);
                    }else queue.put(id,dataset,"ALL/"+scopeFrom+"/"+scopeTo,null,null,scopeFrom,scopeTo,false);
                }
            }
            var policy=settings.policy(Dataset.FINANCE);
            if(policy.isEnabled())for(var row:jdbc.queryForList("""
                select a.id from crm.appointment a left join crm.syncjob j on j.projectid=a.projectid and j.dataset='FINANCE' and j.scopekey=a.id::text
                where a.projectid=? and a.kind='APPOINTMENT' and a.startlocal>=? and a.startlocal<?
                  and (a.financialobservedat is null or a.financialobservedat<clock_timestamp()-(? * interval '1 millisecond'))
                  and (j.id is null or j.nextat<=clock_timestamp())
                order by j.lastattemptat nulls first,a.financialobservedat nulls first,a.id limit ?
                """,id,today.minusDays(policy.getPastDays()).atStartOfDay(),today.plusDays(policy.getFutureDays()+1L).atStartOfDay(),policy.getStaleAfter().toMillis(),policy.getBatchSize())){
                UUID appointment=(UUID)row.get("id");queue.put(id,Dataset.FINANCE,appointment.toString(),null,appointment,null,null,false);
            }
        }
    });}
    private void execute(CrmSyncQueue.Job job){
        String error=null;boolean continuation=false;
        runningThreads.put(job.token(),Thread.currentThread());
        try{
            if(!queue.heartbeat(job))throw CrmException.conflict("CRM_REFRESH_EXPIRED");
            long generation=switch(job.dataset()){
                case CATALOG -> catalog.runBackground(job.project(),job.organization(),job.actor());
                case CLIENTS,CLIENT_STATISTICS -> clients.runBackground(job.project(),job.organization(),job.dataset()==Dataset.CLIENT_STATISTICS,job.actor());
                case APPOINTMENTS,HISTORY,CLIENT_HISTORY -> appointments.runBackground(job.project(),job.organization(),appointmentScope(job),job.force(),job.actor(),settings.policy(job.dataset()),queue.expectedContext(job));
                case SCHEDULES -> schedules.runBackground(job.project(),job.organization(),job.master(),job.from(),job.to(),job.actor());
                case FINANCE -> finance.runBackground(job.appointment(),job.organization(),job.actor());
            };
            String dataset=switch(job.dataset()){case CATALOG->"CATALOG";case CLIENTS,CLIENT_STATISTICS->"CLIENTS";case CLIENT_HISTORY->"CLIENT_HISTORY";case APPOINTMENTS,HISTORY->"APPOINTMENTS";case SCHEDULES->"WORK_INTERVALS";case FINANCE->"FINANCE";};
            var rows=jdbc.queryForList("select status,lasterrorcode,scope,jsonb_exists(scope,'observationCompletedAt') as observationcomplete from crm.syncstate where projectid=? and dataset=? and scopekey=? and (?<0 or generation=?)",job.project(),dataset,job.scope(),generation,generation);
            if(rows.isEmpty())error="CRM_INVALID_RESPONSE";
            else{
                var row=rows.getFirst();error=(String)row.get("lasterrorcode");
                continuation="CRM_SYNC_LIMIT_REACHED".equals(error);
                if(continuation)error=null;
                else if(error==null&&!Set.of("SUCCEEDED","PARTIAL").contains(row.get("status")))error="CRM_CONNECTION_BUSY";
                if(error==null && job.scope().startsWith("RECORD:") && !Boolean.TRUE.equals(row.get("observationcomplete")))error="CRM_OBSERVATION_PENDING";
            }
        }catch(CrmException failure){error=failure.code();}
        catch(org.springframework.security.access.AccessDeniedException failure){error="ACCESS_DENIED";}
        catch(RuntimeException failure){error="CRM_TEMPORARILY_UNAVAILABLE";LOG.warn("CRM synchronization {} failed: {}",job.dataset(),failure.getClass().getSimpleName());}
        catch(Error fatal){error="CRM_TEMPORARILY_UNAVAILABLE";throw fatal;}
        finally{try{queue.finish(job,error,continuation);}finally{runningThreads.remove(job.token());active.remove(job.token());}}
    }
    private CrmAppointmentScope appointmentScope(CrmSyncQueue.Job job){
        String external=null;
        if(job.scope().startsWith("RECORD:"))external=job.scope().substring("RECORD:".length(),job.scope().length()-("/"+job.from()+"/"+job.to()).length());
        return new CrmAppointmentScope(job.dataset()==Dataset.CLIENT_HISTORY ? "CLIENT_HISTORY":"APPOINTMENTS",job.client(),job.from(),job.to(),external);
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}
