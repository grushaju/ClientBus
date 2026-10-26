package kit.penny.clientbus.server.notification.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Sampling remains available when delivery is disabled and never runs on a relay/consumer thread. */
@Configuration
public class NotificationMetricsConfiguration {
    @Bean ThreadPoolTaskScheduler uiOutboxMetricsScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-metrics-");
        return scheduler;
    }
}
