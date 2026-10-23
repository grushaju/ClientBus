package kit.penny.clientbus.server.notification.push;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(prefix="clientbus.notifications",name="push-enabled",havingValue="true")
public class PushDeliveryConfiguration {
    @Bean(name="taskScheduler")
    @ConditionalOnMissingBean(name="taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder builder){return builder.build();}
    @Bean ThreadPoolTaskScheduler pushDeliveryScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("notification-push-");return scheduler;
    }
}
