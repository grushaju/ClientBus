package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.dikidi.DikidiProvider;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CrmConfiguration {
    // Infrastructure must be constructible before JPA; it must not instantiate the CRM worker.
    @Bean(name="crmScheduleScheduler",destroyMethod="shutdown",defaultCandidate=false)
    public static ThreadPoolTaskScheduler crmScheduleScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("crm-schedule-poll-"); scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    @Bean(destroyMethod = "close")
    public DikidiProvider dikidiProvider(CrmSettings settings) {
        return new DikidiProvider(new DikidiProvider.Settings(settings.connectTimeout(), settings.requestTimeout(), settings.maxResponseBytes()));
    }
}
