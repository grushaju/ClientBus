package kit.penny.clientbus.server.crm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polling has its own thread and never holds up message/notification scheduling. */
@Component
public class CrmSchedulePolling {
    private static final Logger LOG=LoggerFactory.getLogger(CrmSchedulePolling.class);
    private final CrmScheduleSync schedules;
    private final boolean enabled;
    private final long refreshMs;
    public CrmSchedulePolling(CrmScheduleSync schedules,
            @Value("${clientbus.crm.schedules.enabled:true}") boolean enabled,
            @Value("${clientbus.crm.schedules.refresh-ms:300000}") long refreshMs) {
        if (refreshMs<1000 || refreshMs>86400000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        this.schedules=schedules; this.enabled=enabled; this.refreshMs=refreshMs;
    }
    @Scheduled(initialDelayString="${clientbus.crm.schedules.poll-ms:10000}",
            fixedDelayString="${clientbus.crm.schedules.poll-ms:10000}",scheduler="crmScheduleScheduler")
    public void poll() {
        if (!enabled) return;
        try { schedules.refreshDue(refreshMs); }
        catch (RuntimeException error) { LOG.warn("CRM schedule polling failed: {}",error.getClass().getSimpleName()); }
    }
}
