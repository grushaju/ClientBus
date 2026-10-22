package kit.penny.clientbus.server.notification.transport;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.*;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.jwt.JwtService;
import kit.penny.clientbus.server.security.policy.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "clientbus.notifications.sse-enabled=true","clientbus.notifications.heartbeat-seconds=1",
        "clientbus.notifications.gateway.probe-interval=PT0.5S","clientbus.notifications.gateway.broker-timeout=PT1S",
        "clientbus.notifications.gateway.pipeline-stale-after=PT3S"})
class UiTransportIntegrationTest extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired UiSseGateway gateway;
    @Autowired UiGatewayDatabase gatewayDatabase;
    @Autowired UiKafkaSubscriber subscriber;
    @Autowired AccessPolicy policy;
    @Autowired NotificationRoutingPolicy routing;
    @Autowired NotificationProperties limits;
    @Autowired UiGatewayProperties gatewayConfig;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired JsonMapper mapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @org.springframework.beans.factory.annotation.Value("${clientbus.jwt.secret}") String jwtSecret;
    UUID org,workspace,employee,conversation,foreignWorkspace;String token,expiredToken;
    private <T> T save(T value){em.persist(value);return value;}
    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status->{
            var organization=save(TestDataFactory.organization());var w=save(TestDataFactory.workspace(organization));
            var foreign=save(TestDataFactory.workspace(save(TestDataFactory.organization())));
            String key=UUID.randomUUID().toString();var user=save(new UserEntity(key,key+"@test.local","unused",UserRole.EMPLOYEE));
            var e=save(new EmployeeEntity(organization,user,"Test","Stream",null));save(new EmployeeWorkspaceEntity(e,w));
            var channel=save(TestDataFactory.channelAccount(save(TestDataFactory.channel(w))));
            var c=save(TestDataFactory.conversation(w,channel,save(TestDataFactory.clientAccount(save(TestDataFactory.client(organization))))));c.setAssignedEmployee(e);
            em.flush();org=organization.getId();workspace=w.getId();employee=e.getId();conversation=c.getId();foreignWorkspace=foreign.getId();token=jwt.generateToken(user);
            expiredToken=new JwtService(jwtSecret,-1000).generateToken(user);
        });
        await().atMost(Duration.ofSeconds(30)).until(gateway::ready);
    }
    @Test void stalledAclQueriesTimeOutAndWorkersRecover() throws Exception {
        int workers=((com.zaxxer.hikari.HikariDataSource)gatewayDatabase.jdbc.getJdbcTemplate().getDataSource()).getMaximumPoolSize();
        try(var executor=Executors.newFixedThreadPool(workers)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<workers;i++) tasks.add(executor.submit(()->assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    ()->gatewayDatabase.jdbc.queryForObject("select 1 from pg_sleep(10)",Map.of(),Integer.class))));
            for(var task:tasks) task.get(7,TimeUnit.SECONDS);
            assertEquals(1,gatewayDatabase.jdbc.queryForObject("select 1",Map.of(),Integer.class));
            assertTrue(gatewayDatabase.access.allows(AccessSubject.recipient(employee),AccessPolicy.Resource.WORKSPACE,workspace));
        }
    }
    @Test void exhaustedAclPoolHasBoundedAcquisitionAndDoesNotBlockBusinessPool() throws Exception {
        var pool=(com.zaxxer.hikari.HikariDataSource)gatewayDatabase.jdbc.getJdbcTemplate().getDataSource();
        var held=new ArrayList<java.sql.Connection>();
        try {
            for(int i=0;i<pool.getMaximumPoolSize();i++) held.add(pool.getConnection());
            assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    ()->gatewayDatabase.jdbc.queryForObject("select 1",Map.of(),Integer.class)));
            assertEquals(1,jdbc.queryForObject("select 1",Integer.class));
        } finally { for(var connection:held) connection.close(); }
        assertEquals(1,gatewayDatabase.jdbc.queryForObject("select 1",Map.of(),Integer.class));
    }
    private HttpRequest request(UUID w,String auth) {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/events?workspaceId="+w))
                .timeout(Duration.ofSeconds(15)).header("Accept","text/event-stream");
        if(auth!=null)builder.header("Authorization","Bearer "+auth);
        return builder.build();
    }
    private UiInvalidation event() {
        return new UiInvalidation(UUID.randomUUID(),1,"messages.invalidated",Instant.now(),org,workspace,
                new UiResourceRef("conversation",conversation),UiInvalidation.Audience.CONVERSATION,Set.of(),EventOrigin.LIVE,null,null);
    }
    private void publish(UiInvalidation event) throws Exception {
        var props=new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        try(var producer=new KafkaProducer<String,String>(props)) {
            producer.send(new ProducerRecord<>(UiKafkaSubscriber.TOPIC,org.toString(),mapper.writeValueAsString(event))).get(5,TimeUnit.SECONDS);
        }
    }
    private UiEventEnvelope next(BufferedReader reader) throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(()->{
                String line;
                while((line=reader.readLine())!=null) if(line.startsWith("data:"))
                    return mapper.readValue(line.substring(5).stripLeading(),UiEventEnvelope.class);
                return null;
            });
            try{return future.get(8,TimeUnit.SECONDS);}catch(TimeoutException e){reader.close();throw e;}
        }
    }
    @Test void realHttpAndKafkaFanoutToTwoGatewayGroups() throws Exception {
        var config=new UiGatewayProperties("second-"+UUID.randomUUID(),16,4,2,16,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofSeconds(3));
        var second=new UiSseGateway(limits,config,gatewayDatabase,mapper);
        var secondSubscriber=new UiKafkaSubscriber(second,config,limits,mapper,kafkaProperties);
        try(var http=HttpClient.newHttpClient()) {
            secondSubscriber.start();await().atMost(Duration.ofSeconds(30)).until(second::ready);
            assertNotEquals(subscriber.groupId(),secondSubscriber.groupId());
            var captured=new CopyOnWriteArrayList<UiEventEnvelope>();
            second.register(AccessSubject.session(employee,Instant.now().plusSeconds(60)),workspace,new UiSseGateway.Sink(){
                public void send(UiEventEnvelope e){captured.add(e);}public void close(){}
            });
            var response=http.send(request(workspace,token),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("no",response.headers().firstValue("X-Accel-Buffering").orElseThrow());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                var ready=next(reader);assertEquals("stream.ready",ready.type());assertNotNull(ready.stream().alertsAfter());
                var event=event();publish(event);
                UiEventEnvelope result;do{result=next(reader);}while(result!=null && result.type().equals("stream.heartbeat"));
                assertNotNull(result);assertEquals(event.eventId(),result.eventId());assertEquals(conversation,result.resource().id());
                await().atMost(Duration.ofSeconds(5)).until(()->captured.stream().anyMatch(e->e.eventId().equals(event.eventId())));
                jdbc.update("delete from dbo.employeeworkspace where employeeid=?",employee);
                // Heartbeat also revalidates current access, so no further business event is needed.
                await().atMost(Duration.ofSeconds(5)).until(()->gateway.connectionCount()==0 && second.connectionCount()==0);
            }
            assertEquals(401,http.send(request(workspace,null),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(401,http.send(request(workspace,"invalid"),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403,http.send(request(foreignWorkspace,token),HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {secondSubscriber.stop(()->{});second.shutdown();}
    }
    @Test void subscriberStopClosesHttpStreamAndRejectsNewAdmissionUntilRecovery() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var response=http.send(request(workspace,token),HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                assertEquals("stream.ready",next(reader).type());
                subscriber.stop(()->{});assertFalse(gateway.ready());
                await().atMost(Duration.ofSeconds(5)).until(()->gateway.connectionCount()==0);
                assertEquals(503,http.send(request(workspace,token),HttpResponse.BodyHandlers.discarding()).statusCode());
            } finally {subscriber.start();await().atMost(Duration.ofSeconds(30)).until(gateway::ready);}
        }
    }
    @Test void brokerUnavailabilityCannotBeMaskedBySseHeartbeats() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var response=http.send(request(workspace,token),HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                assertEquals("stream.ready",next(reader).type());
                kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
                try {
                    await().atMost(Duration.ofSeconds(10)).until(()->!gateway.ready() && gateway.connectionCount()==0);
                    assertEquals(503,http.send(request(workspace,token),HttpResponse.BodyHandlers.discarding()).statusCode());
                } finally {
                    kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
                    await().atMost(Duration.ofSeconds(45)).until(gateway::ready);
                }
            }
        }
    }
    @Test void authenticationAndAdmissionErrorsUseJsonContractDespiteSseAcceptHeader() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            for(String credential:Arrays.asList(null,"invalid",expiredToken)) {
                var response=http.send(request(workspace,credential),HttpResponse.BodyHandlers.ofString());
                assertEquals(401,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                var error=mapper.readValue(response.body(),NotificationApiErrorDto.class);
                assertEquals("UNAUTHENTICATED",error.code());assertNotNull(error.correlationId());
            }
            var forbidden=http.send(request(foreignWorkspace,token),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,forbidden.statusCode());assertEquals("FORBIDDEN",mapper.readValue(forbidden.body(),NotificationApiErrorDto.class).code());
            var bad=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/events?workspaceId=bad"))
                    .header("Accept","text/event-stream").header("Authorization","Bearer "+token).build();
            var invalid=http.send(bad,HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode());assertEquals("INVALID_REQUEST",mapper.readValue(invalid.body(),NotificationApiErrorDto.class).code());
        }
    }

    @Test void stoppingOneGatewayCleansOnlyItsGroupAndRestartRestoresFreshFanout() throws Exception {
        var config=new UiGatewayProperties("survivor-"+UUID.randomUUID(),16,4,2,16,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofSeconds(3));
        var second=new UiSseGateway(limits,config,gatewayDatabase,mapper);
        var other=new UiKafkaSubscriber(second,config,limits,mapper,kafkaProperties);
        var captured=new CopyOnWriteArrayList<UiEventEnvelope>();
        try(var admin=Admin.create(kafkaProperties.buildAdminProperties());var http=HttpClient.newHttpClient()) {
            other.start();await().atMost(Duration.ofSeconds(30)).until(second::ready);
            second.register(AccessSubject.session(employee,Instant.now().plusSeconds(90)),workspace,new UiSseGateway.Sink(){
                public void send(UiEventEnvelope event){captured.add(event);}public void close(){}
            });
            long cleaned=subscriber.cleanedGroups();
            subscriber.stop(()->{});
            assertEquals(cleaned+1,subscriber.cleanedGroups());
            assertTrue(admin.describeConsumerGroups(List.of(other.groupId())).all().get(5,TimeUnit.SECONDS)
                    .get(other.groupId()).members().size()>0);
            var during=event();publish(during);
            await().atMost(Duration.ofSeconds(5)).until(()->captured.stream().anyMatch(value->value.eventId().equals(during.eventId())));
            subscriber.start();await().atMost(Duration.ofSeconds(30)).until(gateway::ready);
            var response=http.send(request(workspace,token),HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                assertEquals("stream.ready",next(reader).type());
                var after=event();publish(after);
                UiEventEnvelope result;do{result=next(reader);}while(result!=null && result.type().equals("stream.heartbeat"));
                assertNotNull(result);assertEquals(after.eventId(),result.eventId());
                await().atMost(Duration.ofSeconds(5)).until(()->captured.stream().anyMatch(value->value.eventId().equals(after.eventId())));
            }
        } finally {other.stop(()->{});second.shutdown();if(!subscriber.isRunning())subscriber.start();await().atMost(Duration.ofSeconds(30)).until(gateway::ready);}
    }

    @Test void collidingInstanceIdsFailClosedDuringRebalanceAndRecoverAfterTheCollisionLeaves() {
        var second=new UiSseGateway(limits,gatewayConfig,gatewayDatabase,mapper);
        var collision=new UiKafkaSubscriber(second,gatewayConfig,limits,mapper,kafkaProperties);
        try {
            collision.start();
            await().atMost(Duration.ofSeconds(30)).until(()->!gateway.ready() && !second.ready());
            assertEquals(0,gateway.connectionCount());assertEquals(0,second.connectionCount());
        } finally {collision.stop(()->{});second.shutdown();await().atMost(Duration.ofSeconds(30)).until(gateway::ready);}
    }

    @Test void brokerProcessRestartRestoresDeliveryAfterReadinessWasLost() throws Exception {
        try {
            kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).withTimeout(2).exec();
            await().atMost(Duration.ofSeconds(10)).until(()->!gateway.ready() && gateway.connectionCount()==0);
        } finally {kafka.getDockerClient().startContainerCmd(kafka.getContainerId()).exec();}
        await().atMost(Duration.ofSeconds(60)).until(gateway::ready);
        try(var http=HttpClient.newHttpClient()) {
            var response=http.send(request(workspace,token),HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                assertEquals("stream.ready",next(reader).type());var after=event();publish(after);
                UiEventEnvelope result;do{result=next(reader);}while(result!=null && result.type().equals("stream.heartbeat"));
                assertNotNull(result);assertEquals(after.eventId(),result.eventId());
            }
        }
    }
}
