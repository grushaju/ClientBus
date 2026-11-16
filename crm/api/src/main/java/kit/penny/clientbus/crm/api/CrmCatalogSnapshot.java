package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** Collections belong to one catalog read. Profile name/errors are independent of catalog completeness. */
public record CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
        List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
        String projectDisplayName, CrmFailure.Code projectError, List<CrmStaff> staff,
        List<CrmStaffAccess> staffAccesses) {
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope) {
        this(masters, services, offers, observedAt, completeness, scope, null, null, List.of(), null);
    }
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
            String projectDisplayName) {
        this(masters, services, offers, observedAt, completeness, scope, projectDisplayName, null, List.of(), null);
    }
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
            String projectDisplayName, CrmFailure.Code projectError) {
        this(masters, services, offers, observedAt, completeness, scope, projectDisplayName, projectError,
                List.of(), null);
    }
    public CrmCatalogSnapshot(List<CrmMaster> masters, List<CrmCatalogService> services,
            List<CrmServiceOffer> offers, Instant observedAt, CrmReadResult.Completeness completeness, String scope,
            String projectDisplayName, CrmFailure.Code projectError, List<CrmStaff> staff) {
        this(masters, services, offers, observedAt, completeness, scope, projectDisplayName, projectError, staff, null);
    }
    public CrmCatalogSnapshot {
        masters = List.copyOf(masters); services = List.copyOf(services); offers = List.copyOf(offers);
        staff = List.copyOf(staff);
        // null — страница правок не читалась (ограниченные права учётки или сбой): записи
        // доступа не наблюдаются и не стареют; пустой список — прочитано пусто, стареют.
        staffAccesses = staffAccesses == null ? null : List.copyOf(staffAccesses);
    }
}
