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
        try { validate(claim); }
        catch(RuntimeException e) { if(store.failed(claim,true,"INVALID_PAYLOAD")) outcome("quarantined");return; }
        try {
            publisher.publish(claim.organizationId().toString(),claim.payload());
            if(store.published(claim)) outcome("published");
        } catch(Exception error) {
            Throwable cause=error;while(cause instanceof ExecutionException && cause.getCause()!=null) cause=cause.getCause();
            boolean permanent=cause instanceof AuthorizationException || cause instanceof InvalidTopicException
                    || cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof UnsupportedVersionException || cause instanceof org.apache.kafka.common.config.ConfigException;
            if(store.failed(claim,permanent,permanent?"PERMANENT_PUBLISH":"TRANSIENT_PUBLISH")) outcome(permanent?"quarantined":"retry");
            if(cause instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    private void outcome(String result){meters.counter("clientbus.notification.outbox.attempts","result",result).increment();}
    private void validate(UiOutboxStore.Claim claim) {
        if(!UiKafkaSubscriber.TOPIC.equals(claim.destination()) || claim.operationId()!=null
                || claim.schemaVersion()!=1 || claim.payload().getBytes(StandardCharsets.UTF_8).length>config.maxOutboxBytes())
            throw new IllegalArgumentException();
        var event=mapper.readValue(claim.payload(),UiInvalidation.class);
        if(!claim.eventId().equals(event.eventId()) || !claim.organizationId().equals(event.organizationId())
                || !claim.type().equals(event.type()) || event.schemaVersion()!=1 || event.occurredAt()==null
                || event.origin()==null || event.audience()==null || event.recipientEmployeeIds().size()>500
                || event.type().startsWith("stream.") || event.resource()==null
                || event.resource().id()==null || event.resource().kind()==null) throw new IllegalArgumentException();
    }
}
