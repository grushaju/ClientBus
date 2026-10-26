package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class NotificationCenterMaintenance {
    private final NotificationCenterCleanup cleanup;
    private final MeterRegistry meters;
    private volatile long lastSuccessNanos = Long.MIN_VALUE;
    private volatile boolean lastAttemptSucceeded;

    public NotificationCenterMaintenance(NotificationCenterCleanup cleanup, MeterRegistry meters) {
        this.cleanup = cleanup; this.meters = meters;
        Gauge.builder("clientbus.notification.cleanup.last.success.age.seconds", this, NotificationCenterMaintenance::lastSuccessAgeSeconds)
                .register(meters);
        Gauge.builder("clientbus.notification.cleanup.valid", this, state -> state.lastAttemptSucceeded ? 1 : 0).register(meters);
    }

    @Scheduled(initialDelayString = "${clientbus.notifications.cleanup-poll-ms:60000}",
            fixedDelayString = "${clientbus.notifications.cleanup-poll-ms:60000}")
    public void tick() {
        long started = System.nanoTime();
        String result = "failure";
        try {
            int removed = cleanup.cleanBatch(100);
            if (removed > 0) meters.counter("clientbus.notification.cleanup.removed").increment(removed);
            lastSuccessNanos = System.nanoTime();
            lastAttemptSucceeded = true;
            result = "success";
        } catch (RuntimeException error) {
            lastAttemptSucceeded = false;
            meters.counter("clientbus.notification.cleanup.errors").increment();
            NotificationDiagnostics.push(NotificationDiagnostics.Stage.CENTER_CLEANUP, NotificationDiagnostics.Outcome.FAILED,
                    NotificationDiagnostics.Code.DB_UNAVAILABLE, null, null);
        } finally {
            meters.counter("clientbus.notification.cleanup.runs", "result", result).increment();
            meters.timer("clientbus.notification.cleanup.duration", "result", result)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private double lastSuccessAgeSeconds() {
        long last = lastSuccessNanos;
        return last == Long.MIN_VALUE ? Double.NaN : Math.max(0, (System.nanoTime() - last) / 1_000_000_000.0);
    }
}
