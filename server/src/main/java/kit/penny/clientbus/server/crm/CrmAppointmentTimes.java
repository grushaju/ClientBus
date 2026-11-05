package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.server.persistence.entity.crm.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.UUID;

@Service
public class CrmAppointmentTimes {
    private final CrmStore store; private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    private final UiDomainEvents events;
    public CrmAppointmentTimes(CrmStore store,JdbcTemplate jdbc,PlatformTransactionManager manager,UiDomainEvents events) { this.store=store; this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.events=events; }
    static void derive(CrmAppointmentEntity row,CrmProjectEntity project) {
        row.setZoneId(project.getZoneId()); row.setDerivedZoneRevision(project.getZoneRevision());
        row.setStartAt(instant(row.getStartLocal(),project.getZoneId())); row.setEndAt(instant(row.getEndLocal(),project.getZoneId()));
    }
    static Instant instant(LocalDateTime local,String zone) {
        if (local==null || zone==null) return null;
        var offsets=ZoneId.of(zone).getRules().getValidOffsets(local);
        return offsets.size()==1 ? local.toInstant(offsets.getFirst()) : null;
    }
    int recalculate() {
        store.storage.requireReady();
        var projects=jdbc.queryForList("""
                select distinct p.id from crm.project p join crm.appointment a on a.projectid=p.id
                where a.derivedzonerevision is distinct from p.zonerevision order by p.id limit 2
                """,UUID.class);
        int updated=0;
        for (UUID id : projects) updated+=tx.execute(status -> {
            var project=store.em.find(CrmProjectEntity.class,id); if (project==null) return 0;
            store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
            var rows=store.em.createQuery("from CrmAppointmentEntity a where a.project.id=:project and "
                    +"(a.derivedZoneRevision is null or a.derivedZoneRevision<>:revision) order by a.id",CrmAppointmentEntity.class)
                    .setParameter("project",id).setParameter("revision",project.getZoneRevision()).setMaxResults(100).getResultList();
            rows.forEach(row -> derive(row,project));
            if (!rows.isEmpty()) {
                jdbc.update("update crm.syncstate set version=version+1,updatedat=clock_timestamp() where projectid=? and dataset='WRITER:APPOINTMENTS'",id);
                events.workspace(project.getWorkspace(),"crm.invalidated");
            }
            return rows.size();
        });
        return updated;
    }
}
