package kit.penny.clientbus.server.notification.history;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.Map;

@Configuration
public class HistorySyncConfiguration {
    public static final String TOPIC = "clientbus.history-sync-operations.v1";

    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean ThreadPoolTaskScheduler historyScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("history-maintenance-");
        return scheduler;
    }

    @Bean NewTopic historyOperationsTopic() { return topic(TOPIC); }
    @Bean NewTopic historyOperationsDlq() { return topic(TOPIC + ".dlq"); }

    private NewTopic topic(String name) {
        return new NewTopic(name, 1, (short) 1).configs(Map.of("retention.ms", "604800000",
                "cleanup.policy", "delete", "max.message.bytes", "65536"));
    }
}
