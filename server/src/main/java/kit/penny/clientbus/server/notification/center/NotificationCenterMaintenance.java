package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationCenterMaintenance {
    private final NotificationCenterCleanup cleanup;
    private final MeterRegistry meters;

    public NotificationCenterMaintenance(NotificationCenterCleanup cleanup, MeterRegistry meters) {
        this.cleanup = cleanup; this.meters = meters;
    }

    @Scheduled(initialDelayString = "${clientbus.notifications.cleanup-poll-ms:60000}",
            fixedDelayString = "${clientbus.notifications.cleanup-poll-ms:60000}")
    public void tick() {
        try {
            int removed = cleanup.cleanBatch(100);
            if (removed > 0) meters.counter("clientbus.notification.cleanup.removed").increment(removed);
        } catch (RuntimeException error) { meters.counter("clientbus.notification.cleanup.errors").increment(); }
    }
}
