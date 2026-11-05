package kit.penny.clientbus.server.crm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes project names and their catalogs even when no browser is open. */
@Component
public class CrmCatalogPolling {
    private static final Logger LOG = LoggerFactory.getLogger(CrmCatalogPolling.class);
    private final CrmCatalogSync catalogs;
    private final boolean enabled;
    private final long refreshMs;
    public CrmCatalogPolling(CrmCatalogSync catalogs,
            @Value("${clientbus.crm.catalog.enabled:true}") boolean enabled,
            @Value("${clientbus.crm.catalog.refresh-ms:300000}") long refreshMs) {
        if (refreshMs < 1000 || refreshMs > 86400000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        this.catalogs = catalogs; this.enabled = enabled; this.refreshMs = refreshMs;
    }
    @Scheduled(initialDelayString="${clientbus.crm.catalog.poll-ms:10000}",
            fixedDelayString="${clientbus.crm.catalog.poll-ms:10000}",scheduler="crmScheduleScheduler")
    public void poll() {
        if (!enabled) return;
        try { catalogs.refreshDue(refreshMs); }
        catch (RuntimeException error) { LOG.warn("CRM catalog polling failed: {}",error.getClass().getSimpleName()); }
    }
}
