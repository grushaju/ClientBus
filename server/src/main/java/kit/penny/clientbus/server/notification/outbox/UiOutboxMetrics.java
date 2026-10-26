package kit.penny.clientbus.server.notification.outbox;

import io.micrometer.core.instrument.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sampling is independent of slow Kafka calls. Unavailable/stale measurements never masquerade as a fresh zero. */
@Component
public class UiOutboxMetrics {
    private record Snapshot(double pending,double quarantined,double oldestSeconds,java.util.Map<String,Object> values,long sampledAt,boolean valid) {}
    private final UiOutboxStore store;
    private final MeterRegistry meters;
    private volatile Snapshot snapshot;
    private final long staleNanos;
    private final java.util.function.LongSupplier clock;
    public UiOutboxMetrics(UiOutboxStore store,MeterRegistry meters) {
        this(store,meters,new kit.penny.clientbus.server.notification.metrics.NotificationMetricsSettings(10000,2),System::nanoTime);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public UiOutboxMetrics(UiOutboxStore store,MeterRegistry meters,kit.penny.clientbus.server.notification.metrics.NotificationMetricsSettings settings) {
        this(store,meters,settings,System::nanoTime);
    }
    UiOutboxMetrics(UiOutboxStore store,MeterRegistry meters,kit.penny.clientbus.server.notification.metrics.NotificationMetricsSettings settings,
            java.util.function.LongSupplier clock) {
        this.store=store;this.meters=meters;
        this.staleNanos=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3*settings.metricsPollMs());this.clock=clock;
        Gauge.builder("clientbus.notification.outbox.pending",this,r->r.snapshot==null?Double.NaN:r.snapshot.pending()).register(meters);
        Gauge.builder("clientbus.notification.outbox.quarantined",this,r->r.snapshot==null?Double.NaN:r.snapshot.quarantined()).register(meters);
        Gauge.builder("clientbus.notification.outbox.oldest.seconds",this,UiOutboxMetrics::oldestSeconds).register(meters);
        Gauge.builder("clientbus.notification.outbox.sample.age.seconds",this,UiOutboxMetrics::sampleAge).register(meters);
        Gauge.builder("clientbus.notification.outbox.sample.valid",this,r->r.fresh()?1:0).register(meters);
        for(String pipeline:java.util.List.of("ui","history")) {
            Gauge.builder("clientbus.notification.outbox.pipeline.pending",this,r->r.value(pipeline+"pending")).tag("pipeline",pipeline).register(meters);
            Gauge.builder("clientbus.notification.outbox.pipeline.quarantined",this,r->r.value(pipeline+"quarantined")).tag("pipeline",pipeline).register(meters);
            Gauge.builder("clientbus.notification.outbox.pipeline.oldest.seconds",this,r->r.pipelineOldest(pipeline)).tag("pipeline",pipeline).register(meters);
        }
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.metrics-poll-ms:10000}",scheduler="uiOutboxMetricsScheduler")
    public void sample() {
        try {
            var stats=store.statistics();
            snapshot=new Snapshot(((Number)stats.get("pending")).doubleValue(),((Number)stats.get("quarantined")).doubleValue(),
                    ((Number)stats.get("oldest_seconds")).doubleValue(),java.util.Map.copyOf(stats),clock.getAsLong(),true);
        } catch(RuntimeException error) {
            var previous=snapshot;
            if(previous!=null) snapshot=new Snapshot(previous.pending(),previous.quarantined(),previous.oldestSeconds(),previous.values(),previous.sampledAt(),false);
            meters.counter("clientbus.notification.outbox.errors","stage","statistics").increment();
        }
    }
    private boolean fresh() {var value=snapshot;return value!=null && value.valid() && clock.getAsLong()-value.sampledAt()<=staleNanos;}
    private double value(String key) {
        var current=snapshot;
        return current==null || !(current.values().get(key) instanceof Number number)?Double.NaN:number.doubleValue();
    }
    private double pipelineOldest(String pipeline) {
        var current=snapshot;
        if(current==null) return Double.NaN;
        var pending=current.values().get(pipeline+"pending");var oldest=current.values().get(pipeline+"oldest");
        if(!(pending instanceof Number count) || !(oldest instanceof Number age)) return Double.NaN;
        return count.doubleValue()==0?0:age.doubleValue()+(clock.getAsLong()-current.sampledAt())/1_000_000_000d;
    }
    private double sampleAge() {
        var value=snapshot;
        return value==null?Double.NaN:(clock.getAsLong()-value.sampledAt())/1_000_000_000d;
    }
    private double oldestSeconds() {
        var value=snapshot;
        return value==null?Double.NaN:value.pending()==0?0:value.oldestSeconds()+(clock.getAsLong()-value.sampledAt())/1_000_000_000d;
    }
}
