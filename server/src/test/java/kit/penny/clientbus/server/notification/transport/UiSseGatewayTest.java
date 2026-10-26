package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.security.policy.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class UiSseGatewayTest {
    UUID employee=UUID.randomUUID(),org=UUID.randomUUID(),workspace=UUID.randomUUID();
    AccessPolicy access=mock(AccessPolicy.class);
    NotificationRoutingPolicy routing=mock(NotificationRoutingPolicy.class);
    NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    UiSseGateway gateway;
    SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    static class Sink implements UiSseGateway.Sink {
        final List<UiEventEnvelope> events=new CopyOnWriteArrayList<>();
        volatile boolean closed;
        public void send(UiEventEnvelope event) throws Exception { events.add(event); }
        public void close() { closed=true; }
    }
    AccessSubject subject() { return AccessSubject.session(employee,Instant.now().plusSeconds(60)); }
    void create(int queue,int workers,int executorQueue,Duration writeTimeout) {
        var limits=new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.queue-capacity",queue)))
                .bindOrCreate("clientbus.notifications",NotificationProperties.class);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(UUID.class))).thenReturn(org);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(java.sql.Timestamp.class))).thenReturn(java.sql.Timestamp.from(Instant.now()));
        when(routing.forRecipient(any(),any())).thenAnswer(invocation -> {
            UiInvalidation e=invocation.getArgument(1);
            return Optional.of(new UiEventEnvelope(e.eventId(),1,e.type(),e.occurredAt(),null,e.resource(),null,null));
        });
        var config=new UiGatewayProperties("test",8,8,workers,executorQueue,writeTimeout,
                Duration.ofMillis(50),Duration.ofMillis(50),Duration.ofSeconds(10));
        gateway=new UiSseGateway(limits,config,access,routing,jdbc,JsonMapper.builder().build(),new UiTransportMetrics(metrics,config,limits));
    }
    UiInvalidation event(UUID selected,UiInvalidation.Audience audience) {
        return new UiInvalidation(UUID.randomUUID(),1,"inbox.invalidated",Instant.now(),org,selected,
                new UiResourceRef("conversation",UUID.randomUUID()),audience,Set.of(employee),EventOrigin.LIVE,null,null);
    }
    void until(java.util.concurrent.Callable<Boolean> condition) { await().atMost(Duration.ofSeconds(3)).until(condition); }
    void assertClosedReason(String reason) {
        assertEquals(1,metrics.get("clientbus.notification.sse.closed").tag("reason",reason).counter().count());
        assertEquals(1,metrics.find("clientbus.notification.sse.closed").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
    }
    @AfterEach void cleanup() { if(gateway!=null)gateway.shutdown(); }

    @Test void noReadyUntilPipelineAndReadyIsFirst() {
        create(8,1,8,Duration.ofSeconds(2)); Sink sink=new Sink();
        assertEquals(503,assertThrows(ResponseStatusException.class,()->gateway.register(subject(),workspace,sink)).getStatusCode().value());
        assertTrue(sink.events.isEmpty()); gateway.pipelineHealthy();
        gateway.register(subject(),workspace,sink); gateway.accept(event(workspace,UiInvalidation.Audience.CONVERSATION));
        until(()->sink.events.size()==2);
        assertEquals("stream.ready",sink.events.getFirst().type()); assertNotNull(sink.events.getFirst().stream().alertsAfter());
        assertNull(sink.events.getFirst().resource());
        assertEquals(1,metrics.get("clientbus.notification.sse.connections").gauge().value());
        assertEquals(1,metrics.get("clientbus.notification.sse.admissions").tag("result","opened").counter().count());
        until(()->metrics.find("clientbus.notification.sse.write.duration").tag("kind","invalidation").tag("result","success").timer()!=null
                && metrics.get("clientbus.notification.sse.write.duration").tag("kind","invalidation").tag("result","success").timer().count()==1);
        assertEquals(1,metrics.get("clientbus.notification.sse.invalidation.age").timer().count());
    }
    @Test void pipelineLossClosesEveryConnectionAndReconnectHasNewStreamId() {
        create(8,2,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink a=new Sink(),b=new Sink();
        gateway.register(subject(),workspace,a);gateway.register(subject(),workspace,b);
        until(()->a.events.size()==1 && b.events.size()==1);
        gateway.pipelineLost();until(()->a.closed && b.closed);assertEquals(0,gateway.connectionCount());
        assertThrows(ResponseStatusException.class,()->gateway.register(subject(),workspace,new Sink()));
        gateway.pipelineHealthy();Sink c=new Sink();gateway.register(subject(),workspace,c);until(()->c.events.size()==1);
        assertNotEquals(a.events.getFirst().stream().streamId(),c.events.getFirst().stream().streamId());
    }
    @Test void workspaceFilterDoesNotSuppressPersonalCrossWorkspaceEvents() {
        create(8,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink sink=new Sink();
        gateway.register(subject(),workspace,sink);until(()->sink.events.size()==1);
        gateway.accept(event(UUID.randomUUID(),UiInvalidation.Audience.CONVERSATION));
        gateway.accept(event(UUID.randomUUID(),UiInvalidation.Audience.PERSONAL));
        until(()->sink.events.size()==2);verify(routing,times(1)).forRecipient(any(),any());
    }
    @Test void currentAclFailureClosesBeforeSensitiveEventSend() {
        create(8,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink sink=new Sink();
        gateway.register(subject(),workspace,sink);until(()->sink.events.size()==1);
        doThrow(new AccessDeniedException("revoked")).when(access).require(any(),any(),any());
        gateway.accept(event(workspace,UiInvalidation.Audience.CONVERSATION));until(()->sink.closed);
        assertEquals(1,sink.events.size());assertEquals(0,gateway.connectionCount());
    }
    @Test void expiredSessionClosesWithoutWaitingForHeartbeat() {
        create(8,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink sink=new Sink();
        gateway.register(AccessSubject.session(employee,Instant.now().plusMillis(200)),workspace,sink);
        until(()->{gateway.tick();return sink.closed;}); assertEquals(0,gateway.connectionCount());
    }
    @Test void overflowIsBoundedAndNeverBlocksPublisherOnSocket() throws Exception {
        create(2,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink sink=new Sink(){ public void send(UiEventEnvelope e) throws Exception { if(e.type().equals("stream.ready")){entered.countDown();release.await(2,TimeUnit.SECONDS);}super.send(e); } };
        var connection=gateway.register(subject(),workspace,sink);assertTrue(entered.await(1,TimeUnit.SECONDS));
        assertTimeout(Duration.ofMillis(300),()->{ for(int i=0;i<20;i++)gateway.accept(event(workspace,UiInvalidation.Audience.CONVERSATION)); });
        synchronized(connection.pending){assertEquals(1,connection.pending.size());}
        assertEquals(1,metrics.get("clientbus.notification.sse.queue.entries").gauge().value());
        assertEquals(1,metrics.get("clientbus.notification.sse.workers.active").gauge().value());
        assertEquals(1,metrics.get("clientbus.notification.sse.resync").tag("reason","overflow").counter().count());
        release.countDown();until(()->sink.closed);
        assertEquals(List.of("stream.ready","stream.resync-required"),sink.events.stream().map(UiEventEnvelope::type).toList());
        assertClosedReason("overflow");
    }
    @Test void overflowAfterReadyKeepsOverflowCloseReason() throws Exception {
        create(2,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink sink=new Sink(){public void send(UiEventEnvelope e) throws Exception {
            super.send(e);
            if(e.type().equals("inbox.invalidated")){entered.countDown();release.await(2,TimeUnit.SECONDS);}
        }};
        gateway.register(subject(),workspace,sink);until(()->sink.events.size()==1);
        gateway.accept(event(workspace,UiInvalidation.Audience.CONVERSATION));assertTrue(entered.await(1,TimeUnit.SECONDS));
        for(int i=0;i<3;i++)gateway.accept(event(workspace,UiInvalidation.Audience.CONVERSATION));
        release.countDown();until(()->sink.closed);
        assertEquals(List.of("stream.ready","inbox.invalidated","stream.resync-required"),sink.events.stream().map(UiEventEnvelope::type).toList());
        assertClosedReason("overflow");
    }
    @Test void overflowBeforeReadyKeepsOverflowCloseReason() throws Exception {
        assertResyncBeforeReady(UiTransportMetrics.ResyncReason.OVERFLOW,"overflow");
    }
    @Test void malformedBeforeReadyKeepsMalformedCloseReason() throws Exception {
        assertResyncBeforeReady(UiTransportMetrics.ResyncReason.MALFORMED,"malformed");
    }
    void assertResyncBeforeReady(UiTransportMetrics.ResyncReason reason,String expected) throws Exception {
        create(4,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink blocked=new Sink(){public void send(UiEventEnvelope e) throws Exception {entered.countDown();release.await(2,TimeUnit.SECONDS);}};
        gateway.register(subject(),workspace,blocked);assertTrue(entered.await(1,TimeUnit.SECONDS));
        Sink sink=new Sink();var connection=gateway.register(subject(),workspace,sink);
        connection.resyncAndClose(reason);until(()->sink.closed);
        assertTrue(sink.events.isEmpty());assertClosedReason(expected);
        release.countDown();
    }
    @Test void writeTimeoutWinsOverQueuedResyncCloseReason() throws Exception {
        create(4,1,8,Duration.ofMillis(500));gateway.pipelineHealthy();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink sink=new Sink(){
            public void send(UiEventEnvelope e) throws Exception {entered.countDown();release.await(2,TimeUnit.SECONDS);super.send(e);}
            public void close(){super.close();release.countDown();}
        };
        gateway.register(subject(),workspace,sink);assertTrue(entered.await(1,TimeUnit.SECONDS));
        gateway.malformedEvent();
        assertEquals(1,metrics.get("clientbus.notification.sse.resync").tag("reason","malformed").counter().count());
        until(()->sink.closed);
        assertClosedReason("write_timeout");
    }
    @Test void writeErrorWinsOverQueuedResyncCloseReason() throws Exception {
        create(4,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink sink=new Sink(){public void send(UiEventEnvelope e) throws Exception {
            entered.countDown();release.await(2,TimeUnit.SECONDS);throw new java.io.IOException("Disconnected");
        }};
        gateway.register(subject(),workspace,sink);assertTrue(entered.await(1,TimeUnit.SECONDS));
        gateway.malformedEvent();release.countDown();until(()->sink.closed);
        assertClosedReason("write_error");
    }
    @Test void blockedWriterDeadlineReleasesRegistryWithoutBlockingTimer() throws Exception {
        create(4,1,8,Duration.ofMillis(100));gateway.pipelineHealthy();var release=new CountDownLatch(1);
        Sink sink=new Sink(){public void send(UiEventEnvelope e) throws Exception{release.await(2,TimeUnit.SECONDS);}public void close(){super.close();release.countDown();}};
        gateway.register(subject(),workspace,sink);until(()->sink.closed);assertEquals(0,gateway.connectionCount());
        assertEquals(1,metrics.get("clientbus.notification.sse.closed").tag("reason","write_timeout").counter().count());
    }
    @Test void executorSaturationClosesInsteadOfGrowingQueue() throws Exception {
        create(4,1,1,Duration.ofSeconds(2));gateway.pipelineHealthy();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Sink blocked=new Sink(){public void send(UiEventEnvelope e)throws Exception{entered.countDown();release.await(2,TimeUnit.SECONDS);}public void close(){super.close();release.countDown();}};
        gateway.register(subject(),workspace,blocked);assertTrue(entered.await(1,TimeUnit.SECONDS));
        gateway.register(subject(),workspace,new Sink());Sink rejected=new Sink();gateway.register(subject(),workspace,rejected);
        until(()->rejected.closed);assertTrue(gateway.connectionCount()<=2);release.countDown();
    }
    @Test void connectionLimitRejectsBeforeAllocatingAnotherSession() {
        create(4,2,16,Duration.ofSeconds(2));gateway.pipelineHealthy();
        for(int i=0;i<8;i++) gateway.register(subject(),workspace,new Sink());
        assertEquals(429,assertThrows(ResponseStatusException.class,()->gateway.register(subject(),workspace,new Sink())).getStatusCode().value());
        assertEquals(8,gateway.connectionCount());
        assertEquals(1,metrics.get("clientbus.notification.sse.admissions").tag("result","limit").counter().count());
    }
    @Test void malformedKafkaRecordTriggersMinimalRecoveryInsteadOfPoisonLoop() {
        create(4,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink sink=new Sink();
        gateway.register(subject(),workspace,sink);until(()->sink.events.size()==1);
        var config=new UiGatewayProperties("test-subscriber",8,4,1,8,Duration.ofSeconds(2),Duration.ofMillis(50),Duration.ofMillis(50),Duration.ofSeconds(10));
        var props=new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("clientbus.notifications",NotificationProperties.class);
        var subscriber=new UiKafkaSubscriber(gateway,config,props,JsonMapper.builder().build(),new org.springframework.boot.kafka.autoconfigure.KafkaProperties());
        subscriber.receive("{bad-json");until(()->sink.closed);
        assertEquals("stream.resync-required",sink.events.getLast().type());
        assertClosedReason("malformed");
    }
    @Test void shutdownClosesSessionsAndCannotBeReopenedByALateBrokerProbe() {
        create(4,1,8,Duration.ofSeconds(2));gateway.pipelineHealthy();Sink sink=new Sink();
        gateway.register(subject(),workspace,sink);until(()->sink.events.size()==1);
        gateway.shutdown();until(()->sink.closed);
        gateway.pipelineHealthy();assertFalse(gateway.ready());
        assertEquals(0,metrics.get("clientbus.notification.sse.connections").gauge().value());
        assertEquals(0,metrics.get("clientbus.notification.sse.queue.entries").gauge().value());
        assertEquals(1,metrics.get("clientbus.notification.sse.closed").tag("reason","shutdown").counter().count());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->gateway.register(subject(),workspace,new Sink())).getStatusCode().value());
    }
}
