package kit.penny.clientbus.server.notification.transport;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix="clientbus.notifications",name="sse-enabled",havingValue="true")
public class UiTransportConfiguration {
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler uiOutboxScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("ui-outbox-");
        return scheduler;
    }
    @Bean NewTopic uiInvalidationsTopic() {
        return new NewTopic(UiKafkaSubscriber.TOPIC,3,(short)1).configs(Map.of(
                "retention.ms","86400000","cleanup.policy","delete","max.message.bytes","65536"));
    }
}
