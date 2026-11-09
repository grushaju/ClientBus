package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CrmDeletionGuard {
    private final CrmStore store;
    private final UiDomainEvents events;
    public CrmDeletionGuard(CrmStore store, UiDomainEvents events) { this.store = store; this.events = events; }
    public void workspace(WorkspaceEntity workspace) {
        store.storage.requireReady();
        store.em.lock(workspace, LockModeType.PESSIMISTIC_WRITE);
        if (store.projects.existsByWorkspaceId(workspace.getId()))
            throw new CrmException(409, "CRM_RESOURCE_IN_USE", "Workspace связан с CRM-проектом, включая отключённый проект или архив.");
    }
    public void employee(EmployeeEntity employee) {
        store.storage.requireReady();
        store.em.lock(employee, LockModeType.PESSIMISTIC_WRITE);
        if (store.em.createQuery("select count(o) from CrmOperationEntity o where o.initiatorEmployee.id=:id",Long.class)
                .setParameter("id",employee.getId()).getSingleResult()>0)
            throw new CrmException(409,"CRM_RESOURCE_IN_USE","Сотрудник указан автором операций CRM. Отключите его учётную запись.");
        if (store.masters.existsByMappingUpdatedById(employee.getId()) || store.em.createQuery(
                "select count(c) from CrmClientEntity c where c.mappingUpdatedBy.id=:id",Long.class)
                .setParameter("id",employee.getId()).getSingleResult()>0)
            throw new CrmException(409, "CRM_RESOURCE_IN_USE", "Сотрудник указан автором сопоставлений CRM. Отключите его учётную запись.");
        var linked = store.masters.findAllByEmployeeId(employee.getId()).stream()
                .sorted(Comparator.comparing((CrmMasterEntity master) -> master.getProject().getId().toString())
                        .thenComparing(master -> master.getId().toString())).toList();
        Set<UUID> changedProjects=new HashSet<>();
        var actor = store.em.getReference(EmployeeEntity.class, store.guard.actor());
        for (var master : linked) {
            store.em.refresh(master.getProject(), LockModeType.PESSIMISTIC_WRITE);
            store.guard.workspace(master.getProject().getWorkspace().getId(), true);
            store.em.refresh(master, LockModeType.PESSIMISTIC_WRITE);
            if (master.getEmployee() == null || !master.getEmployee().getId().equals(employee.getId())) continue;
            master.setEmployee(null); master.setMappingPreviousId(employee.getId());
            master.setMappingUpdatedBy(actor); master.setMappingUpdatedAt(Instant.now()); master.setMappingReason("TARGET_DELETED");
            if (changedProjects.add(master.getProject().getId())) {
                CrmCatalogSync.bumpRevision(store,master.getProject().getId());
                events.workspace(master.getProject().getWorkspace(), "crm.invalidated");
            }
        }
        store.em.flush(); // Link updates precede the core delete and roll back with it.
    }
    public void client(ClientEntity client) {
        store.storage.requireReady();
        var organization=store.guard.admin();
        if (!organization.equals(client.getOrganization().getId())) throw CrmStore.notFound();
        var actorId=store.guard.actor();
        store.lockMappingEmployees(actorId,null);
        store.em.refresh(client,LockModeType.PESSIMISTIC_WRITE);
        var projectIds=store.em.createQuery("select distinct c.project.id from CrmClientEntity c where c.client.id=:id order by c.project.id",java.util.UUID.class)
                .setParameter("id",client.getId()).getResultList();
        for (var projectId : projectIds) {
            var project=store.project(projectId,organization);
            store.guard.workspace(project.getWorkspace().getId(),true);
            store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
            var linked=store.em.createQuery("from CrmClientEntity c where c.project.id=:project and c.client.id=:id",CrmClientEntity.class)
                    .setParameter("project",projectId).setParameter("id",client.getId()).getResultList();
            for (var row : linked) {
                store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
                if (row.getClient()==null || !row.getClient().getId().equals(client.getId())) continue;
                row.setClient(null); row.setMappingPreviousId(client.getId()); row.setMappingUpdatedAt(Instant.now());
                row.setMappingUpdatedBy(store.em.getReference(EmployeeEntity.class,actorId)); row.setMappingReason("TARGET_DELETED");
            }
            CrmClientService.bumpRevision(store,projectId);
            events.workspace(project.getWorkspace(),"crm.invalidated");
        }
        store.em.flush();
    }
}
