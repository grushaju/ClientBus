package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** Collections belong to one catalog read. Profile name/errors are independent of catalog completeness. */
public record CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
        List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
        String projectDisplayName, CrmFailure.Code projectError) {
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope) {
        this(masters, services, offers, observedAt, completeness, scope, null, null);
    }
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
            String projectDisplayName) {
        this(masters, services, offers, observedAt, completeness, scope, projectDisplayName, null);
    }
    public CrmCatalogSnapshot {
        masters = List.copyOf(masters); services = List.copyOf(services); offers = List.copyOf(offers);
    }
}
