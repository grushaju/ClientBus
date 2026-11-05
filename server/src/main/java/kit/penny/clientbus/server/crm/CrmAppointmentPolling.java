package kit.penny.clientbus.server.crm;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CrmAppointmentPolling {
    private static final Logger LOG=LoggerFactory.getLogger(CrmAppointmentPolling.class);
    private final CrmAppointmentSync sync;
    private final CrmAppointmentTimes times;
    private final boolean enabled;
    private final long refreshMs;
    public CrmAppointmentPolling(CrmAppointmentSync sync,CrmAppointmentTimes times,
            @Value("${clientbus.crm.appointments.enabled:true}") boolean enabled,
            @Value("${clientbus.crm.appointments.refresh-ms:300000}") long refreshMs) {
        if (refreshMs<1000 || refreshMs>86400000) throw new IllegalArgumentException("Invalid CRM refresh interval");
        this.sync=sync; this.times=times; this.enabled=enabled; this.refreshMs=refreshMs;
    }
    @Scheduled(initialDelayString="${clientbus.crm.appointments.poll-ms:10000}",
            fixedDelayString="${clientbus.crm.appointments.poll-ms:10000}",scheduler="crmScheduleScheduler")
    public void poll() {
        try { times.recalculate(); }
        catch (RuntimeException error) { LOG.warn("CRM appointment timezone recalculation failed: {}",error.getClass().getSimpleName()); }
        if (!enabled) return;
        try { sync.refreshDue(refreshMs); }
        catch (RuntimeException error) { LOG.warn("CRM appointment polling failed: {}",error.getClass().getSimpleName()); }
    }
}
