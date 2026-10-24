package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
public class HistoryOperationWorker {
    private final HistorySyncService history;
    private final ChannelConnectorRegistry registry;
    private final MeterRegistry meters;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private final Map<UUID, HistorySyncService.Operation> active = new ConcurrentHashMap<>();
    public HistoryOperationWorker(HistorySyncService history, ChannelConnectorRegistry registry,
            HistorySyncSettings settings, MeterRegistry meters) {
        this.history = history; this.registry = registry; this.meters = meters;
        slots = new Semaphore(settings.workers());
        executor = Executors.newFixedThreadPool(settings.workers(), runnable -> {
            var thread = new Thread(runnable, "history-connector"); thread.setDaemon(true); return thread;
        });
    }
    @KafkaListener(id = "historyOperationConsumer", groupId = "${spring.kafka.consumer.connector-command-group-id}",
            topics = HistorySyncConfiguration.TOPIC, autoStartup = "${clientbus.notifications.history-consumer-enabled:true}")
    public void consume(KafkaEvent<HistorySyncCommand> event) {
        if (!history.matches(event)) throw new IllegalArgumentException("Invalid history operation command");
        offer(event.payload().operationId());
    }
    public void offer(UUID id) {
        if (!slots.tryAcquire()) return;
        if (!offered.add(id)) { slots.release(); return; }
        try { executor.execute(() -> run(id)); }
        catch (RejectedExecutionException stopped) { offered.remove(id); slots.release(); }
    }
    private void run(UUID id) {
        HistorySyncService.Operation claim = null;
        try {
            var acquired = history.claim(id);
            if (acquired.isEmpty()) return;
            claim = acquired.get(); active.put(id, claim);
            var execution = history.execution(claim);
            kit.penny.clientbus.server.connector.SyncConversationHistoryResult result;
            try { result = registry.getConnector(execution.type()).handle(new SyncConversationHistoryCommand(claim.account(), execution.externalId(), claim.before(), claim.limit())); }
            catch (RuntimeException unavailable) { history.fail(claim, "CONNECTOR_UNAVAILABLE"); return; }
            if (result == null || result.messages() == null || result.messages().size() > claim.limit())
                throw new IllegalArgumentException("Invalid history result");
            var distinct = new LinkedHashMap<String, PlatformMessageRequest>();
            for (var message : result.messages()) {
                if (message == null || message.externalId() == null || !claim.account().equals(message.channelAccountId())
                        || !execution.externalId().equals(message.clientExternalId())) throw new IllegalArgumentException("History result scope mismatch");
                distinct.putIfAbsent(message.externalId(), message);
            }
            var items = List.copyOf(distinct.values());
            // One bounded page commits with its terminal result. A failed partial page must not
            // move the UI's oldest cached cursor past messages that have not been persisted.
            history.importBatch(claim, items, execution, true, items.size(), result.historyStartReached());
        } catch (HistorySyncService.StaleClaim stale) {
            // Durable reclaim/watchdog decides the outcome; an old worker cannot complete it.
        } catch (RuntimeException error) {
            if (claim != null) {
                try { history.fail(claim, error instanceof NotificationApiException api && "ACCESS_REVOKED".equals(api.code())
                        ? "ACCESS_REVOKED" : error instanceof kit.penny.clientbus.server.exception.ChannelUnavailableException
                        ? "CONNECTOR_UNAVAILABLE" : "IMPORT_FAILED"); }
                catch (RuntimeException databaseUnavailable) { meters.counter("clientbus.history.errors", "stage", "completion").increment(); }
            }
        } finally { active.remove(id); offered.remove(id); slots.release(); }
    }
    @Scheduled(fixedDelayString = "${clientbus.notifications.history-worker-poll-ms:1000}", scheduler = "historyScheduler")
    public void tick() {
        try {
            for (var claim : active.values()) history.renew(claim);
            history.maintain();
            for (UUID id : history.due()) offer(id);
        } catch (RuntimeException error) { meters.counter("clientbus.history.errors", "stage", "maintenance").increment(); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
