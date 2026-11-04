package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.List;

/** Terminal cursor does not by itself prove a consistent complete provider snapshot. */
public record CrmClientPage(List<CrmClient> items, String nextCursor, Instant observedAt,
                            CrmReadResult.Completeness completeness, String scope) {
    public CrmClientPage { items = List.copyOf(items); }
}
