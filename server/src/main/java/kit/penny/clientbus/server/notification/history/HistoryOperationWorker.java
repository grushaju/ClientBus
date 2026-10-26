package kit.penny.clientbus.server.notification.history;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.notification.center.NotificationApiException;
import kit.penny.clientbus.server.notification.contract.HistorySyncCommand;
import kit.penny.clientbus.server.notification.diagnostics.NotificationDiagnostics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Component
public class HistoryOperationWorker implements AutoCloseable {
    private final HistorySyncService history;
    private final ChannelConnectorRegistry registry;
    private final MeterRegistry meters;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final int capacity;
    private final Object lifecycle = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger connectorCalls = new AtomicInteger();
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private record ActiveClaim(HistorySyncService.Operation operation, AtomicBoolean lostOwnership) {}
    private final Map<UUID, ActiveClaim> active = new ConcurrentHashMap<>();
    private volatile long maintenanceSuccessNanos = Long.MIN_VALUE;
    private volatile boolean maintenanceValid;
    public HistoryOperationWorker(HistorySyncService history, ChannelConnectorRegistry registry,
            HistorySyncSettings settings, MeterRegistry meters) {
        this.history = history; this.registry = registry; this.meters = meters;
        capacity = settings.workers();
        slots = new Semaphore(capacity);
        executor = Executors.newFixedThreadPool(settings.workers(), runnable -> {
            var thread = new Thread(runnable, "history-connector"); thread.setDaemon(true); return thread;
        });
        Gauge.builder("clientbus.history.worker.slots.occupied",this,state->state.capacity-state.slots.availablePermits()).register(meters);
        Gauge.builder("clientbus.history.worker.active",connectorCalls,AtomicInteger::get).register(meters);
        Gauge.builder("clientbus.history.worker.capacity",this,state->state.capacity).register(meters);
        Gauge.builder("clientbus.history.worker.admission.enabled",this,state->state.stopped.get()?0:1).register(meters);
        Gauge.builder("clientbus.history.maintenance.last.success.age.seconds",this,HistoryOperationWorker::maintenanceAgeSeconds).register(meters);
        Gauge.builder("clientbus.history.maintenance.valid",this,state->state.maintenanceValid?1:0).register(meters);
    }
    @KafkaListener(id = "historyOperationConsumer", groupId = "${spring.kafka.consumer.connector-command-group-id}",
            clientIdPrefix = kit.penny.clientbus.server.kafka.config.HistoryConsumerMetrics.CLIENT_ID_PREFIX,
            topics = HistorySyncConfiguration.TOPIC, autoStartup = "${clientbus.notifications.history-consumer-enabled:true}")
    public void consume(KafkaEvent<HistorySyncCommand> event) {
        if (stopped.get()) return; // Durable due scanning resumes accepted operations after restart.
        if (!history.matches(event)) throw new IllegalArgumentException("Invalid history operation command");
        offer(event.payload().operationId());
    }
    public void offer(UUID id) {
        synchronized (lifecycle) {
            if (stopped.get()) { offerOutcome("stopped"); return; }
            if (!slots.tryAcquire()) { offerOutcome("full"); return; }
            if (!offered.add(id)) { slots.release(); offerOutcome("duplicate"); return; }
            var task = new ClaimTask(id);
            try { executor.execute(task); offerOutcome("accepted"); }
            catch (RejectedExecutionException rejected) { task.cancelBeforeStart(); offerOutcome("stopped"); }
        }
    }
    private final class ClaimTask implements Runnable {
        private final UUID id;
        private final AtomicBoolean started = new AtomicBoolean();
        private ClaimTask(UUID id) { this.id = id; }
        @Override public void run() { if (started.compareAndSet(false,true)) runOperation(id); }
        private void cancelBeforeStart() {
            if (started.compareAndSet(false,true)) { offered.remove(id); slots.release(); }
        }
    }
    private void runOperation(UUID id) {
        HistorySyncService.Operation claim = null;
        ActiveClaim state = null;
        try {
            if (stopped.get()) return;
            var acquired = history.claim(id);
            if (acquired.isEmpty()) return;
            claim = acquired.get(); state = new ActiveClaim(claim,new AtomicBoolean()); active.put(id, state);
            NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.STARTED,
                    NotificationDiagnostics.Code.NONE,null,id,id);
            if (stopped.get()) return;
            var execution = history.execution(claim);
            kit.penny.clientbus.server.connector.SyncConversationHistoryResult result;
            try {
                var connector = registry.getConnector(execution.type());
                if (stopped.get()) return;
                long started = System.nanoTime();
                connectorCalls.incrementAndGet();
                meters.counter("clientbus.history.connector.calls").increment();
                try { result = connector.handle(new SyncConversationHistoryCommand(claim.account(), execution.externalId(), claim.before(), claim.limit())); }
                finally {
                    connectorCalls.decrementAndGet();
                    meters.timer("clientbus.history.connector.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
                }
            }
            catch (RuntimeException unavailable) { if (!stopped.get()) history.fail(claim, "CONNECTOR_UNAVAILABLE"); return; }
            if (stopped.get()) return;
            if (state.lostOwnership().get()) { stale(id,"completion"); return; }
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
            stale(id,"completion");
        } catch (RuntimeException error) {
            if (claim != null && !stopped.get()) {
                try { history.fail(claim, error instanceof NotificationApiException api && "ACCESS_REVOKED".equals(api.code())
                        ? "ACCESS_REVOKED" : error instanceof kit.penny.clientbus.server.exception.ChannelUnavailableException
                        ? "CONNECTOR_UNAVAILABLE" : "IMPORT_FAILED"); }
                catch (RuntimeException databaseUnavailable) {
                    meters.counter("clientbus.history.errors", "stage", "completion").increment();
                    NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.FAILED,
                            NotificationDiagnostics.Code.DB_UNAVAILABLE,null,id,id);
                }
            } else if (claim == null && !stopped.get()) {
                meters.counter("clientbus.history.errors", "stage", "claim").increment();
                NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.FAILED,
                        NotificationDiagnostics.Code.DB_UNAVAILABLE,null,id,id);
            }
        } finally { active.remove(id); offered.remove(id); slots.release(); }
    }
    @Scheduled(fixedDelayString = "${clientbus.notifications.history-worker-poll-ms:1000}", scheduler = "historyScheduler")
    public void tick() {
        if (stopped.get()) return;
        long started = System.nanoTime();
        String result = "failure";
        try {
            for (var state : active.values()) {
                var claim = state.operation();
                if (stopped.get()) return;
                if (!state.lostOwnership().get() && !history.renew(claim) && state.lostOwnership().compareAndSet(false,true)) stale(claim.id(),"renewal");
            }
            if (stopped.get()) return;
            history.maintain();
            if (stopped.get()) return;
            for (UUID id : history.due()) offer(id);
            maintenanceSuccessNanos = System.nanoTime(); maintenanceValid = true; result = "success";
        } catch (RuntimeException error) {
            maintenanceValid = false;
            meters.counter("clientbus.history.errors", "stage", "maintenance").increment();
            NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.FAILED,
                    NotificationDiagnostics.Code.DB_UNAVAILABLE,null,null,null);
        } finally {
            meters.timer("clientbus.history.maintenance.duration", "result", stopped.get()?"stopped":result)
                    .record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
        }
    }
    private void offerOutcome(String result) { meters.counter("clientbus.history.worker.offers","result",result).increment(); }
    private void stale(UUID id,String stage) {
        meters.counter("clientbus.history.worker.stale","stage",stage).increment();
        NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.STALE,
                NotificationDiagnostics.Code.LEASE_LOST,null,id,id);
    }
    private double maintenanceAgeSeconds() {
        long last = maintenanceSuccessNanos;
        return last == Long.MIN_VALUE ? Double.NaN : Math.max(0,(System.nanoTime()-last)/1_000_000_000.0);
    }
    @Override @PreDestroy public void close() {
        synchronized (lifecycle) {
            if (!stopped.compareAndSet(false,true)) return;
            for (var pending : executor.shutdownNow()) if (pending instanceof ClaimTask task) task.cancelBeforeStart();
        }
        NotificationDiagnostics.event(NotificationDiagnostics.Stage.HISTORY_WORKER,NotificationDiagnostics.Outcome.STOPPED,
                NotificationDiagnostics.Code.SHUTDOWN,null,null,null);
    }
}
