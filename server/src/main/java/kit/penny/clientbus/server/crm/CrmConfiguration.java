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
    public DikidiProvider dikidiProvider(CrmSettings settings,CrmTrafficStore pauses,CrmJournalStore journals,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.requests-per-second:4}") double perSecond,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.request-burst:32}") int burst,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.global-requests-per-second:16}") double globalRate,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.global-request-burst:128}") int globalBurst,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.request-parallelism:4}") int parallelism,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.retry-base-ms:30000}") long base,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.retry-max-ms:900000}") long max,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.journal-cache-ttl-ms:30000}") long ttl,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.journal-cache-enabled:true}") boolean cacheEnabled,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.journal-cache-entries:128}") int entries,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.journal-cache-bytes:33554432}") int bytes,
            @org.springframework.beans.factory.annotation.Value("${clientbus.crm.dikidi.journal-cache-entry-bytes:2097152}") int entryBytes) {
        var provider=new DikidiProvider(new DikidiProvider.Settings(settings.connectTimeout(), settings.requestTimeout(), settings.maxResponseBytes()),
                new kit.penny.clientbus.crm.dikidi.DikidiRequests.Settings(perSecond,burst,1024,java.time.Duration.ofMillis(base),java.time.Duration.ofMillis(max),globalRate,globalBurst),pauses).parallelism(parallelism);
        return provider.journalCache(journals,java.time.Duration.ofMillis(ttl),entries,bytes,entryBytes,cacheEnabled);
    }
}
