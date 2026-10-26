package kit.penny.clientbus.server.notification.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("clientbus.notifications")
public record NotificationMetricsSettings(@DefaultValue("10000") long metricsPollMs,
        @DefaultValue("2") int metricsQueryTimeoutSeconds) {
    public NotificationMetricsSettings {
        if(metricsPollMs<1000 || metricsPollMs>60000 || metricsQueryTimeoutSeconds<1 || metricsQueryTimeoutSeconds>10)
            throw new IllegalArgumentException("Notification metrics interval/timeout is outside bounded limits");
    }
}
