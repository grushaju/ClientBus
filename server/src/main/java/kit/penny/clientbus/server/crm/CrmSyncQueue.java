package kit.penny.clientbus.server.crm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static kit.penny.clientbus.server.crm.CrmSyncProperties.Dataset;

/** Durable scheduling metadata only. Projection cursors and completeness remain in syncstate. */
@Component
public class CrmSyncQueue {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    static final String DDL="""
        create table if not exists crm.syncjob (
          id uuid constraint syncjobpk primary key, projectid uuid not null constraint fksyncjobproject references crm.project(id) on delete cascade,
          dataset varchar(32) not null, scopekey varchar(256) not null, masterid uuid, appointmentid uuid,
          datefrom date, dateto date, crmclientid uuid, force boolean not null default false, actorid uuid,
          enabled boolean not null default true, manual boolean not null default false,
          priority integer not null, status varchar(24) not null default 'PENDING', nextat timestamptz not null default clock_timestamp(),
          plannedat timestamptz not null default clock_timestamp(), expectedcontext jsonb,
          lastattemptat timestamptz, lastsuccessat timestamptz, errorcode varchar(128), failures integer not null default 0,
          claimtoken uuid, claimuntil timestamptz, constraint uqsyncjobscope unique(projectid,dataset,scopekey))
        """;
    record Job(UUID id,UUID project,UUID organization,Dataset dataset,String scope,UUID master,UUID appointment,
               LocalDate from,LocalDate to,UUID token,int failures,boolean manual,UUID client,boolean force,UUID actor) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmSyncProperties settings;
    public CrmSyncQueue(JdbcTemplate jdbc,PlatformTransactionManager manager,CrmSyncProperties settings){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.settings=settings;}
    void put(UUID project,Dataset dataset,String scope,UUID master,UUID appointment,LocalDate from,LocalDate to,boolean manual){
        jdbc.update("""
          insert into crm.syncjob(id,projectid,dataset,scopekey,masterid,appointmentid,datefrom,dateto,priority,manual)
          values (?,?,?,?,?,?,?,?,?,?) on conflict(projectid,dataset,scopekey) do update set enabled=true,plannedat=clock_timestamp(),
            manual=case when crm.syncjob.status='RUNNING' then crm.syncjob.manual else crm.syncjob.manual or excluded.manual end,
            priority=case when crm.syncjob.manual then crm.syncjob.priority else excluded.priority end,
            nextat=case when excluded.manual and crm.syncjob.status<>'RUNNING' then least(crm.syncjob.nextat,clock_timestamp()) else crm.syncjob.nextat end
          """,UUID.randomUUID(),project,dataset.name(),scope,master,appointment,from,to,manual ? settings.getManualPriority() : settings.policy(dataset).getPriority(),manual);
    }
    Job claim(){return tx.execute(status -> {
        var rows=jdbc.query("""
          select j.*,p.organizationid from crm.syncjob j join crm.project p on p.id=j.projectid join crm.connection c on c.id=p.connectionid
          where j.enabled and p.enabled and not p.archived and c.status not in ('DISABLED','REAUTH_REQUIRED')
            and (c.requestnotbefore is null or c.requestnotbefore<=clock_timestamp()) and j.nextat<=clock_timestamp()
            and (j.claimuntil is null or j.claimuntil<=clock_timestamp())
            and (select count(*) from crm.syncjob active join crm.project ap on ap.id=active.projectid
              where ap.connectionid=c.id and active.claimuntil>clock_timestamp())<?
          order by (j.priority+extract(epoch from clock_timestamp()-j.nextat)/?) desc,j.nextat,j.id
          limit 1 for update of j skip locked
          """,(rs,i)->new Job(rs.getObject("id",UUID.class),rs.getObject("projectid",UUID.class),rs.getObject("organizationid",UUID.class),
                Dataset.valueOf(rs.getString("dataset")),rs.getString("scopekey"),rs.getObject("masterid",UUID.class),rs.getObject("appointmentid",UUID.class),
                rs.getObject("datefrom",LocalDate.class),rs.getObject("dateto",LocalDate.class),UUID.randomUUID(),rs.getInt("failures"),rs.getBoolean("manual"),rs.getObject("crmclientid",UUID.class),rs.getBoolean("force"),rs.getObject("actorid",UUID.class)),settings.getConnectionWorkers(),settings.getAgingInterval().toSeconds());
        if(rows.isEmpty())return null;
        var job=rows.getFirst();
        // Atomic CAS is required: NOT EXISTS alone can see a snapshot preceding another claim's commit.
        int acquired=0;
        for(int slot=0;slot<settings.getConnectionWorkers()&&acquired==0;slot++)acquired=jdbc.update("""
            insert into crm.syncconnectionlease(connectionid,slot,claimtoken,claimuntil)
              select connectionid,?,?,clock_timestamp()+(? * interval '1 millisecond') from crm.project where id=?
            on conflict(connectionid,slot) do update set claimtoken=excluded.claimtoken,claimuntil=excluded.claimuntil
              where crm.syncconnectionlease.claimuntil is null or crm.syncconnectionlease.claimuntil<=clock_timestamp()
            """,slot,job.token(),settings.getLeaseDuration().toMillis(),job.project());
        if(acquired!=1)return null;
        jdbc.update("update crm.syncjob set status='RUNNING',errorcode=null,lastattemptat=clock_timestamp(),claimtoken=?,claimuntil=clock_timestamp()+(? * interval '1 millisecond') where id=?",
            job.token(),settings.getLeaseDuration().toMillis(),job.id());return job;
    });}
    boolean heartbeat(Job job){return Boolean.TRUE.equals(tx.execute(status->{
        int renewed=jdbc.update("update crm.syncjob set claimuntil=clock_timestamp()+(? * interval '1 millisecond') where id=? and claimtoken=? and claimuntil>clock_timestamp()",
            settings.getLeaseDuration().toMillis(),job.id(),job.token());
        if(renewed!=1)return false;
        if(jdbc.update("update crm.syncconnectionlease set claimuntil=clock_timestamp()+(? * interval '1 millisecond') where claimtoken=? and claimuntil>clock_timestamp()",settings.getLeaseDuration().toMillis(),job.token())!=1){status.setRollbackOnly();return false;}
        return true;
    }));}
    void finish(Job job,String error,boolean continuation){
        var policy=settings.policy(job.dataset());
        boolean completed=error==null&&!continuation || "ACCESS_DENIED".equals(error) || "CRM_CONTEXT_CHANGED".equals(error);
        long delay=error==null ? (continuation?policy.getContinuationDelay():policy.getRefreshInterval()).toMillis() : retryDelay(job.failures());
        tx.executeWithoutResult(status->{int changed=jdbc.update("""
          update crm.syncjob set status=?,errorcode=?,failures=case when ? then 0 else least(failures+1,1000) end,
            lastsuccessat=case when ? then clock_timestamp() else lastsuccessat end,
            nextat=clock_timestamp()+(? * interval '1 millisecond'),claimtoken=null,claimuntil=null,
            enabled=case when manual and ? then false else enabled end,
            manual=case when ? then false else manual end,
            actorid=case when ? then null else actorid end,force=case when ? then false else force end,
            expectedcontext=case when ? then null else expectedcontext end
          where id=? and claimtoken=? and claimuntil>clock_timestamp()
          """,error==null ? continuation?"PENDING":"SUCCEEDED":"FAILED",error,error==null,error==null&&!continuation,delay,
              completed,completed,completed,completed,completed,job.id(),job.token());
            if(changed==1)jdbc.update("update crm.syncconnectionlease set claimtoken=null,claimuntil=null where claimtoken=?",job.token());
        });
    }
    void request(UUID project,Dataset dataset,String scope,UUID master,UUID client,LocalDate from,LocalDate to,boolean force,UUID actor){tx.executeWithoutResult(status->{
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from crm.project p join crm.connection c on c.id=p.connectionid where p.id=? and p.enabled and not p.archived and c.status not in ('DISABLED','REAUTH_REQUIRED'))",Boolean.class,project)))
            throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");
        // Upsert holds the task row lock until its metadata is saved.
        put(project,dataset,scope,master,null,from,to,true);
        if(force && Boolean.TRUE.equals(jdbc.queryForObject("select status='RUNNING' and not force from crm.syncjob where projectid=? and dataset=? and scopekey=?",Boolean.class,project,dataset.name(),scope)))
            throw CrmException.conflict("CRM_CONNECTION_BUSY");
        jdbc.update("update crm.syncjob set crmclientid=?,force=force or ?,actorid=?,expectedcontext=null where projectid=? and dataset=? and scopekey=? and status<>'RUNNING'",client,force,actor,project,dataset.name(),scope);
    });}
    void requestPeriod(UUID project,Dataset dataset,UUID client,LocalDate from,LocalDate to,boolean force,UUID actor){tx.executeWithoutResult(status->{
        // A DIKIDI journal page is one calendar day. Keep each durable unit resumable without replaying a prefix.
        for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)){
            var scope=new CrmAppointmentScope(dataset==Dataset.CLIENT_HISTORY ? "CLIENT_HISTORY":"APPOINTMENTS",client,day,day);
            request(project,dataset,scope.key(),null,client,day,day,force,actor);
        }
    });}
    void expandPendingPeriods(){
        for(var row:jdbc.queryForList("""
            select j.* from crm.syncjob j join crm.project p on p.id=j.projectid join crm.connection c on c.id=p.connectionid
            where j.enabled and j.manual and j.dataset in ('APPOINTMENTS','CLIENT_HISTORY') and j.datefrom<j.dateto
              and (j.claimuntil is null or j.claimuntil<=clock_timestamp())
              and p.enabled and not p.archived and c.status not in ('DISABLED','REAUTH_REQUIRED')
            for update of j skip locked
            """)){
            requestPeriod((UUID)row.get("projectid"),Dataset.valueOf((String)row.get("dataset")),(UUID)row.get("crmclientid"),((java.sql.Date)row.get("datefrom")).toLocalDate(),((java.sql.Date)row.get("dateto")).toLocalDate(),(Boolean)row.get("force"),(UUID)row.get("actorid"));
            jdbc.update("update crm.syncjob set enabled=false,manual=false,status='SUPERSEDED',claimtoken=null,claimuntil=null where id=?",row.get("id"));
        }
    }
    boolean observation(UUID project,CrmAppointmentScope scope,UUID actor){
        var context=jdbc.queryForObject("select p.*,c.credentialversion from crm.project p join crm.connection c on c.id=p.connectionid where p.id=?",(rs,i)->new CrmContext(project,rs.getObject("organizationid",UUID.class),rs.getObject("workspaceid",UUID.class),rs.getString("provider"),rs.getString("externalid"),rs.getObject("connectionid",UUID.class),rs.getLong("credentialversion"),rs.getLong("contextversion"),rs.getLong("zonerevision")),project);
        return observation(project,scope,actor,context);
    }
    private boolean observation(UUID project,CrmAppointmentScope scope,UUID actor,CrmContext context){
        put(project,Dataset.APPOINTMENTS,scope.key(),null,null,scope.dateFrom(),scope.dateTo(),true);
        return jdbc.update("update crm.syncjob set force=true,actorid=?,expectedcontext=cast(? as jsonb) where projectid=? and dataset='APPOINTMENTS' and scopekey=? and status<>'RUNNING'",actor,JSON.writeValueAsString(context),project,scope.key())==1;
    }
    CrmContext expectedContext(Job job){return jdbc.queryForObject("select expectedcontext::text from crm.syncjob where id=? and claimtoken=?",(rs,i)->rs.getString(1)==null?null:JSON.readValue(rs.getString(1),CrmContext.class),job.id(),job.token());}
    void importObservations(){
        for(var row:jdbc.queryForList("""
            select s.projectid,s.scopekey,s.scope->>'dateFrom' as datefrom,s.scope->>'dateTo' as dateto,
              s.scope->>'externalAppointmentId' as externalid,s.scope->>'refreshActorId' as actor,
              s.connectionid,s.credentialversion,s.contextversion,s.zonerevision,p.organizationid,p.workspaceid,p.provider,p.externalid as projectexternalid
            from crm.syncstate s join crm.project p on p.id=s.projectid join crm.connection c on c.id=p.connectionid
            where s.dataset='APPOINTMENTS' and s.statekind='SCOPE' and p.enabled and not p.archived
              and s.connectionid=p.connectionid and s.contextversion=p.contextversion and s.zonerevision=p.zonerevision and s.credentialversion=c.credentialversion
              and s.scope->>'refreshActorId' is not null
              and ((s.scope->>'externalAppointmentId' is not null and s.scope->>'observationCompletedAt' is null) or s.status='IDLE')
              and not exists(select 1 from crm.syncjob j where j.projectid=s.projectid and j.dataset='APPOINTMENTS' and j.scopekey=s.scopekey)
            """))observation((UUID)row.get("projectid"),new CrmAppointmentScope("APPOINTMENTS",null,LocalDate.parse((String)row.get("datefrom")),LocalDate.parse((String)row.get("dateto")),(String)row.get("externalid")),UUID.fromString((String)row.get("actor")),
                new CrmContext((UUID)row.get("projectid"),(UUID)row.get("organizationid"),(UUID)row.get("workspaceid"),(String)row.get("provider"),(String)row.get("projectexternalid"),(UUID)row.get("connectionid"),((Number)row.get("credentialversion")).longValue(),((Number)row.get("contextversion")).longValue(),((Number)row.get("zonerevision")).longValue()));
    }
    void requestFinance(UUID project,UUID appointment,UUID actor){tx.executeWithoutResult(status->{
        request(project,Dataset.FINANCE,appointment.toString(),null,null,null,null,false,actor);
        jdbc.update("update crm.syncjob set appointmentid=? where projectid=? and dataset='FINANCE' and scopekey=? and status<>'RUNNING'",appointment,project,appointment.toString());
    });}
    long retryDelay(int failures){
        double base=Math.min(settings.getRetryMaxDelay().toMillis(),settings.getRetryInitialDelay().toMillis()*Math.pow(settings.getRetryMultiplier(),Math.min(failures,1000)));
        return Math.max(1000,Math.min(settings.getRetryMaxDelay().toMillis(),(long)(base*(1-settings.getRetryJitter()+Math.random()*2*settings.getRetryJitter()))));
    }
    void plan(Runnable action){tx.executeWithoutResult(status->{
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(12662)",Boolean.class)))return;
        jdbc.update("update crm.syncjob set enabled=false where not manual");action.run();
        jdbc.update("delete from crm.syncjob where not enabled and not manual and (claimuntil is null or claimuntil<=clock_timestamp()) and plannedat<clock_timestamp()-(? * interval '1 millisecond')",settings.getRetiredJobRetention().toMillis());
    });}
    List<Map<String,Object>> state(UUID project){return jdbc.queryForList("select dataset,scopekey,status,nextat,lastattemptat,lastsuccessat,errorcode,failures,datefrom,dateto,enabled from crm.syncjob where projectid=? and enabled order by dataset,scopekey",project);}
    Map<String,Object> overview(UUID project){
        var datasets=jdbc.queryForList("""
            select dataset,count(*)::int as scopes,
              count(*) filter(where claimuntil>statement_timestamp())::int as running,
              count(*) filter(where status='FAILED' and (claimuntil is null or claimuntil<=statement_timestamp()))::int as failed,
              count(*) filter(where status='SUCCEEDED' and nextat>statement_timestamp() and (claimuntil is null or claimuntil<=statement_timestamp()))::int as completed,
              count(*) filter(where (status not in ('SUCCEEDED','FAILED') or (status='SUCCEEDED' and nextat<=statement_timestamp())) and (claimuntil is null or claimuntil<=statement_timestamp()))::int as pending,
              count(*) filter(where nextat<=statement_timestamp() and (claimuntil is null or claimuntil<=statement_timestamp()))::int as due,
              case when count(lastsuccessat)=count(*) then min(lastsuccessat) else null end as lastsuccessat,
              max(lastattemptat) as lastattemptat,min(datefrom) as datefrom,max(dateto) as dateto,
              min(nextat) filter(where claimuntil is null or claimuntil<=statement_timestamp()) as nextat,
              min(errorcode) filter(where status='FAILED') as errorcode
            from crm.syncjob where projectid=? and enabled group by dataset order by dataset
            """,project);
        var clients=jdbc.queryForMap("""
            select count(*)::int as total,
              count(*) filter(where averagecheck is null)::int as missing,
              count(*) filter(where averagecheck is not null and coalesce(statisticsupdatedat,lastobservedat)>=statement_timestamp()-(? * interval '1 millisecond'))::int as fresh,
              min(coalesce(statisticsupdatedat,lastobservedat)) filter(where averagecheck is not null) as oldestat
            from crm.client where projectid=? and identitykind='PROJECT'
            """,settings.policy(Dataset.CLIENT_STATISTICS).getStaleAfter().toMillis(),project);
        var stored=jdbc.queryForMap("""
            select
              (select count(*) from crm.employee where projectid=? and ismaster and observationstate<>'MISSING') as masters,
              (select count(*) from crm.service where projectid=? and observationstate<>'MISSING') as services,
              (select count(*) from crm.appointment where projectid=? and kind='APPOINTMENT') as appointments,
              (select count(*) from crm.workinterval where projectid=?) as intervals
            """,project,project,project,project);
        return Map.of("enabled",settings.isEnabled(),"datasets",datasets,"clients",clients,"stored",stored);
    }
}
