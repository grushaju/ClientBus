package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import static kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="clientbus.notifications",name="sse-enabled",havingValue="true")
public class UiKafkaSubscriber implements SmartLifecycle {
    public static final String TOPIC="clientbus.ui.invalidations.v1";
    private final UiSseGateway gateway;
    private final UiGatewayProperties config;
    private final NotificationProperties limits;
    private final JsonMapper mapper;
    private final UiTransportMetrics metrics;
    private final Map<String,Object> properties;
    private final Map<String,Object> adminProperties;
    private final java.util.concurrent.atomic.AtomicLong cleanedGroups = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running;
    private volatile Consumer<String,String> consumer;
    private Thread thread;
    public UiKafkaSubscriber(UiSseGateway gateway, UiGatewayProperties config, NotificationProperties limits,
                             JsonMapper mapper,KafkaProperties kafka) {
        this(gateway,config,limits,mapper,kafka,new UiTransportMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),config,limits));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public UiKafkaSubscriber(UiSseGateway gateway, UiGatewayProperties config, NotificationProperties limits,
                             JsonMapper mapper,KafkaProperties kafka,UiTransportMetrics metrics) {
        this.gateway=gateway;this.config=config;this.limits=limits;this.mapper=mapper;this.metrics=metrics;
        properties=new HashMap<>(kafka.buildConsumerProperties());
        adminProperties=new HashMap<>(kafka.buildAdminProperties());
        // Kafka requires the Admin API timeout to cover its request timeout.
        // cleanupGroup still bounds the whole operation by the configured broker timeout.
        int adminTimeoutMillis=Math.max(1000,(int)config.brokerTimeout().toMillis());
        adminProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,adminTimeoutMillis);
        adminProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,adminTimeoutMillis);
        properties.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,"clientbus-ui-"+config.instanceId());
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG,"clientbus-ui-"+config.instanceId());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"latest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,config.maxPollRecords());
        properties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,config.maxFetchBytes());
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,65536);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,(int)config.brokerTimeout().toMillis());
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.max(1000,(int)config.brokerTimeout().toMillis()));
    }
    public String groupId() { return (String)properties.get(ConsumerConfig.GROUP_ID_CONFIG); }
    public long cleanedGroups() { return cleanedGroups.get(); }
    @Override public synchronized void start() {
        if (running) return;
        if (thread!=null && thread.isAlive()) throw new IllegalStateException("Previous UI subscriber is still stopping");
        running=true;thread=new Thread(this::consume,"ui-kafka-subscriber");thread.setDaemon(true);thread.start();
    }
    private void consume() {
        while (running) {
            Consumer<String,String> current=null;
            try {
                current=new KafkaConsumer<String,String>(properties);
                consumer=current;
                current.subscribe(List.of(TOPIC),new ConsumerRebalanceListener() {
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) { metrics.kafkaUnavailable();gateway.pipelineLost(); }
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) { metrics.kafkaUnavailable();gateway.pipelineLost(); }
                    public void onPartitionsLost(Collection<TopicPartition> partitions) { metrics.kafkaUnavailable();gateway.pipelineLost(); }
                });
                long nextProbe=0;
                while (running) {
                    var records=current.poll(Duration.ofMillis(250));
                    if (System.nanoTime()>=nextProbe) {
                        var metadata=current.partitionsFor(TOPIC,config.brokerTimeout());
                        var expected=metadata.stream().map(p -> new TopicPartition(TOPIC,p.partition())).toList();
                        if (expected.isEmpty() || !current.assignment().containsAll(expected)) { metrics.kafkaUnavailable();gateway.pipelineLost(); }
                        else {
                            // An empty poll can succeed while disconnected: obtain a broker response too.
                            var ends=current.endOffsets(expected,config.brokerTimeout());
                            sampleLag(current,ends);
                            if (running) gateway.pipelineHealthy();
                        }
                        nextProbe=System.nanoTime()+config.probeInterval().toNanos();
                    }
                    for (var record:records) receive(record.value());
                }
            } catch (WakeupException e) { metrics.kafkaUnavailable();gateway.pipelineLost(); }
            catch (RuntimeException e) {
                metrics.kafkaUnavailable();metrics.kafka(UiTransportMetrics.KafkaResult.ERROR);gateway.pipelineLost();
                NotificationDiagnostics.event(Stage.KAFKA_SUBSCRIBER,Outcome.DEGRADED,Code.PIPELINE_UNAVAILABLE,null,null,null);
                // Avoid logging payload/token or spinning on permanent authorization/configuration errors.
                if (running) java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
            } finally {
                if (current!=null) { try { current.close(Duration.ofSeconds(2)); } catch (RuntimeException ignored) { } }
                consumer=null;metrics.kafkaUnavailable();gateway.pipelineLost();
            }
        }
    }
    /** Called exclusively by the consumer owner thread, using fetched position rather than committed offsets. */
    void sampleLag(Consumer<String,String> current,Map<TopicPartition,Long> ends) {
        try {
            if(ends.isEmpty()) { metrics.kafkaUnavailable();return; }
            long remaining=0;
            for(var partition:ends.entrySet()) remaining=Math.addExact(remaining,
                    Math.max(0,partition.getValue()-current.position(partition.getKey(),Duration.ZERO)));
            metrics.kafkaLag(remaining,ends.size());
        } catch (RuntimeException unavailable) { metrics.kafkaUnavailable(); }
    }
    void receive(String json) {
        try {
            if (json==null || json.getBytes(StandardCharsets.UTF_8).length>limits.maxOutboxBytes()) throw new IllegalArgumentException();
            var event=mapper.readValue(json,UiInvalidation.class);
            if (event.eventId()==null || event.type()==null || event.organizationId()==null || event.occurredAt()==null
                    || event.audience()==null || event.recipientEmployeeIds().size()>500
                    || (event.resource()!=null && (event.resource().id()==null || event.resource().kind()==null))) throw new IllegalArgumentException();
            gateway.accept(event);metrics.kafka(UiTransportMetrics.KafkaResult.RECEIVED);
        } catch (RuntimeException e) {
            metrics.kafka(UiTransportMetrics.KafkaResult.MALFORMED);
            NotificationDiagnostics.event(Stage.KAFKA_SUBSCRIBER,Outcome.REJECTED,Code.INVALID_EVENT,null,null,null);
            gateway.malformedEvent();
        }
    }
    @Override public synchronized void stop() {
        running=false;metrics.kafkaUnavailable();gateway.pipelineLost();
        var current=consumer;if(current!=null)current.wakeup();
        if(thread!=null)java.util.concurrent.locks.LockSupport.unpark(thread);
    }
    @Override public void stop(Runnable callback) {
        Thread stopping;
        synchronized (this) { stop(); stopping=thread; }
        try {
            if(stopping!=null && stopping!=Thread.currentThread()) stopping.join(4000);
            if(stopping!=null && !stopping.isAlive() && config.cleanupGroupOnStop()) cleanupGroup();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { callback.run(); }
    }
    private void cleanupGroup() {
        // Delete only this instance's group. Kafka rejects deletion if any member
        // has rejoined; no list/delete sweep can remove another active gateway.
        Admin admin=null;
        try {
            admin=Admin.create(adminProperties);
            admin.deleteConsumerGroups(List.of(groupId())).all().get(config.brokerTimeout().toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            cleanedGroups.incrementAndGet();
            metrics.kafka(UiTransportMetrics.KafkaResult.CLEANED);
        } catch (Exception ignored) { metrics.kafka(UiTransportMetrics.KafkaResult.CLEANUP_FAILED); /* Explicit offline admin procedure. */ }
        finally { if(admin!=null) admin.close(config.brokerTimeout()); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-100; }
}
