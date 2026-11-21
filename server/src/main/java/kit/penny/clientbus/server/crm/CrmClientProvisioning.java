package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

/** Durable one-shot external creation. A process crash never authorizes sending the write again. */
@Service
public class CrmClientProvisioning {
    static final String DDL="""
        create table if not exists crm.clientprovision (
            id uuid constraint clientprovisionpk primary key, projectid uuid not null constraint fkclientprovisionproject references crm.project(id), clientid uuid not null,
            actorid uuid not null constraint fkclientprovisionactor references dbo.employee(employeeid), requestkey uuid not null,
            context jsonb not null, draft jsonb not null, normalizedphone varchar(15) not null,
            differentperson boolean not null, status varchar(24) not null,
            confirmedclient jsonb, crmclientid uuid, errorcode varchar(64),
            createdat timestamptz not null default clock_timestamp(), updatedat timestamptz not null default clock_timestamp(),
            deadlineat timestamptz, constraint uqclientprovisionrequest unique(projectid,requestkey))
        """;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final CrmStore store;
    private final CrmRegistry registry;
    private final CrmSecretCipher cipher;
    private final CrmClientWriter writer;
    private final UiDomainEvents events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    record Operation(CrmClientProvisionDto dto,UUID actor,UUID key,CrmContext context,CrmClientDraft draft,boolean differentPerson,CrmClient confirmed) {}
    record Access(CrmContext context,CrmCredentials credentials) {}
    public CrmClientProvisioning(CrmStore store,CrmRegistry registry,CrmSecretCipher cipher,CrmClientWriter writer,
                                UiDomainEvents events,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.store=store;this.registry=registry;this.cipher=cipher;this.writer=writer;this.events=events;this.jdbc=jdbc;tx=new TransactionTemplate(manager);
    }
    public CrmClientSearchDto search(UUID projectId,UUID clientId,String query) {
        store.storage.requireReady();String q=CrmSearch.normalize(query);
        if(q.isBlank())throw CrmException.invalid();
        var access=tx.execute(s->{var project=store.project(projectId,store.guard.organization());client(clientId,project,false);configured(project);return access(project);});
        CrmClientProvisioningPort.Search result;
        try(var credentials=access.credentials().open(cipher)) {
            result=registry.require(access.credentials().provider).findClients(credentials,access.context().externalProjectId(),q,Instant.now().plusSeconds(45));
        } catch(CrmFailure failure){throw CrmStore.failure(failure);}
        return tx.execute(s->{
            var project=store.requireContext(access.context());store.guard.workspace(project.getWorkspace().getId(),false);
            client(clientId,project,false);apply(project,result.items());
            if(result.items().isEmpty())return new CrmClientSearchDto(List.of(),result.hasMore());
            var ids=result.items().stream().map(CrmClient::externalId).toList();
            var local=store.em.createQuery("from CrmClientEntity where project.id=:project and identityKind='PROJECT' and externalId in :ids",CrmClientEntity.class)
                    .setParameter("project",projectId).setParameter("ids",ids).getResultList().stream()
                    .collect(java.util.stream.Collectors.toMap(CrmClientEntity::getExternalId,CrmClientService::dto));
            return new CrmClientSearchDto(ids.stream().map(local::get).toList(),result.hasMore());
        });
    }
    public CrmClientProvisionDto create(UUID projectId,UUID key,CrmProvisionClientRequest request) {
        store.storage.requireReady();var draft=validate(key,request);
        return tx.execute(s->{
            var project=projectForClient(projectId,request.clientId());store.guard.workspace(project.getWorkspace().getId(),true);
            var existing=jdbc.queryForList("select id from crm.clientprovision where projectid=? and requestkey=?",UUID.class,projectId,key);
            if(!existing.isEmpty()) {
                var previous=row(existing.getFirst());
                if(!previous.dto().clientId().equals(request.clientId()) || !previous.draft().equals(draft)
                        || previous.differentPerson()!=request.differentPerson() || previous.context().contextVersion()!=request.contextVersion())
                    throw CrmException.conflict("CRM_IDEMPOTENCY_CONFLICT");
                return previous.dto();
            }
            configured(project);requireEnabled(client(request.clientId(),project,false));
            if(project.getContextVersion()!=request.contextVersion())throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            if(!jdbc.queryForList("select id from crm.clientprovision where projectid=? and (clientid=? or normalizedphone=?) and status in ('REQUESTED','IN_FLIGHT','UNKNOWN','CREATED')",
                    UUID.class,projectId,request.clientId(),CrmPhone.normalize(draft.phone())).isEmpty())
                throw new CrmException(409,"CRM_CLIENT_CREATION_PENDING","Создание уже выполняется или требует проверки результата.");
            if(!store.em.createQuery("from CrmClientEntity where project.id=:project and client.id=:client",CrmClientEntity.class)
                    .setParameter("project",projectId).setParameter("client",request.clientId()).getResultList().isEmpty())
                throw new CrmException(409,"CRM_CLIENT_ALREADY_LINKED","У клиента уже есть карточка в этом проекте CRM.");
            UUID id=UUID.randomUUID();
            jdbc.update("insert into crm.clientprovision(id,projectid,clientid,actorid,requestkey,context,draft,normalizedphone,differentperson,status) values (?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,'REQUESTED')",
                    id,projectId,request.clientId(),store.guard.actor(),key,JSON.writeValueAsString(store.context(project)),JSON.writeValueAsString(draft),CrmPhone.normalize(draft.phone()),request.differentPerson());
            changed(project);return row(id).dto();
        });
    }
    public CrmClientProvisionDto latest(UUID projectId,UUID clientId) {
        store.storage.requireReady();return tx.execute(s->{
            var project=store.project(projectId,store.guard.organization());client(clientId,project,false);
            var ids=jdbc.queryForList("select id from crm.clientprovision where projectid=? and clientid=? order by createdat desc,id desc limit 1",UUID.class,projectId,clientId);
            return ids.isEmpty()?null:row(ids.getFirst()).dto();
        });
    }
    /** Explicit employee resolution of an uncertain write. It never sends another create. */
    public CrmClientProvisionDto resolve(UUID id,UUID crmClientId,long version) {
        store.storage.requireReady();return tx.execute(s->{
            var initial=row(id);var project=projectForClient(initial.dto().projectId(),initial.dto().clientId());store.guard.workspace(project.getWorkspace().getId(),true);
            var operation=locked(id);completionBinding(operation,project);
            if("CONFIRMED".equals(operation.dto().status()) && crmClientId.equals(operation.dto().crmClientId()))return operation.dto();
            if(!Set.of("UNKNOWN","CREATED").contains(operation.dto().status()))throw CrmException.conflict("CRM_OPERATION_CONFLICT");
            var target=store.em.find(CrmClientEntity.class,crmClientId,LockModeType.PESSIMISTIC_WRITE);
            if(target==null || !target.getProject().getId().equals(project.getId()))throw CrmStore.notFound();
            if(target.getVersion()!=version)throw CrmException.conflict("CRM_VERSION_CONFLICT");
            if(operation.confirmed()!=null && !operation.confirmed().externalId().equals(target.getExternalId()))throw CrmException.conflict("CRM_OPERATION_CONFLICT");
            link(operation,project,target,store.guard.actor());return row(id).dto();
        });
    }
    public CrmClientProvisionDto recover(UUID id) {
        store.storage.requireReady();
        var operation=tx.execute(s->{var op=row(id);var project=projectForClient(op.dto().projectId(),op.dto().clientId());store.guard.workspace(project.getWorkspace().getId(),true);completionBinding(op,project);return op;});
        if(operation.confirmed()!=null)complete(id);
        return tx.execute(s->row(id).dto());
    }
    List<UUID> due() {
        store.storage.requireReady();
        jdbc.update("update crm.clientprovision set status='UNKNOWN',errorcode='CRM_RESULT_UNKNOWN',updatedat=clock_timestamp() where status='IN_FLIGHT' and deadlineat<clock_timestamp()");
        return jdbc.queryForList("select id from crm.clientprovision where status='REQUESTED' or status='CREATED' and errorcode is null order by createdat,id limit 1",UUID.class);
    }
    void run(UUID id) {
        if(tx.execute(s->row(id).dto().status().equals("CREATED"))){complete(id);return;}
        record Prepared(Operation operation,Access access) {}
        Prepared prepared;
        try {
            prepared=tx.execute(s->{
                var initial=row(id);if(!"REQUESTED".equals(initial.dto().status()))return null;
                var project=projectWorker(initial);
                var operation=locked(id);if(!operation.dto().status().equals("REQUESTED"))return null;
                configured(project);store.guard.manageWorkspace(operation.actor(),project.getWorkspace().getId());
                requireEnabled(client(operation.dto().clientId(),project,false));
                if(operation.dto().updatedAt().isBefore(Instant.now().minusSeconds(300)))throw CrmException.conflict("CRM_OPERATION_EXPIRED");
                if(!store.em.createQuery("from CrmClientEntity where project.id=:project and client.id=:client",CrmClientEntity.class)
                        .setParameter("project",project.getId()).setParameter("client",operation.dto().clientId()).getResultList().isEmpty())
                    throw CrmException.conflict("CRM_CLIENT_ALREADY_LINKED");
                jdbc.update("update crm.clientprovision set status='IN_FLIGHT',deadlineat=clock_timestamp()+interval '120 seconds',updatedat=clock_timestamp() where id=?",id);
                return new Prepared(operation,access(project));
            });
        } catch(RuntimeException failure){reject(id,code(failure),"REQUESTED");return;}
        if(prepared==null)return;
        boolean writeStarted=false;
        try(var credentials=prepared.access().credentials().open(cipher)) {
            var op=prepared.operation();var provider=registry.require(prepared.access().credentials().provider);
            var found=provider.findClients(credentials,op.context().externalProjectId(),CrmPhone.normalize(op.draft().phone()),Instant.now().plusSeconds(40));
            boolean duplicate=found.items().stream().anyMatch(c->Objects.equals(CrmPhone.normalize(op.draft().phone()),CrmPhone.normalize(c.contacts().get("phone"))));
            if(found.hasMore() || duplicate && !op.differentPerson()) {
                tx.executeWithoutResult(s->{var project=store.requireContext(op.context());apply(project,found.items());});
                reject(id,found.hasMore()?"CRM_CLIENT_SEARCH_INCOMPLETE":"CRM_CLIENT_DUPLICATE","IN_FLIGHT");return;
            }
            // Recheck rights and configuration after external preflight, immediately before dispatch.
            tx.executeWithoutResult(s->{var project=projectWorker(op);requireEnabled(client(op.dto().clientId(),project,false));configured(project);store.guard.manageWorkspace(op.actor(),project.getWorkspace().getId());});
            writeStarted=true;
            provider.createClient(credentials,op.context().externalProjectId(),op.draft(),Instant.now().plusSeconds(45),confirmed->{
                validateConfirmed(confirmed,op.draft());
                var saved=new CrmClient(confirmed.externalId(),"PROJECT",confirmed.displayName(),
                        Map.of("phone",confirmed.contacts().get("phone")),Map.of(),Set.of("displayName","phone"));
                tx.executeWithoutResult(s->jdbc.update("update crm.clientprovision set confirmedclient=cast(? as jsonb),status='CREATED',updatedat=clock_timestamp() where id=? and status in ('IN_FLIGHT','UNKNOWN')",JSON.writeValueAsString(saved),id));
            });
            complete(id);
            tx.executeWithoutResult(s->jdbc.update("update crm.clientprovision set status='UNKNOWN',errorcode='CRM_RESULT_UNKNOWN',updatedat=clock_timestamp() where id=? and status='IN_FLIGHT'",id));
        } catch(RuntimeException failure) {
            if(!writeStarted || failure instanceof CrmFailure crm && (crm.notDispatched() || crm.code()==CrmFailure.Code.CLIENT_PHONE_INVALID))reject(id,code(failure),"IN_FLIGHT");
            else tx.executeWithoutResult(s->jdbc.update("update crm.clientprovision set status='UNKNOWN',errorcode=?,updatedat=clock_timestamp() where id=? and status='IN_FLIGHT'",code(failure),id));
        }
    }
    private void complete(UUID id) {
        try { tx.executeWithoutResult(s->{
            var initial=row(id);if(initial.confirmed()==null)return;
            var project=projectForCompletion(initial);
            var op=locked(id);if(op.dto().status().equals("CONFIRMED"))return;
            var existing=findLocal(project.getId(),op.confirmed().externalId());
            // A search/import after creation may already contain newer contacts. Recovery only adds the link.
            if(existing==null){apply(project,List.of(op.confirmed()));existing=findLocal(project.getId(),op.confirmed().externalId());}
            link(op,project,existing,op.actor());
        }); } catch(RuntimeException failure) {
            tx.executeWithoutResult(s->jdbc.update("update crm.clientprovision set errorcode=?,updatedat=clock_timestamp() where id=? and status='CREATED'",code(failure),id));
        }
    }
    private void link(Operation op,CrmProjectEntity project,CrmClientEntity target,UUID actor) {
        if(!"PROJECT".equals(target.getIdentityKind()) || target.getClient()!=null && !target.getClient().getId().equals(op.dto().clientId()))
            throw CrmException.conflict("CRM_VERSION_CONFLICT");
        target.setClient(store.em.getReference(ClientEntity.class,op.dto().clientId()));
        target.setMappingUpdatedBy(store.em.getReference(EmployeeEntity.class,actor));target.setMappingUpdatedAt(Instant.now());target.setMappingReason(op.confirmed()==null?"RESOLVE_EXTERNAL":"CREATE_EXTERNAL");
        store.em.flush();CrmClientService.bumpRevision(store,project.getId());
        jdbc.update("update crm.clientprovision set status='CONFIRMED',crmclientid=?,errorcode=null,updatedat=clock_timestamp() where id=?",target.getId(),op.dto().id());
        changed(project);events.organization(project.getOrganization().getId(),"clients.invalidated");
    }
    private void apply(CrmProjectEntity project,List<CrmClient> rows) {
        if(rows.isEmpty())return;
        writer.apply(project,new CrmClientPage(rows,null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,project.getExternalId()+":CLIENTS"));
        CrmClientService.bumpRevision(store,project.getId());changed(project);
    }
    private CrmClientEntity findLocal(UUID project,String external) {
        return store.em.createQuery("from CrmClientEntity where project.id=:project and identityKind='PROJECT' and externalId=:external",CrmClientEntity.class)
                .setParameter("project",project).setParameter("external",external).getResultStream().findFirst().orElse(null);
    }
    private ClientEntity client(UUID id,CrmProjectEntity project,boolean lock) {
        var client=store.em.find(ClientEntity.class,id,lock?LockModeType.PESSIMISTIC_WRITE:LockModeType.NONE);
        if(client==null || !client.getOrganization().getId().equals(project.getOrganization().getId()))throw CrmStore.notFound();
        return client;
    }
    private static void requireEnabled(ClientEntity client){if(!client.isEnabled())throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");}
    private CrmProjectEntity project(UUID id) {
        var project=store.project(id,store.guard.organization());
        UUID connection=project.getConnection().getId();
        store.em.refresh(project.getConnection(),LockModeType.PESSIMISTIC_READ);store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
        if(!connection.equals(project.getConnection().getId()))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        return project;
    }
    private CrmProjectEntity projectForClient(UUID projectId,UUID clientId) {
        var project=store.project(projectId,store.guard.organization());
        client(clientId,project,true);store.lockMappingEmployees(store.guard.actor(),null);
        return project(projectId);
    }
    private CrmProjectEntity projectWorker(Operation op) {
        var initial=store.em.find(CrmProjectEntity.class,op.context().projectId());if(initial==null)throw CrmStore.notFound();
        client(op.dto().clientId(),initial,true);store.lockMappingEmployees(op.actor(),null);
        var project=store.requireContext(op.context());binding(op,project);return project;
    }
    /** Saving the result of an accepted effect must survive credential rotation, disabling or archiving. */
    private CrmProjectEntity projectForCompletion(Operation op) {
        var project=store.em.find(CrmProjectEntity.class,op.context().projectId());if(project==null)throw CrmStore.notFound();
        client(op.dto().clientId(),project,true);store.lockMappingEmployees(op.actor(),null);
        UUID connection=project.getConnection().getId();
        store.em.refresh(project.getConnection(),LockModeType.PESSIMISTIC_READ);store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
        if(!connection.equals(project.getConnection().getId()))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
        completionBinding(op,project);return project;
    }
    private void completionBinding(Operation op,CrmProjectEntity project) {
        var expected=op.context();
        if(!expected.projectId().equals(project.getId()) || !expected.organizationId().equals(project.getOrganization().getId())
                || !expected.workspaceId().equals(project.getWorkspace().getId()) || !expected.provider().equals(project.getProvider().name())
                || !expected.externalProjectId().equals(project.getExternalId()))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private void binding(Operation op,CrmProjectEntity project) {
        if(!op.context().equals(store.context(project)))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
    }
    private void configured(CrmProjectEntity project) {
        CrmStore.requireMutable(project);
        if(!project.isEnabled() || project.getConnection().getStatus()!=CrmConnectionStatus.CONNECTED)throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");
        if(!registry.require(project.getProvider()).canCreateClient())throw CrmException.conflict("CRM_CAPABILITY_UNVERIFIED");
        store.requireNetwork(project);
    }
    private Access access(CrmProjectEntity project){return new Access(store.context(project),store.credentials(project.getConnection()));}
    private Operation locked(UUID id){jdbc.queryForObject("select id from crm.clientprovision where id=? for update",UUID.class,id);return row(id);}
    private Operation row(UUID id) {
        return jdbc.query("select * from crm.clientprovision where id=?",(r,n)->{
            var draft=JSON.readValue(r.getString("draft"),CrmClientDraft.class);
            return new Operation(
                new CrmClientProvisionDto(r.getObject("id",UUID.class),r.getObject("projectid",UUID.class),r.getObject("clientid",UUID.class),r.getString("status"),r.getObject("crmclientid",UUID.class),r.getString("errorcode"),r.getTimestamp("updatedat").toInstant(),draft.phone()),
                r.getObject("actorid",UUID.class),r.getObject("requestkey",UUID.class),JSON.readValue(r.getString("context"),CrmContext.class),draft,r.getBoolean("differentperson"),
                r.getString("confirmedclient")==null?null:JSON.readValue(r.getString("confirmedclient"),CrmClient.class));
        },id).stream().findFirst().orElseThrow(CrmStore::notFound);
    }
    private void reject(UUID id,String error,String expectedStatus){tx.executeWithoutResult(s->jdbc.update("update crm.clientprovision set status='REJECTED',errorcode=?,updatedat=clock_timestamp() where id=? and status=?",error,id,expectedStatus));}
    private void changed(CrmProjectEntity project){events.workspace(project.getWorkspace(),"crm.invalidated");}
    static CrmClientDraft validate(UUID key,CrmProvisionClientRequest r) {
        if(key==null || r==null || r.clientId()==null || r.contextVersion()==null || r.contextVersion()<0 || r.firstName()==null || r.firstName().isBlank() || r.firstName().length()>50
                || r.lastName()==null || r.lastName().length()>50 || r.phone()==null || r.phone().length()>30 || CrmPhone.normalize(r.phone())==null)throw CrmException.invalid();
        return new CrmClientDraft(r.firstName().strip(),r.lastName().strip(),CrmPhone.normalize(r.phone()));
    }
    private static void validateConfirmed(CrmClient client,CrmClientDraft draft) {
        if(client==null || !"PROJECT".equals(client.identityKind()) || client.externalId()==null || client.externalId().isBlank() || client.externalId().length()>128
                || !Objects.equals(CrmPhone.normalize(draft.phone()),CrmPhone.normalize(client.contacts().get("phone")))
                || !(draft.firstName()+" "+draft.lastName()).strip().equals(client.displayName()))throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
    }
    private static String code(RuntimeException failure) {
        if(failure instanceof CrmException crm)return crm.code();
        if(failure instanceof CrmFailure crm)return CrmStore.failure(crm).code();
        if(failure instanceof org.springframework.security.access.AccessDeniedException)return "ACCESS_DENIED";
        return "CRM_TEMPORARILY_UNAVAILABLE";
    }
}
