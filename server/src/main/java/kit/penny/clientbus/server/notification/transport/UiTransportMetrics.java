package kit.penny.clientbus.server.notification.transport;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-instance transport metrics. Scrapes only read memory; no Kafka or database calls. */
@Component
@ConditionalOnProperty(prefix="clientbus.notifications", name="sse-enabled", havingValue="true")
public class UiTransportMetrics {
    enum CloseReason { NORMAL, PIPELINE, EXPIRED, OVERFLOW, MALFORMED, WRITE_TIMEOUT, WRITE_ERROR, ACCESS, EXECUTOR, OVERSIZE, SHUTDOWN }
    enum ResyncReason { OVERFLOW, MALFORMED }
    enum Admission { OPENED, PIPELINE, EXPIRED, CLEANUP_BUSY, LIMIT }
    enum KafkaResult { RECEIVED, MALFORMED, ERROR, CLEANED, CLEANUP_FAILED }
    private record Lag(long sampledNanos, long records, int partitions, boolean valid) {}
    private final MeterRegistry registry;
    private final UiGatewayProperties config;
    private final LongSupplier nanoTime;
    private volatile Lag lag=new Lag(0,0,0,false);

    @org.springframework.beans.factory.annotation.Autowired
    public UiTransportMetrics(MeterRegistry registry, UiGatewayProperties config, NotificationProperties limits) {
        this(registry,config,limits,System::nanoTime);
    }

    UiTransportMetrics(MeterRegistry registry, UiGatewayProperties config, NotificationProperties limits, LongSupplier nanoTime) {
        this.registry=registry;this.config=config;this.nanoTime=nanoTime;
        registry.gauge("clientbus.notification.sse.connections.capacity",config,UiGatewayProperties::maxConnections);
        registry.gauge("clientbus.notification.sse.queue.capacity",limits,NotificationProperties::queueCapacity);
        registry.gauge("clientbus.notification.sse.workers.capacity",config,UiGatewayProperties::workers);
        registry.gauge("clientbus.notification.sse.executor.queue.capacity",config,UiGatewayProperties::executorQueueCapacity);
        registry.gauge("clientbus.notification.kafka.lag.records",this,m->m.lag.sampledNanos()==0?Double.NaN:m.lag.records());
        registry.gauge("clientbus.notification.kafka.assigned.partitions",this,m->m.lag.sampledNanos()==0?Double.NaN:m.lag.partitions());
        registry.gauge("clientbus.notification.kafka.sample.age.seconds",this,m->m.lag.sampledNanos()==0?Double.NaN:
                Math.max(0,m.nanoTime.getAsLong()-m.lag.sampledNanos())/1_000_000_000d);
        registry.gauge("clientbus.notification.kafka.sample.valid",this,m->m.lagValid()?1:0);
    }

    void bind(UiSseGateway gateway) {
        registry.gauge("clientbus.notification.sse.connections",gateway,UiSseGateway::connectionCount);
        registry.gauge("clientbus.notification.sse.queue.entries",gateway,UiSseGateway::queuedEvents);
        registry.gauge("clientbus.notification.sse.workers.active",gateway,UiSseGateway::activeWriters);
        registry.gauge("clientbus.notification.sse.executor.queue.entries",gateway,UiSseGateway::queuedWriters);
        registry.gauge("clientbus.notification.sse.closers.active",gateway,UiSseGateway::activeClosers);
        registry.gauge("clientbus.notification.sse.closers.queue.entries",gateway,UiSseGateway::queuedClosers);
        registry.gauge("clientbus.notification.sse.pipeline.ready",gateway,g->g.ready()?1:0);
    }

    void admission(Admission outcome) { registry.counter("clientbus.notification.sse.admissions","result",outcome.name().toLowerCase(java.util.Locale.ROOT)).increment(); }
    void closed(CloseReason reason) { registry.counter("clientbus.notification.sse.closed","reason",reason.name().toLowerCase(java.util.Locale.ROOT)).increment(); }
    void resync(ResyncReason reason) { registry.counter("clientbus.notification.sse.resync","reason",reason.name().toLowerCase(java.util.Locale.ROOT)).increment(); }
    void kafka(KafkaResult result) {
        switch(result) {
            case RECEIVED, MALFORMED -> registry.counter("clientbus.notification.kafka.records","result",result.name().toLowerCase(java.util.Locale.ROOT)).increment();
            case ERROR -> registry.counter("clientbus.notification.kafka.errors","stage","consume").increment();
            case CLEANUP_FAILED -> registry.counter("clientbus.notification.kafka.errors","stage","cleanup").increment();
            case CLEANED -> registry.counter("clientbus.notification.kafka.groups.cleaned").increment();
        }
    }
    void queueWait(long nanos) { registry.timer("clientbus.notification.sse.queue.wait").record(Math.max(0,nanos),TimeUnit.NANOSECONDS); }
    void write(long nanos, boolean control, String outcome) {
        registry.timer("clientbus.notification.sse.write.duration","kind",control?"control":"invalidation","result",outcome)
                .record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
    }
    /** Event occurrence to successful socket write, not browser application or user delivery. */
    void invalidationAge(Instant occurredAt) {
        var age=Duration.between(occurredAt,Instant.now());
        registry.timer("clientbus.notification.sse.invalidation.age").record(age.isNegative()?Duration.ZERO:age);
    }
    void kafkaLag(long records,int partitions) { lag=new Lag(nanoTime.getAsLong(),records,partitions,true); }
    void kafkaUnavailable() { var previous=lag;lag=new Lag(previous.sampledNanos(),previous.records(),previous.partitions(),false); }
    boolean lagValid() { var snapshot=lag;return snapshot.valid() && snapshot.sampledNanos()!=0
            && nanoTime.getAsLong()-snapshot.sampledNanos()<config.pipelineStaleAfter().toNanos(); }
}
