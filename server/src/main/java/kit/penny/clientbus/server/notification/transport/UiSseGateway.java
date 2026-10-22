package kit.penny.clientbus.server.notification.transport;

import jakarta.annotation.PreDestroy;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.security.policy.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.*;

@Service
@ConditionalOnProperty(prefix="clientbus.notifications", name="sse-enabled", havingValue="true")
public class UiSseGateway {
    public interface Sink {
        void send(UiEventEnvelope event) throws Exception;
        void close();
    }
    private record Pending(UiInvalidation event, UiEventEnvelope control, boolean finish) {}
    private final NotificationProperties limits;
    private final UiGatewayProperties config;
    private final AccessPolicy access;
    private final NotificationRoutingPolicy routing;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final ConcurrentMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writers;
    private final ThreadPoolExecutor closers;
    private final ScheduledExecutorService timer;
    private volatile long lastProbeNanos;
    private volatile boolean pipeline;
    private volatile boolean stopped;
    private volatile long epoch;

    @org.springframework.beans.factory.annotation.Autowired
    public UiSseGateway(NotificationProperties limits, UiGatewayProperties config, UiGatewayDatabase database, JsonMapper mapper) {
        this(limits,config,database.access,database.routing,database.jdbc,mapper);
    }

    UiSseGateway(NotificationProperties limits, UiGatewayProperties config, AccessPolicy access,
                        NotificationRoutingPolicy routing, NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.limits=limits; this.config=config; this.access=access; this.routing=routing; this.jdbc=jdbc; this.mapper=mapper;
        writers = new ThreadPoolExecutor(config.workers(), config.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.executorQueueCapacity()), factory("ui-sse-writer"), new ThreadPoolExecutor.AbortPolicy());
        closers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(config.maxConnections()),
                factory("ui-sse-close"),new ThreadPoolExecutor.AbortPolicy());
        var scheduler = new ScheduledThreadPoolExecutor(1, factory("ui-sse-timer"));
        scheduler.setRemoveOnCancelPolicy(true); timer=scheduler;
        timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }
    private static ThreadFactory factory(String name) {
        return action -> { var thread=new Thread(action,name); thread.setDaemon(true); return thread; };
    }
    public boolean ready() { return !stopped && pipeline && System.nanoTime()-lastProbeNanos < config.pipelineStaleAfter().toNanos(); }
    public int connectionCount() { return connections.size(); }
    public synchronized void pipelineHealthy() { if (!stopped) { lastProbeNanos=System.nanoTime(); pipeline=true; } }
    public void pipelineLost() {
        synchronized (this) { pipeline=false; epoch++; }
        connections.values().forEach(Connection::close);
    }

    public SseEmitter open(AccessSubject subject, UUID workspace, jakarta.servlet.http.HttpServletResponse response) {
        if (subject.expiresAt()==null || subject.expired(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long timeout=Math.max(1, Math.min(Duration.ofMinutes(30).toMillis(), Duration.between(Instant.now(),subject.expiresAt()).toMillis()));
        var emitter=new SseEmitter(timeout);
        var connection=register(subject,workspace,new Sink() {
            private boolean first=true;
            public void send(UiEventEnvelope event) throws Exception {
                // No id: no replay/Last-Event-ID contract.
                emitter.send(SseEmitter.event().name(event.type()).data(mapper.writeValueAsString(event)));
                // SseEmitter buffers sends before MVC attaches its handler. Bound that buffer to ready only.
                if (first) {
                    first=false;
                    long deadline=System.nanoTime()+config.writeTimeout().toNanos();
                    while (!response.isCommitted()) {
                        if (System.nanoTime()>=deadline) throw new java.io.IOException("Stream initialization timed out");
                        Thread.sleep(5);
                    }
                }
            }
            public void close() { emitter.complete(); }
        });
        emitter.onCompletion(connection::close); emitter.onTimeout(connection::close); emitter.onError(e -> connection.close());
        return emitter;
    }

    /** Sink seam also permits deterministic slow-client tests without network timing. */
    Connection register(AccessSubject subject, UUID workspace, Sink sink) {
        if (!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UI pipeline unavailable");
        if (subject.expiresAt()==null || subject.expired(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        UUID organization=jdbc.queryForObject("select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid where e.employeeid=:employee and u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')",
                Map.of("employee",subject.employeeId()),UUID.class);
        access.require(subject,ORGANIZATION,organization);
        if (workspace!=null) access.require(subject,WORKSPACE,workspace);
        Instant alertsAfter=jdbc.queryForObject("select clock_timestamp()",Map.of(), java.sql.Timestamp.class).toInstant();
        Connection connection;
        synchronized (this) {
            if (!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            if (closers.getQueue().remainingCapacity()==0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stream cleanup busy");
            if (connections.size()>=config.maxConnections() || connections.values().stream()
                    .filter(c -> c.subject.employeeId().equals(subject.employeeId())).count()>=config.maxConnectionsPerEmployee())
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Stream limit reached");
            connection=new Connection(subject,organization,workspace,sink,epoch);
            connection.pending.put("ready",new Pending(null,new UiEventEnvelope(UUID.randomUUID(),1,"stream.ready",Instant.now(),null,null,null,
                    new StreamReadyDto(connection.id,alertsAfter,limits.heartbeatSeconds())),false));
            connections.put(connection.id,connection);
        }
        connection.schedule(); return connection;
    }

    /** Kafka thread only queues bounded references. No policy query or socket I/O here. */
    public void accept(UiInvalidation event) {
        if (!ready()) return;
        connections.values().forEach(c -> c.offer(event));
    }
    public void malformedEvent() { connections.values().forEach(Connection::resyncAndClose); }

    void tick() {
        try {
            if (!ready()) { pipelineLost(); return; }
            Instant now=Instant.now();
            connections.values().forEach(c -> {
                if (c.subject.expired(now)) c.close();
                else if (Duration.between(c.lastHeartbeat,now).getSeconds()>=limits.heartbeatSeconds()) {
                    c.lastHeartbeat=now; c.control("heartbeat","stream.heartbeat",false);
                }
            });
        } catch (RuntimeException ignored) { pipelineLost(); }
    }

    final class Connection {
        final UUID id=UUID.randomUUID(); final AccessSubject subject; final UUID organization,workspace; final Sink sink; final long generation;
        final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
        final AtomicBoolean running=new AtomicBoolean();
        volatile boolean closed,finishing; volatile Instant lastHeartbeat=Instant.now();
        Connection(AccessSubject subject,UUID organization,UUID workspace,Sink sink,long generation) {
            this.subject=subject;this.organization=organization;this.workspace=workspace;this.sink=sink;this.generation=generation;
        }
        void offer(UiInvalidation event) {
            if (!organization.equals(event.organizationId())) return;
            boolean personal=event.audience()==UiInvalidation.Audience.PERSONAL || event.audience()==UiInvalidation.Audience.FORMER_SCOPE;
            if (personal && !event.recipientEmployeeIds().contains(subject.employeeId())) return;
            if (!personal && event.audience()!=UiInvalidation.Audience.ORGANIZATION
                    && (workspace==null || !workspace.equals(event.workspaceId()))) return;
            String key=event.type()+":"+event.audience()+":"+event.workspaceId()+":"+event.resource();
            synchronized (pending) {
                if (closed || finishing) return;
                if (!pending.containsKey(key) && pending.size()>=limits.queueCapacity()) { resyncAndClose(); return; }
                pending.put(key,new Pending(event,null,false));
            }
            schedule();
        }
        void control(String key,String type,boolean finish) {
            synchronized (pending) {
                if (closed || finishing) return;
                // Heartbeats must never evict ready/business signals.
                if (pending.size()>=limits.queueCapacity()) { resyncAndClose(); return; }
                pending.put(key,new Pending(null,new UiEventEnvelope(UUID.randomUUID(),1,type,Instant.now(),null,null,null,null),finish));
            }
            schedule();
        }
        void resyncAndClose() {
            synchronized (pending) {
                if (closed || finishing) return;
                // If ready has not been sent, reconnect itself causes the reconciliation.
                if (pending.containsKey("ready")) { close(); return; }
                pending.clear(); finishing=true;
                pending.put("resync",new Pending(null,new UiEventEnvelope(UUID.randomUUID(),1,"stream.resync-required",Instant.now(),null,null,null,null),true));
            }
            schedule();
        }
        void schedule() {
            if (!closed && running.compareAndSet(false,true)) {
                try { writers.execute(this::drain); } catch (RejectedExecutionException e) { running.set(false); close(); }
            }
        }
        void drain() {
            try {
                for (int count=0;count<16 && !closed;count++) {
                    Pending next;
                    synchronized (pending) {
                        var iterator=pending.entrySet().iterator(); if (!iterator.hasNext()) break;
                        next=iterator.next().getValue(); iterator.remove();
                    }
                    if (!ready() || generation!=epoch || subject.expired(Instant.now())) { close(); break; }
                    access.require(subject,ORGANIZATION,organization);
                    if (workspace!=null) access.require(subject,WORKSPACE,workspace);
                    UiEventEnvelope output=next.control();
                    if (next.event()!=null) output=routing.forRecipient(subject,next.event()).orElse(null);
                    if (output!=null) {
                        if (mapper.writeValueAsBytes(output).length>limits.maxEventBytes()) { close(); break; }
                        var deadline=timer.schedule(this::close,config.writeTimeout().toMillis(),TimeUnit.MILLISECONDS);
                        try { if (!closed && ready() && !subject.expired(Instant.now())) sink.send(output); }
                        finally { deadline.cancel(false); }
                    }
                    if (next.finish()) { close(); break; }
                }
            } catch (Exception e) { close(); }
            finally {
                running.set(false);
                synchronized (pending) { if (!pending.isEmpty() && !closed) schedule(); }
            }
        }
        void close() {
            synchronized (pending) { if (closed) return; closed=true; pending.clear(); }
            connections.remove(id,this);
            try { closers.execute(() -> { try { sink.close(); } catch (RuntimeException ignored) { } }); }
            catch (RejectedExecutionException ignored) { /* Servlet async timeout is the final cleanup boundary. */ }
        }
    }

    @PreDestroy public void shutdown() {
        stopped=true; pipelineLost(); timer.shutdownNow(); writers.shutdownNow(); closers.shutdown();
    }
}
