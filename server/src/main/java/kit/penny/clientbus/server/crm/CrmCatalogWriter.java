package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Applies one catalog observation inside the worker's context/claim transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CrmCatalogWriter {
    private final CrmStore store;
    public CrmCatalogWriter(CrmStore store) { this.store = store; }
    public void apply(CrmProjectEntity project, CrmCatalogSnapshot snapshot, long generation) {
        validate(snapshot);
        if (snapshot.projectDisplayName() != null) project.setDisplayName(snapshot.projectDisplayName());
        Map<String, CrmMasterEntity> existingMasters = new HashMap<>();
        store.em.createQuery("from CrmMasterEntity where project.id=:project", CrmMasterEntity.class)
                .setParameter("project", project.getId()).getResultList().forEach(row -> existingMasters.put(row.getExternalId(), row));
        Map<String, CrmServiceEntity> existingServices = new HashMap<>();
        store.em.createQuery("from CrmServiceEntity where project.id=:project", CrmServiceEntity.class)
                .setParameter("project", project.getId()).getResultList().forEach(row -> existingServices.put(row.getExternalId(), row));
        Map<List<String>, CrmMasterServiceEntity> existingOffers = new HashMap<>();
        store.em.createQuery("from CrmMasterServiceEntity where project.id=:project", CrmMasterServiceEntity.class)
                .setParameter("project", project.getId()).getResultList().forEach(row -> existingOffers.put(
                        List.of(row.getMaster().getExternalId(), row.getService().getExternalId()), row));
        Map<String, CrmMasterEntity> masterRows = new HashMap<>();
        for (var source : snapshot.masters()) {
            var row = existingMasters.get(source.externalId());
            boolean created = row == null;
            if (created) { row = new CrmMasterEntity(); row.setProject(project); row.setExternalId(source.externalId()); }
            row.setDisplayName(source.displayName()); row.setProfile(merge(row.getProfile(), source.attributes()));
            row.setLastObservedAt(snapshot.observedAt()); row.setLastObservedGeneration(generation); row.setObservationState("OBSERVED");
            if (created) store.em.persist(row);
            masterRows.put(source.externalId(), row);
        }
        Map<String, CrmServiceEntity> serviceRows = new HashMap<>();
        for (var source : snapshot.services()) {
            var row = existingServices.get(source.externalId());
            boolean created = row == null;
            if (created) { row = new CrmServiceEntity(); row.setProject(project); row.setExternalId(source.externalId()); }
            row.setDisplayName(source.displayName());
            if (source.category() != null) row.setCategory(source.category());
            row.setAttributes(merge(row.getAttributes(), source.attributes())); row.setLastObservedAt(snapshot.observedAt());
            row.setLastObservedGeneration(generation); row.setObservationState("OBSERVED"); serviceRows.put(source.externalId(), row);
            if (created) store.em.persist(row);
        }
        for (var source : snapshot.offers()) {
            var master = masterRows.get(source.externalMasterId()); var service = serviceRows.get(source.externalServiceId());
            var row = existingOffers.get(List.of(source.externalMasterId(), source.externalServiceId()));
            boolean created = row == null;
            if (created) {
                row = new CrmMasterServiceEntity(); row.setProject(project); row.setMaster(master); row.setService(service);
            }
            if (source.presentFields().contains("price")) row.setPrice(source.price());
            if (source.presentFields().contains("durationSeconds")) row.setDurationSeconds(source.durationSeconds());
            if (source.currency() != null || source.presentFields().contains("price"))
                row.setCurrency(source.currency() == null ? project.getCurrency() : source.currency());
            row.setOfferState(merge(row.getOfferState(), source.offerState()));
            Map<String, Object> attributes = merge(row.getAttributes(), source.attributes());
            attributes.put("priceObserved", source.presentFields().contains("price"));
            attributes.put("durationObserved", source.presentFields().contains("durationSeconds"));
            attributes.put("activeObserved", source.offerState().containsKey("active"));
            attributes.put("onlineObserved", source.offerState().containsKey("online"));
            row.setAttributes(attributes); row.setLastObservedAt(snapshot.observedAt());
            row.setLastObservedGeneration(generation); row.setObservationState("OBSERVED");
            if (created) store.em.persist(row);
        }
        store.em.flush();
        if (snapshot.completeness() == CrmReadResult.Completeness.COMPLETE) {
            for (String table : List.of("master", "service", "masterservice"))
                store.em.createNativeQuery("update crm."+table+" set observationstate='NOT_OBSERVED',version=version+1,updatedat=clock_timestamp() "
                        +"where projectid=:project and (lastobservedgeneration is null or lastobservedgeneration<>:generation) "
                        +"and observationstate is distinct from 'NOT_OBSERVED'")
                        .setParameter("project", project.getId()).setParameter("generation", generation).executeUpdate();
        }
    }
    private static Map<String, Object> merge(Map<String, ?> previous, Map<String, ?> values) {
        var result = new LinkedHashMap<String, Object>(previous); result.putAll(values); return result;
    }
    private static void validate(CrmCatalogSnapshot snapshot) {
        if (snapshot.projectDisplayName() != null && snapshot.projectError() != null) invalid();
        if (snapshot.projectDisplayName() != null
                && (snapshot.projectDisplayName().isBlank() || snapshot.projectDisplayName().length() > 65536)) invalid();
        Set<String> masters = new HashSet<>(), services = new HashSet<>();
        for (var row : snapshot.masters()) if (!masters.add(id(row.externalId())) || row.displayName() == null || row.displayName().isBlank()) invalid();
        for (var row : snapshot.services()) if (!services.add(id(row.externalId())) || row.displayName() == null || row.displayName().isBlank()) invalid();
        Set<List<String>> pairs = new HashSet<>();
        for (var row : snapshot.offers()) {
            if (!masters.contains(row.externalMasterId()) || !services.contains(row.externalServiceId())
                    || !pairs.add(List.of(row.externalMasterId(), row.externalServiceId()))) invalid();
            if (row.durationSeconds() != null && row.durationSeconds() < 0) invalid();
            if (row.price() != null) {
                try {
                    var price = row.price().stripTrailingZeros();
                    if (price.signum() < 0 || price.scale() > 4 || price.precision() - (long) price.scale() > 15) invalid();
                }
                catch (ArithmeticException error) { invalid(); }
            }
            if (row.currency() != null && !row.currency().matches("[A-Z]{3}")) invalid();
        }
    }
    private static String id(String value) { if (value == null || value.isBlank() || value.length()>128) invalid(); return value; }
    private static void invalid() { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}
