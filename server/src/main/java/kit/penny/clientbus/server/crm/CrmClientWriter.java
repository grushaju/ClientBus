package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Component
@Transactional(propagation=Propagation.MANDATORY)
public class CrmClientWriter {
    private final CrmStore store;
    public CrmClientWriter(CrmStore store) { this.store=store; }
    public void apply(CrmProjectEntity project, CrmClientPage page) {
        Set<List<String>> seen=new HashSet<>();
        if (page.items().size()>1000) invalid();
        for (var source : page.items()) {
            if (source.externalId()==null || source.externalId().isBlank() || source.externalId().length()>128
                    || !"PROJECT".equals(source.identityKind()) || !seen.add(List.of(source.identityKind(), source.externalId()))
                    || source.displayName()!=null && source.displayName().length()>65536
                    || !Set.of("displayName","phone").containsAll(source.presentFields())
                    || !Set.of("phone").containsAll(source.contacts().keySet())
                    || source.contacts().values().stream().anyMatch(value -> value.length()>256)) invalid();
            var rows=store.em.createQuery("from CrmClientEntity where project.id=:project and identityKind=:kind and externalId=:external", CrmClientEntity.class)
                    .setParameter("project",project.getId()).setParameter("kind",source.identityKind()).setParameter("external",source.externalId()).getResultList();
            boolean created=rows.isEmpty();
            var row=created ? new CrmClientEntity() : rows.getFirst();
            if (created) { row.setProject(project); row.setIdentityKind(source.identityKind()); row.setExternalId(source.externalId()); }
            if (source.presentFields().contains("displayName")) row.setDisplayName(source.displayName());
            if (source.presentFields().contains("phone")) {
                if (!source.contacts().containsKey("phone")) invalid();
                row.setContacts(new LinkedHashMap<>(source.contacts()));
                row.setNormalizedPhone(CrmPhone.normalize(source.contacts().get("phone")));
            }
            // Only reviewed profile fields are persisted; never raw provider HTML or arbitrary JSON.
            if ("R08".equals(source.profile().get("source"))) row.setProfile(Map.of("source","R08"));
            row.setLastObservedAt(page.observedAt());
            if (created) store.em.persist(row);
        }
        store.em.flush();
    }
    private static void invalid() { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}
