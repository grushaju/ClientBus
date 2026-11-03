package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import kit.penny.clientbus.server.persistence.repository.crm.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Reads local observations only; a page and its freshness share the project read lock. */
@Service
public class CrmCatalogReader {
    private final CrmStore store;
    private final CrmServiceRepository services;
    private final CrmMasterServiceRepository offers;
    private final CrmCatalogSync sync;
    private final TransactionTemplate tx;
    public CrmCatalogReader(CrmStore store, CrmServiceRepository services, CrmMasterServiceRepository offers,
                            CrmCatalogSync sync, PlatformTransactionManager manager) {
        this.store = store; this.services = services; this.offers = offers; this.sync = sync;
        this.tx = new TransactionTemplate(manager);
    }
    public CrmServicePageDto services(UUID projectId, UUID masterId, UUID serviceId, boolean includeNotObserved, String cursor) {
        return services(projectId, masterId, serviceId, includeNotObserved, cursor, null);
    }
    public CrmServicePageDto services(UUID projectId, UUID masterId, UUID serviceId, boolean includeNotObserved, String cursor, String searchQuery) {
        store.storage.requireReady();
        String search = CrmSearch.normalize(searchQuery);
        return tx.execute(status -> {
            var project = store.project(projectId, store.guard.organization());
            store.em.refresh(project, LockModeType.PESSIMISTIC_READ);
            if (masterId != null && !store.masters.findById(masterId)
                    .map(row -> row.getProject().getId().equals(projectId)).orElse(false)) throw CrmStore.notFound();
            if (serviceId != null && !services.findById(serviceId)
                    .map(row -> row.getProject().getId().equals(projectId)).orElse(false)) throw CrmStore.notFound();
            long revision = sync.revision(projectId);
            String scope = CrmSearch.scope(projectId+"/"+masterId+"/"+serviceId+"/"+includeNotObserved+"/"+search);
            int page = decode(cursor, scope, revision);
            var rows = services.findAll((root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                predicates.add(cb.equal(root.get("project").get("id"), projectId));
                if (!search.isEmpty()) predicates.add(cb.or(
                        cb.like(cb.lower(root.get("displayName")), CrmSearch.pattern(search), '!'),
                        cb.like(cb.lower(root.get("category")), CrmSearch.pattern(search), '!')));
                if (serviceId != null) predicates.add(cb.equal(root.get("id"), serviceId));
                if (!includeNotObserved) predicates.add(cb.equal(root.get("observationState"), "OBSERVED"));
                if (masterId != null) {
                    var offer = query.subquery(UUID.class);
                    var source = offer.from(CrmMasterServiceEntity.class);
                    var conditions = new ArrayList<Predicate>();
                    conditions.add(cb.equal(source.get("project").get("id"), projectId));
                    conditions.add(cb.equal(source.get("master").get("id"), masterId));
                    conditions.add(cb.equal(source.get("service").get("id"), root.get("id")));
                    if (!includeNotObserved) conditions.add(cb.equal(source.get("observationState"), "OBSERVED"));
                    offer.select(source.get("id")).where(conditions.toArray(Predicate[]::new));
                    predicates.add(cb.exists(offer));
                }
                return cb.and(predicates.toArray(Predicate[]::new));
            }, PageRequest.of(page, 100, Sort.by("externalId", "id")));
            var serviceIds = rows.getContent().stream().map(CrmRecord::getId).toList();
            Map<UUID, List<CrmServiceOfferDto>> byService = serviceIds.isEmpty() ? Map.of() :
                    offers.findAllByProjectIdAndServiceIdInOrderByIdAsc(projectId, serviceIds).stream()
                    .filter(row -> masterId == null || row.getMaster().getId().equals(masterId))
                    .filter(row -> includeNotObserved || "OBSERVED".equals(row.getObservationState()))
                    .map(CrmCatalogReader::dto).collect(Collectors.groupingBy(CrmServiceOfferDto::serviceId));
            var items = rows.getContent().stream().map(row -> new CrmServiceDto(row.getId(), projectId, row.getExternalId(),
                    row.getDisplayName(), row.getCategory(), row.getAttributes(), byService.getOrDefault(row.getId(), List.of()),
                    row.getObservationState(), row.getLastObservedAt(), row.getVersion())).toList();
            return new CrmServicePageDto(items, rows.hasNext() ? encode(scope, revision, page+1) : null,
                    rows.hasNext(), sync.state(projectId, "CATALOG"), revision);
        });
    }
    private static CrmServiceOfferDto dto(CrmMasterServiceEntity row) {
        List<String> warnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(row.getAttributes().get("priceObserved"))) warnings.add("PRICE_NOT_OBSERVED");
        if (row.getPrice() == null) warnings.add("PRICE_UNKNOWN");
        if (!Boolean.TRUE.equals(row.getAttributes().get("durationObserved"))) warnings.add("DURATION_NOT_OBSERVED");
        if (row.getDurationSeconds() == null) warnings.add("DURATION_UNKNOWN");
        if (!Boolean.TRUE.equals(row.getAttributes().get("activeObserved"))) warnings.add("ACTIVE_NOT_OBSERVED");
        if (!Boolean.TRUE.equals(row.getAttributes().get("onlineObserved"))) warnings.add("ONLINE_NOT_OBSERVED");
        if (row.getCurrency() == null) warnings.add("CURRENCY_UNKNOWN");
        return new CrmServiceOfferDto(row.getId(), row.getMaster().getId(), row.getMaster().getExternalId(), row.getService().getId(),
                row.getPrice() == null ? null : row.getPrice().toPlainString(), row.getCurrency(), row.getDurationSeconds(), row.getOfferState(), row.getAttributes(),
                row.getObservationState(), row.getLastObservedAt(), List.copyOf(warnings), row.getVersion(), row.getMaster().getDisplayName());
    }
    private static String encode(String scope, long revision, int page) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:"+scope+":"+revision+":"+page).getBytes(StandardCharsets.US_ASCII));
    }
    private static int decode(String cursor, String scope, long revision) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            if (cursor.length() > 512) throw CrmException.invalid();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || !scope.equals(parts[1])) throw CrmException.invalid();
            long expected = Long.parseLong(parts[2]); int page = Integer.parseInt(parts[3]);
            if (page < 1 || page > 999 || expected < 0) throw CrmException.invalid();
            if (expected != revision) throw CrmException.conflict("CRM_CONTEXT_CHANGED");
            return page;
        } catch (IllegalArgumentException error) { throw CrmException.invalid(); }
    }
}
