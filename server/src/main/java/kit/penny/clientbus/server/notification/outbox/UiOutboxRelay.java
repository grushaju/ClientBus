package kit.penny.clientbus.server.notification.outbox;

import io.micrometer.core.instrument.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import org.apache.kafka.common.errors.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import static kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics.*;

@Component
@ConditionalOnProperty(prefix="clientbus.notifications",name="sse-enabled",havingValue="true")
public class UiOutboxRelay {
    private final UiOutboxStore store;
    private final UiOutboxPublisher publisher;
    private final NotificationProperties config;
    private final JsonMapper mapper;
    private final MeterRegistry meters;
    public UiOutboxRelay(UiOutboxStore store,UiOutboxPublisher publisher,NotificationProperties config,JsonMapper mapper,MeterRegistry meters) {
        this.store=store;this.publisher=publisher;this.config=config;this.mapper=mapper;this.meters=meters;
    }
    @Scheduled(fixedDelayString="${clientbus.notifications.outbox-poll-ms:500}",scheduler="uiOutboxScheduler")
    public void tick() {
        try {
            for(int i=0;i<config.outboxBatchSize() && !Thread.currentThread().isInterrupted();i++) {
                var claim=store.claim(); if(claim.isEmpty()) break;
                deliver(claim.get());
            }
        } catch(RuntimeException e) {meters.counter("clientbus.notification.outbox.errors","stage","database").increment();}
    }
    void deliver(UiOutboxStore.Claim claim) {
        UiInvalidation event;
        try { event=validate(claim); }
        catch(RuntimeException e) {
            boolean updated=store.failed(claim,true,"INVALID_PAYLOAD");
            if(updated) outcome("quarantined");
            NotificationDiagnostics.event(Stage.UI_RELAY,updated?Outcome.QUARANTINED:Outcome.STALE,updated?Code.INVALID_EVENT:Code.LEASE_LOST,claim.eventId(),null,null);
            return;
        }
        try {
            long started=System.nanoTime();
            meters.counter("clientbus.notification.publish.calls","pipeline","ui").increment();
            boolean acknowledged=false;
            try {publisher.publish(claim.organizationId().toString(),claim.payload());acknowledged=true;}
            finally {meters.timer("clientbus.notification.publish.duration","pipeline","ui","result",acknowledged?"ack":"failure")
                    .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}
            boolean updated=store.published(claim);
            if(updated) outcome("published");
            NotificationDiagnostics.event(Stage.UI_RELAY,updated?Outcome.PUBLISHED:Outcome.STALE,updated?Code.NONE:Code.LEASE_LOST,claim.eventId(),event.correlationId(),null);
        } catch(Exception error) {
            Throwable cause=error;while(cause instanceof ExecutionException && cause.getCause()!=null) cause=cause.getCause();
            boolean permanent=cause instanceof AuthorizationException || cause instanceof InvalidTopicException
                    || cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof UnsupportedVersionException || cause instanceof org.apache.kafka.common.config.ConfigException;
            boolean updated=store.failed(claim,permanent,permanent?"PERMANENT_PUBLISH":"TRANSIENT_PUBLISH");
            if(updated) outcome(permanent?"quarantined":"retry");
            NotificationDiagnostics.event(Stage.UI_RELAY,updated?(permanent?Outcome.QUARANTINED:Outcome.RETRY):Outcome.STALE,
                    updated?Code.PIPELINE_UNAVAILABLE:Code.LEASE_LOST,claim.eventId(),event.correlationId(),null);
            if(cause instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    private void outcome(String result){meters.counter("clientbus.notification.outbox.attempts","result",result).increment();}
    private UiInvalidation validate(UiOutboxStore.Claim claim) {
        if(!UiKafkaSubscriber.TOPIC.equals(claim.destination()) || claim.operationId()!=null
                || claim.schemaVersion()!=1 || claim.payload().getBytes(StandardCharsets.UTF_8).length>config.maxOutboxBytes())
            throw new IllegalArgumentException();
        var event=mapper.readValue(claim.payload(),UiInvalidation.class);
        if(!claim.eventId().equals(event.eventId()) || !claim.organizationId().equals(event.organizationId())
                || !claim.type().equals(event.type()) || event.schemaVersion()!=1 || event.occurredAt()==null
                || event.origin()==null || event.audience()==null || event.recipientEmployeeIds().size()>500
                || event.type().startsWith("stream.") || event.resource()==null
                || event.resource().id()==null || event.resource().kind()==null) throw new IllegalArgumentException();
        return event;
    }
}
