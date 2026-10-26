package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.MeterRegistry;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import kit.penny.clientbus.server.notification.outbox.UiOutboxStore;
import org.apache.kafka.common.errors.*;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import static kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics.*;

@Component
public class HistoryOutboxRelay {
    private final UiOutboxStore store;
    private final HistorySyncService history;
    private final HistoryCommandPublisher publisher;
    private final JsonMapper mapper;
    private final NotificationProperties config;
    private final MeterRegistry meters;
    public HistoryOutboxRelay(UiOutboxStore store, HistorySyncService history, HistoryCommandPublisher publisher,
            JsonMapper mapper, NotificationProperties config, MeterRegistry meters) {
        this.store = store; this.history = history; this.publisher = publisher; this.mapper = mapper;
        this.config = config; this.meters = meters;
    }
    @Scheduled(fixedDelayString = "${clientbus.notifications.history-relay-poll-ms:500}", scheduler = "historyScheduler")
    public void tick() {
        try {
            for (int i = 0; i < config.outboxBatchSize() && !Thread.currentThread().isInterrupted(); i++) {
                var claim = store.claimHistory();
                if (claim.isEmpty()) break;
                deliver(claim.get());
            }
        } catch (RuntimeException error) { meters.counter("clientbus.history.errors", "stage", "outbox").increment(); }
    }
    void deliver(UiOutboxStore.Claim claim) {
        KafkaEvent<HistorySyncCommand> event;
        try {
            event = mapper.readValue(claim.payload(), new TypeReference<>() {});
            if (claim.operationId() == null || claim.schemaVersion() != 1 || !"HISTORY_SYNC_OPERATION".equals(claim.type())
                    || !HistorySyncConfiguration.TOPIC.equals(claim.destination())
                    || claim.payload().getBytes(StandardCharsets.UTF_8).length > config.maxOutboxBytes()
                    || event == null || event.payload() == null || !claim.eventId().equals(event.eventId())
                    || !claim.operationId().equals(event.payload().operationId())
                    || !claim.organizationId().equals(event.payload().organizationId())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { failed(claim, true, "INVALID_PAYLOAD"); return; }
        final boolean canonical, publishable;
        try {
            canonical = history.matches(event);
            publishable = canonical && history.canPublish(claim.operationId());
        } catch (DataAccessException unavailable) {
            meters.counter("clientbus.history.errors", "stage", "validation").increment();
            failed(claim, false, "TRANSIENT_DATABASE");
            return;
        }
        if (!canonical) { failed(claim, true, "INVALID_PAYLOAD"); return; }
        if (!publishable) { failed(claim, true, "OPERATION_TERMINAL"); return; }
        try {
            long started=System.nanoTime();
            meters.counter("clientbus.notification.publish.calls","pipeline","history").increment();
            boolean acknowledged=false;
            try {publisher.publish(claim.operationId().toString(), claim.payload());acknowledged=true;}
            finally {meters.timer("clientbus.notification.publish.duration","pipeline","history","result",acknowledged?"ack":"failure")
                    .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}
            boolean updated=store.published(claim);
            NotificationDiagnostics.event(Stage.HISTORY_RELAY,updated?Outcome.PUBLISHED:Outcome.STALE,updated?Code.NONE:Code.LEASE_LOST,
                    claim.eventId(),claim.operationId(),claim.operationId());
        } catch (Exception error) {
            Throwable cause = error;
            while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
            boolean permanent = cause instanceof AuthorizationException || cause instanceof InvalidTopicException
                    || cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof UnsupportedVersionException || cause instanceof org.apache.kafka.common.config.ConfigException;
            failed(claim, permanent, permanent ? "PERMANENT_PUBLISH" : "TRANSIENT_PUBLISH");
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    private void failed(UiOutboxStore.Claim claim, boolean permanent, String code) {
        boolean updated=permanent?history.publicationFailed(claim, code):store.failed(claim, false, code);
        NotificationDiagnostics.event(Stage.HISTORY_RELAY,updated?(permanent?Outcome.QUARANTINED:Outcome.RETRY):Outcome.STALE,
                !updated?Code.LEASE_LOST:"INVALID_PAYLOAD".equals(code)?Code.INVALID_EVENT:"TRANSIENT_DATABASE".equals(code)?Code.DB_UNAVAILABLE:Code.PIPELINE_UNAVAILABLE,
                claim.eventId(),claim.operationId(),claim.operationId());
    }
}
