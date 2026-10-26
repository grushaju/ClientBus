package kit.penny.clientbus.server.notification.push;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Persistence counters describe committed rows, independently of provider attempts and transport flags. */
@Component
public class PushDeliveryMetrics {
    private static final Set<String> STATUSES = Set.of("RETRY", "PROVIDER_ACCEPTED", "FAILED", "CANCELLED", "EXPIRED");
    private final MeterRegistry meters;

    public PushDeliveryMetrics(MeterRegistry meters) { this.meters = meters; }

    public void queued(int inserted) {
        if (inserted > 0) afterCommit(() -> meters.counter("clientbus.notification.push.queued").increment(inserted));
    }

    public void transition(String status, int changed, UUID notificationId, UUID deliveryId, String reason) {
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Unknown delivery status");
        if (changed <= 0) {
            if (deliveryId != null) stale(notificationId, deliveryId);
            return;
        }
        afterCommit(() -> {
            meters.counter("clientbus.notification.push.transitions", "status", status.toLowerCase(Locale.ROOT)).increment(changed);
            if (deliveryId != null) {
                var outcome = switch (status) {
                    case "RETRY" -> NotificationDiagnostics.Outcome.RETRY;
                    case "PROVIDER_ACCEPTED" -> NotificationDiagnostics.Outcome.ACCEPTED;
                    case "FAILED" -> NotificationDiagnostics.Outcome.FAILED;
                    case "CANCELLED" -> NotificationDiagnostics.Outcome.CANCELLED;
                    case "EXPIRED" -> NotificationDiagnostics.Outcome.EXPIRED;
                    default -> throw new IllegalArgumentException("Unknown delivery status");
                };
                NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER, outcome,
                        diagnosticCode(status, reason), notificationId, deliveryId);
            }
        });
    }

    public void cleaned(String resource, int changed) {
        if (!Set.of("installations_disabled", "installations_removed", "deliveries_expired", "deliveries_removed").contains(resource))
            throw new IllegalArgumentException("Unknown cleanup resource");
        if (changed > 0) afterCommit(() -> meters.counter("clientbus.notification.push.cleanup", "resource", resource).increment(changed));
    }

    public void stale(UUID notificationId, UUID deliveryId) {
        afterCommit(() -> {
            meters.counter("clientbus.notification.push.stale").increment();
            NotificationDiagnostics.push(NotificationDiagnostics.Stage.PUSH_WORKER, NotificationDiagnostics.Outcome.STALE,
                    NotificationDiagnostics.Code.LEASE_LOST, notificationId, deliveryId);
        });
    }

    private static NotificationDiagnostics.Code diagnosticCode(String status, String reason) {
        if (status.equals("PROVIDER_ACCEPTED")) return NotificationDiagnostics.Code.PROVIDER_ACCEPTED;
        if (status.equals("EXPIRED")) return NotificationDiagnostics.Code.TTL_EXPIRED;
        if (reason == null) return NotificationDiagnostics.Code.NONE;
        return switch (reason) {
            case "INVALID_ENDPOINT" -> NotificationDiagnostics.Code.INVALID_ENDPOINT;
            case "INVALID_PAYLOAD" -> NotificationDiagnostics.Code.INVALID_PAYLOAD;
            case "INVALID_CONFIGURATION" -> NotificationDiagnostics.Code.INVALID_CONFIGURATION;
            case "INVALID_PROVIDER_RESULT" -> NotificationDiagnostics.Code.INVALID_EVENT;
            case "ALERT_INACTIVE" -> NotificationDiagnostics.Code.ALERT_INACTIVE;
            case "ENDPOINT_UNAVAILABLE" -> NotificationDiagnostics.Code.ENDPOINT_UNAVAILABLE;
            case "PROVIDER_REJECTED" -> NotificationDiagnostics.Code.PROVIDER_REJECTED;
            case "PROVIDER_RETRY" -> NotificationDiagnostics.Code.PROVIDER_RETRY;
            case "PROVIDER_TIMEOUT" -> NotificationDiagnostics.Code.PROVIDER_TIMEOUT;
            case "PROVIDER_UNAVAILABLE", "PROVIDER_FAILURE" -> NotificationDiagnostics.Code.PROVIDER_UNAVAILABLE;
            case "PROVIDER_BUSY" -> NotificationDiagnostics.Code.QUEUE_FULL;
            case "INTERRUPTED" -> NotificationDiagnostics.Code.SHUTDOWN;
            case "ENDPOINT_CHANGED", "REGISTRATION_CHANGED" -> NotificationDiagnostics.Code.REGISTRATION_STALE;
            case "ACCESS_OR_BINDING_CHANGED" -> NotificationDiagnostics.Code.ACCESS_REVOKED;
            case "POLICY_OR_DATABASE_UNAVAILABLE" -> NotificationDiagnostics.Code.UNAVAILABLE;
            case "RATE_LIMITED" -> NotificationDiagnostics.Code.RATE_LIMITED;
            default -> NotificationDiagnostics.Code.INTERNAL_ERROR;
        };
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { observe(action); }
            });
        } else {
            // Also supports direct invocation in isolated tests; application mutations are transactional.
            observe(action);
        }
    }

    private static void observe(Runnable action) {
        try { action.run(); }
        catch (RuntimeException unavailable) {
            // Persistence has already committed. A failed registry/appender must not report business failure.
            // Avoid recursive diagnostics here because the observation backend itself may be unavailable.
        }
    }
}
