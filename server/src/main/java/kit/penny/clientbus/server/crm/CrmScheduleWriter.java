package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.INVALID_RESPONSE;

/** The caller holds the project/claim lock; the whole dated snapshot is one transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CrmScheduleWriter {
    private record Key(LocalDateTime start, LocalDateTime end, String kind) {}
    private final CrmStore store;
    public CrmScheduleWriter(CrmStore store) { this.store = store; }
    public void apply(CrmProjectEntity project, CrmMasterEntity master, CrmScheduleSnapshot result, long generation) {
        if (!master.getProject().getId().equals(project.getId()) || result.items().size() > 1000)
            throw new CrmFailure(INVALID_RESPONSE);
        Map<Key, CrmWorkInterval> received = new LinkedHashMap<>();
        for (var item : result.items()) {
            if (item == null || !master.getExternalId().equals(item.externalMasterId()) || item.localStart() == null
                    || item.localEnd() == null || !item.localEnd().isAfter(item.localStart())
                    || item.localStart().getNano()%1000 != 0 || item.localEnd().getNano()%1000 != 0
                    || item.localEnd().isAfter(item.localStart().plusDays(1))
                    || item.localStart().toLocalDate().isBefore(result.scope().dateFrom())
                    || item.localStart().toLocalDate().isAfter(result.scope().dateTo()) || !"WORK".equals(item.kind())
                    || item.externalRef() != null && (item.externalRef().isBlank() || item.externalRef().length() > 128)
                    || item.attributes().size() > 1 || item.attributes().entrySet().stream().anyMatch(entry ->
                        !entry.getKey().equals("source") || !(entry.getValue() instanceof String value) || value.length() > 64))
                throw new CrmFailure(INVALID_RESPONSE);
            if (received.put(new Key(item.localStart(), item.localEnd(), item.kind()), item) != null)
                throw new CrmFailure(INVALID_RESPONSE);
        }
        var existing = store.em.createQuery("""
                select w from CrmWorkIntervalEntity w where w.project.id=:project and w.master.id=:master
                    and w.kind='WORK' and w.startLocal>=:start and w.startLocal<:end
                """, CrmWorkIntervalEntity.class).setParameter("project", project.getId()).setParameter("master", master.getId())
                .setParameter("start", result.scope().dateFrom().atStartOfDay())
                .setParameter("end", result.scope().dateTo().plusDays(1).atStartOfDay()).setMaxResults(10001).getResultList();
        if (existing.size() > 10000) throw new CrmFailure(INVALID_RESPONSE);
        Map<Key, CrmWorkIntervalEntity> rows = new HashMap<>();
        boolean complete = result.completeness() == CrmReadResult.Completeness.COMPLETE;
        for (var row : existing) {
            var key = new Key(row.getStartLocal(), row.getEndLocal(), row.getKind());
            rows.put(key, row);
            if (complete && !received.containsKey(key)) store.em.remove(row);
        }
        received.forEach((key, value) -> {
            var row = rows.get(key);
            boolean created = row == null;
            if (row == null) {
                row = new CrmWorkIntervalEntity(); row.setProject(project); row.setMaster(master);
                row.setStartLocal(value.localStart()); row.setEndLocal(value.localEnd()); row.setKind(value.kind());
            }
            Map<String, Object> attributes = new HashMap<>(complete ? Map.of() : row.getAttributes());
            attributes.putAll(value.attributes()); row.setAttributes(attributes);
            if (complete || value.externalRef() != null) row.setExternalRef(value.externalRef());
            row.setLastObservedAt(result.observedAt()); row.setLastObservedGeneration(generation);
            if (created) store.em.persist(row);
        });
    }
}
