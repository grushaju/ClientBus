package kit.penny.clientbus.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.List;

/** Safe binding defaults; application configuration opts into creation and new history submissions. */
@ConfigurationProperties("clientbus.notifications")
public record NotificationProperties(
        @DefaultValue("false") boolean creationEnabled,
        @DefaultValue("false") boolean sseEnabled,
        @DefaultValue("false") boolean pushEnabled,
        @DefaultValue("false") boolean historySubmissionEnabled,
        @DefaultValue("P30D") Duration sourceReplayWindow,
        @DefaultValue("P90D") Duration dedupRetention,
        @DefaultValue("P30D") Duration centerRetention,
        @DefaultValue("P7D") Duration commandIdempotencyWindow,
        @DefaultValue("P7D") Duration historyIdempotencyWindow,
        @DefaultValue("P7D") Duration historyRetention,
        @DefaultValue("P7D") Duration deliveryRetention,
        @DefaultValue("P7D") Duration publishedOutboxRetention,
        @DefaultValue("P30D") Duration installationActivity,
        @DefaultValue("P1D") Duration assignmentAlertTtl,
        @DefaultValue("PT5M") Duration incomingAlertTtl,
        @DefaultValue("P1D") Duration failureAlertTtl,
        @DefaultValue("P1D") Duration channelAlertTtl,
        @DefaultValue("PT1H") Duration operationAlertTtl,
        @DefaultValue("P2D") Duration clientAlertDedupRetention,
        @DefaultValue("PT30S") Duration lease,
        @DefaultValue("PT2M") Duration historyDeadline,
        @DefaultValue("16384") int maxEventBytes,
        @DefaultValue("65536") int maxOutboxBytes,
        @DefaultValue("128") int queueCapacity,
        @DefaultValue("100") int outboxBatchSize,
        @DefaultValue("50") int pushBatchSize,
        @DefaultValue("15") int heartbeatSeconds
) {
    public NotificationProperties {
        for (Duration value : List.of(sourceReplayWindow, dedupRetention, centerRetention, commandIdempotencyWindow, historyIdempotencyWindow, historyRetention, deliveryRetention, publishedOutboxRetention, installationActivity, assignmentAlertTtl, incomingAlertTtl, failureAlertTtl, channelAlertTtl, operationAlertTtl, clientAlertDedupRetention, lease, historyDeadline)) {
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("Notification durations must be positive");
            }
        }
        if (maxEventBytes <= 0 || maxOutboxBytes <= 0 || queueCapacity <= 0 || outboxBatchSize <= 0 || pushBatchSize <= 0 || heartbeatSeconds <= 0 || heartbeatSeconds > 60) {
            throw new IllegalArgumentException("Notification limits must be positive");
        }
        Duration maxAlert = List.of(assignmentAlertTtl, incomingAlertTtl,
                failureAlertTtl, channelAlertTtl, operationAlertTtl)
                .stream().max(Duration::compareTo).orElseThrow();
        if (dedupRetention.compareTo(sourceReplayWindow) < 0
                || centerRetention.compareTo(maxAlert) < 0
                || clientAlertDedupRetention.compareTo(maxAlert) < 0
                || historyIdempotencyWindow.compareTo(Duration.ofDays(7)) < 0
                || historyRetention.compareTo(historyIdempotencyWindow) < 0
                || lease.compareTo(historyDeadline) >= 0
                || maxOutboxBytes < maxEventBytes
                || maxOutboxBytes > 65536 || maxEventBytes > 16384) {
            throw new IllegalArgumentException("Inconsistent notification retention, lease or size limits");
        }
    }
}
