package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.Instant;

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
            throw new CrmException(409, "CRM_RESOURCE_IN_USE", "Workspace связан с CRM-проектом, включая отключённый.");
    }
    public void employee(EmployeeEntity employee) {
        store.storage.requireReady();
        store.em.lock(employee, LockModeType.PESSIMISTIC_WRITE);
        if (store.masters.existsByMappingUpdatedById(employee.getId()))
            throw new CrmException(409, "CRM_RESOURCE_IN_USE", "Сотрудник указан автором сопоставлений CRM. Отключите его учётную запись.");
        var linked = store.masters.findAllByEmployeeId(employee.getId());
        var actor = store.em.getReference(EmployeeEntity.class, store.guard.actor());
        for (var master : linked) {
            store.guard.workspace(master.getProject().getWorkspace().getId(), true);
            store.em.refresh(master, LockModeType.PESSIMISTIC_WRITE);
            if (master.getEmployee() == null || !master.getEmployee().getId().equals(employee.getId())) continue;
            master.setEmployee(null); master.setMappingPreviousId(employee.getId());
            master.setMappingUpdatedBy(actor); master.setMappingUpdatedAt(Instant.now()); master.setMappingReason("TARGET_DELETED");
            events.workspace(master.getProject().getWorkspace(), "crm.invalidated");
        }
        store.em.flush(); // Link updates precede the core delete and roll back with it.
    }
}

