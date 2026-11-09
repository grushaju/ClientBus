package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.crm.api.CrmPhone;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class CrmClientService {
    private final CrmStore store;
    private final CrmClientSync sync;
    private final UiDomainEvents events;
    private final TransactionTemplate tx;
    public CrmClientService(CrmStore store, CrmClientSync sync, UiDomainEvents events, PlatformTransactionManager manager) {
        this.store=store; this.sync=sync; this.events=events; this.tx=new TransactionTemplate(manager);
    }
    public CrmClientPageDto clients(UUID projectId, String query, boolean unlinked, String cursor) {
        store.storage.requireReady();
        String search=CrmSearch.normalize(query);
        return tx.execute(status -> {
            var project=store.project(projectId, store.guard.organization());
            store.em.refresh(project, LockModeType.PESSIMISTIC_READ);
            long revision=sync.revision(projectId);
            String scope=CrmSearch.scope(projectId+"/"+search+"/"+unlinked);
            UUID after=decode(cursor, scope, revision);
            String hql="from CrmClientEntity c where c.project.id=:project"
                    +(after==null ? "" : " and c.id>:after")+(unlinked ? " and c.client is null" : "")
                    +(search.isEmpty() ? "" : " and (lower(c.displayName) like :search escape '!' or c.normalizedPhone like :phone escape '!')")
                    +" order by c.id";
            var request=store.em.createQuery(hql, CrmClientEntity.class).setParameter("project",projectId).setMaxResults(101);
            if (after!=null) request.setParameter("after",after);
            if (!search.isEmpty()) request.setParameter("search",CrmSearch.pattern(search))
                    .setParameter("phone",CrmSearch.pattern(Optional.ofNullable(CrmPhone.normalize(search)).orElse(search)));
            var rows=request.getResultList();
            boolean more=rows.size()>100;
            var items=rows.stream().limit(100).map(CrmClientService::dto).toList();
            return new CrmClientPageDto(items, more ? encode(scope,revision,items.getLast().id()) : null, more, sync.state(projectId), revision);
        });
    }
    public CrmClientDto client(UUID id) {
        store.storage.requireReady();
        return tx.execute(status -> dto(accessible(id, store.guard.organization())));
    }
    public List<CrmClientLinkDto> links(UUID clientId, UUID workspaceId) {
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization=store.guard.organization();
            var client=store.em.find(ClientEntity.class,clientId);
            if (client==null || !organization.equals(client.getOrganization().getId())) throw CrmStore.notFound();
            if (workspaceId!=null) store.guard.workspace(workspaceId,false);
            var query=store.em.createQuery("from CrmClientEntity c join fetch c.project p where c.client.id=:client and p.organization.id=:org"
                    +(workspaceId==null ? "" : " and p.workspace.id=:workspace")+" order by p.id,c.id",CrmClientEntity.class)
                    .setParameter("client",clientId).setParameter("org",organization);
            if (workspaceId!=null) query.setParameter("workspace",workspaceId);
            return query.getResultList().stream().filter(row -> store.guard.canReadWorkspace(row.getProject().getWorkspace().getId()))
                    .map(row -> new CrmClientLinkDto(dto(row),store.projectDto(row.getProject()))).toList();
        });
    }
    public List<CrmClientCandidateDto> candidates(UUID id, String query) {
        store.storage.requireReady();
        String search=CrmSearch.normalize(query);
        return tx.execute(status -> {
            UUID organization=store.guard.organization();
            var row=accessible(id, organization);
            String phone=Optional.ofNullable(row.getNormalizedPhone()).orElse("");
            String queryPhone=Optional.ofNullable(CrmPhone.normalize(search)).orElse("");
            @SuppressWarnings("unchecked") List<UUID> ids=store.em.createNativeQuery("""
                    select c.clientid from dbo.client c where c.organizationid=:org and c.isenabled and (
                        (:search<>'' and lower(c.firstname||' '||c.lastname) like :pattern escape '!')
                        or exists(select 1 from dbo.clientphone p where p.clientid=c.clientid and (
                            (:search<>'' and lower(p.phone) like :pattern escape '!')
                            or (:queryPhone<>'' and translate(p.phone,:formatting,'')=:queryPhone)
                            or (:phone<>'' and translate(p.phone,:formatting,'')=:phone))))
                    order by exists(select 1 from dbo.clientphone p where p.clientid=c.clientid and :phone<>''
                        and translate(p.phone,:formatting,'')=:phone) desc,
                        c.clientid limit 50
                    """,UUID.class).setParameter("org",organization).setParameter("search",search)
                    .setParameter("pattern",CrmSearch.pattern(search)).setParameter("phone",phone)
                    .setParameter("queryPhone",queryPhone).setParameter("formatting",CrmPhone.FORMATTING_CHARACTERS).getResultList();
            return ids.stream().map(clientId -> store.em.find(ClientEntity.class,clientId)).filter(Objects::nonNull).map(target -> new CrmClientCandidateDto(
                    target.getId(),target.getFirstName(),target.getLastName(),List.copyOf(target.getPhoneList()),
                    !phone.isEmpty() && target.getPhoneList().stream().anyMatch(value -> phone.equals(CrmPhone.normalize(value)))))
                    .sorted(Comparator.comparing(CrmClientCandidateDto::phoneMatch).reversed().thenComparing(CrmClientCandidateDto::id)).toList();
        });
    }
    public CrmClientDto link(UUID id, UUID clientId, long expectedVersion) {
        store.storage.requireReady();
        return tx.execute(status -> {
            UUID organization=store.guard.organization(), actor=store.guard.actor();
            var row=accessible(id,organization);
            store.lockMappingEmployees(actor,null);
            ClientEntity target=null;
            if (clientId!=null) {
                target=store.em.find(ClientEntity.class, clientId, LockModeType.PESSIMISTIC_WRITE);
                if (target==null || !organization.equals(target.getOrganization().getId())) throw CrmStore.notFound();
            }
            lock(row,expectedVersion);
            UUID previous=row.getClient()==null ? null : row.getClient().getId();
            if (!Objects.equals(previous,clientId)) {
                if (previous!=null) store.guard.workspace(row.getProject().getWorkspace().getId(),true);
                map(row,target,actor,clientId==null ? "UNLINK" : previous==null ? "LINK" : "RELINK");
            }
            store.em.flush(); return dto(row);
        });
    }
    public CrmClientDto create(UUID id, CreateCrmClientRequest request) {
        store.storage.requireReady(); validate(request);
        return tx.execute(status -> {
            UUID organization=store.guard.organization(), actor=store.guard.actor();
            var row=accessible(id,organization);
            store.lockMappingEmployees(actor,null);
            lock(row,request.expectedVersion());
            if (row.getClient()!=null) throw CrmException.conflict("CRM_VERSION_CONFLICT");
            var client=new ClientEntity(request.firstName().strip(),request.lastName().strip(),row.getProject().getOrganization());
            client.setPhoneList(new ArrayList<>(request.phoneList().stream().map(String::strip).distinct().toList()));
            store.em.persist(client);
            map(row,client,actor,"CREATE");
            store.em.flush(); return dto(row);
        });
    }
    private CrmClientEntity accessible(UUID id, UUID organization) {
        var row=store.em.find(CrmClientEntity.class,id);
        if (row==null || !organization.equals(row.getProject().getOrganization().getId())) throw CrmStore.notFound();
        store.guard.workspace(row.getProject().getWorkspace().getId(),false);
        return row;
    }
    private void lock(CrmClientEntity row, long expectedVersion) {
        if (expectedVersion<0) throw CrmException.invalid();
        store.em.refresh(row.getProject(),LockModeType.PESSIMISTIC_WRITE);
        CrmStore.requireMutable(row.getProject());
        store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
        store.guard.workspace(row.getProject().getWorkspace().getId(),false);
        if (row.getVersion()!=expectedVersion) throw CrmException.conflict("CRM_VERSION_CONFLICT");
    }
    private void map(CrmClientEntity row, ClientEntity target, UUID actor, String reason) {
        row.setMappingPreviousId(row.getClient()==null ? null : row.getClient().getId());
        row.setClient(target); row.setMappingUpdatedBy(store.em.getReference(EmployeeEntity.class,actor));
        row.setMappingUpdatedAt(Instant.now()); row.setMappingReason(reason);
        bumpRevision(store,row.getProject().getId());
        events.workspace(row.getProject().getWorkspace(),"crm.invalidated");
        events.organization(row.getProject().getOrganization().getId(),"clients.invalidated");
    }
    static void bumpRevision(CrmStore store, UUID projectId) {
        store.em.createNativeQuery("update crm.syncstate set version=version+1,updatedat=clock_timestamp() where projectid=:project and dataset='CLIENTS' and scopekey='ALL'")
                .setParameter("project",projectId).executeUpdate();
    }
    static CrmClientDto dto(CrmClientEntity row) {
        return new CrmClientDto(row.getId(),row.getProject().getId(),row.getExternalId(),row.getIdentityKind(),row.getDisplayName(),
                Map.copyOf(row.getContacts()),row.getNormalizedPhone(),row.getClient()==null ? null : row.getClient().getId(),
                row.getMappingUpdatedAt(),row.getMappingUpdatedBy()==null ? null : row.getMappingUpdatedBy().getId(),
                row.getMappingPreviousId(),row.getMappingReason(),row.getLastObservedAt(),row.getVersion());
    }
    private static void validate(CreateCrmClientRequest request) {
        if (request==null || request.expectedVersion()==null || request.expectedVersion()<0 || request.firstName()==null
                || request.firstName().isBlank() || request.firstName().length()>50 || request.lastName()==null || request.lastName().length()>50
                || request.phoneList()==null || request.phoneList().size()>20
                || request.phoneList().stream().anyMatch(phone -> phone==null || phone.isBlank() || phone.length()>20)) throw CrmException.invalid();
    }
    private static String encode(String scope,long revision,UUID after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:"+scope+":"+revision+":"+after).getBytes(StandardCharsets.US_ASCII));
    }
    private static UUID decode(String cursor,String scope,long revision) {
        if (cursor==null || cursor.isBlank()) return null;
        try {
            if (cursor.length()>200) throw CrmException.invalid();
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII).split(":",-1);
            if (parts.length!=4 || !parts[0].equals("1") || !parts[1].equals(scope)) throw CrmException.invalid();
            if (Long.parseLong(parts[2])!=revision) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            return UUID.fromString(parts[3]);
        } catch (IllegalArgumentException error) { throw CrmException.invalid(); }
    }
}
