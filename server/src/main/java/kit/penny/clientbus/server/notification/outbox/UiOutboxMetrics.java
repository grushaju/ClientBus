package kit.penny.clientbus.server.notification.outbox;

import io.micrometer.core.instrument.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sampling is independent of slow Kafka calls. Unavailable/stale measurements never masquerade as a fresh zero. */
@Component
@ConditionalOnProperty(prefix="clientbus.notifications",name="sse-enabled",havingValue="true")
public class UiOutboxMetrics {
    private record Snapshot(double pending,double quarantined,double oldestSeconds,long sampledAt) {}
    private final UiOutboxStore store;
    private final MeterRegistry meters;
    private volatile Snapshot snapshot;
    private volatile boolean valid;
    public UiOutboxMetrics(UiOutboxStore store,MeterRegistry meters) {
        this.store=store;this.meters=meters;
        Gauge.builder("clientbus.notification.outbox.pending",this,r->r.snapshot==null?Double.NaN:r.snapshot.pending()).register(meters);
        Gauge.builder("clientbus.notification.outbox.quarantined",this,r->r.snapshot==null?Double.NaN:r.snapshot.quarantined()).register(meters);
        Gauge.builder("clientbus.notification.outbox.oldest.seconds",this,UiOutboxMetrics::oldestSeconds).register(meters);
        Gauge.builder("clientbus.notification.outbox.sample.age.seconds",this,UiOutboxMetrics::sampleAge).register(meters);
        Gauge.builder("clientbus.notification.outbox.sample.valid",this,r->r.valid?1:0).register(meters);
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.outbox-metrics-poll-ms:2000}",scheduler="uiOutboxMetricsScheduler")
    public void sample() {
        try {
            var stats=store.statistics();
            snapshot=new Snapshot(((Number)stats.get("pending")).doubleValue(),((Number)stats.get("quarantined")).doubleValue(),
                    ((Number)stats.get("oldest_seconds")).doubleValue(),System.nanoTime());
            valid=true;
        } catch(RuntimeException error) {
            valid=false;
            meters.counter("clientbus.notification.outbox.errors","stage","statistics").increment();
        }
    }
    private double sampleAge() {
        var value=snapshot;
        return value==null?Double.NaN:(System.nanoTime()-value.sampledAt())/1_000_000_000d;
    }
    private double oldestSeconds() {
        var value=snapshot;
        return value==null?Double.NaN:value.pending()==0?0:value.oldestSeconds()+(System.nanoTime()-value.sampledAt())/1_000_000_000d;
    }
}
