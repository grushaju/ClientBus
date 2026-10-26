package kit.penny.clientbus.server.notification.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.history.HistorySyncService;
import kit.penny.clientbus.server.notification.push.PushDeliveryStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.function.Supplier;

/** Global DB state; a scrape reads snapshots only. Each source has independent validity/freshness. */
@Component
public class NotificationWorkMetrics {
    private static final class Sample {
        private record Snapshot(Map<String,Object> values,long at,boolean valid) {}
        volatile Snapshot snapshot;
        double value(String key) {var copy=snapshot;return copy==null?Double.NaN:((Number)copy.values().get(key)).doubleValue();}
        double age() {var copy=snapshot;return copy==null?Double.NaN:(System.nanoTime()-copy.at())/1_000_000_000d;}
        double oldest(String... activeKeys) {
            var copy=snapshot;
            if(copy==null) return Double.NaN;
            double active=0;
            for(String key:activeKeys) active+=((Number)copy.values().get(key)).doubleValue();
            return active==0?0:((Number)copy.values().get("oldest_seconds")).doubleValue()+(System.nanoTime()-copy.at())/1_000_000_000d;
        }
    }
    private final Sample history=new Sample(),push=new Sample();
    private final HistorySyncService operations;
    private final PushDeliveryStore deliveries;
    private final MeterRegistry meters;
    private final long staleNanos;
    public NotificationWorkMetrics(HistorySyncService operations,PushDeliveryStore deliveries,
            NotificationProperties flags,NotificationMetricsSettings settings,MeterRegistry meters) {
        this.operations=operations;this.deliveries=deliveries;this.meters=meters;
        this.staleNanos=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3*settings.metricsPollMs());
        Gauge.builder("clientbus.notification.features.enabled",flags,f->f.creationEnabled()?1:0).tag("feature","creation").register(meters);
        Gauge.builder("clientbus.notification.features.enabled",flags,f->f.sseEnabled()?1:0).tag("feature","sse").register(meters);
        Gauge.builder("clientbus.notification.features.enabled",flags,f->f.pushEnabled()?1:0).tag("feature","push").register(meters);
        Gauge.builder("clientbus.notification.features.enabled",flags,f->f.historySubmissionEnabled()?1:0).tag("feature","history_submission").register(meters);
        Gauge.builder("clientbus.history.operations.active",history,s->s.value("pending")).tag("status","pending").register(meters);
        Gauge.builder("clientbus.history.operations.active",history,s->s.value("running")).tag("status","running").register(meters);
        Gauge.builder("clientbus.history.operations.oldest.seconds",history,s->s.oldest("pending","running")).register(meters);
        Gauge.builder("clientbus.notification.push.pending",push,s->s.value("pending")).register(meters);
        Gauge.builder("clientbus.notification.push.inflight",push,s->s.value("in_flight")).register(meters);
        Gauge.builder("clientbus.notification.push.oldest.seconds",push,s->s.oldest("pending")).register(meters);
        register("history",history);register("push",push);
        Gauge.builder("clientbus.notification.metrics.stale.after.seconds",settings,s->3*s.metricsPollMs()/1000d).register(meters);
    }
    private void register(String pipeline,Sample sample) {
        Gauge.builder("clientbus.notification.state.sample.valid",sample,s->{var copy=s.snapshot;return copy!=null && copy.valid() && System.nanoTime()-copy.at()<=staleNanos?1:0;}).tag("pipeline",pipeline).register(meters);
        Gauge.builder("clientbus.notification.state.sample.age.seconds",sample,Sample::age).tag("pipeline",pipeline).register(meters);
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.metrics-poll-ms:10000}",scheduler="uiOutboxMetricsScheduler")
    public void sample() {
        sample("history",history,operations::statistics);
        sample("push",push,deliveries::statistics);
    }
    private void sample(String pipeline,Sample target,Supplier<Map<String,Object>> query) {
        try {target.snapshot=new Sample.Snapshot(Map.copyOf(query.get()),System.nanoTime(),true);}
        catch(RuntimeException unavailable) {
            var previous=target.snapshot;
            if(previous!=null) target.snapshot=new Sample.Snapshot(previous.values(),previous.at(),false);
            meters.counter("clientbus.notification.state.sample.errors","pipeline",pipeline).increment();
        }
    }
}
