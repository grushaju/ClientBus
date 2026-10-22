package kit.penny.clientbus.server.notification.transport;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UiSchedulingIsolationTest {
    @Test void blockedRelayCannotBlockDefaultBusinessSchedulerOrMetricsSampling() throws Exception {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("clientbus.notifications.sse-enabled","true")));
            context.registerBean(UiSseGateway.class,()->mock(UiSseGateway.class));
            context.registerBean(ThreadPoolTaskSchedulerBuilder.class,ThreadPoolTaskSchedulerBuilder::new);
            context.register(UiTransportConfiguration.class);context.refresh();
            var relay=context.getBean("uiOutboxScheduler",ThreadPoolTaskScheduler.class);
            var metrics=context.getBean("uiOutboxMetricsScheduler",ThreadPoolTaskScheduler.class);
            var router=new TaskSchedulerRouter();router.setBeanFactory(context.getBeanFactory());
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var businessRan=new CountDownLatch(1);var metricsRan=new CountDownLatch(1);
            try {
                relay.execute(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                // Same default routing as RecentChatsSyncScheduler's unqualified @Scheduled.
                router.schedule(businessRan::countDown,Instant.now());metrics.execute(metricsRan::countDown);
                assertTrue(businessRan.await(1,TimeUnit.SECONDS));assertTrue(metricsRan.await(1,TimeUnit.SECONDS));
            } finally {release.countDown();router.destroy();}
        }
    }
}
