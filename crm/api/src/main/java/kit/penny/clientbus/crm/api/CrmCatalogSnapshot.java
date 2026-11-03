package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** One source observation: its collections must never be composed from separate catalog reads. */
public record CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
        List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope) {
    public CrmCatalogSnapshot {
        masters = List.copyOf(masters); services = List.copyOf(services); offers = List.copyOf(offers);
    }
}
