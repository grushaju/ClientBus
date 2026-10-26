package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.CreatePlatformMessageRequest;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import kit.penny.clientbus.server.notification.history.HistorySyncConfiguration;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"clientbus.notifications.sse-enabled=true","clientbus.notifications.outbox-poll-ms=3600000"})
class UiOutboxIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired MessageService messages;
    @Autowired UiOutboxStore store;
    @Autowired UiOutboxRelay relay;
    UUID conversation;
    private <T> T save(T entity){em.persist(entity);return entity;}
    @BeforeEach void fixtures() {
        jdbc.update("delete from dbo.notificationoutbox");
        tx.executeWithoutResult(status->{
            var org=save(TestDataFactory.organization());var w=save(TestDataFactory.workspace(org));
            var channel=save(TestDataFactory.channelAccount(save(TestDataFactory.channel(w))));
            var client=save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            conversation=save(TestDataFactory.conversation(w,channel,client)).getId();
        });
    }
    private void create(String id,EventOrigin origin) {
        messages.createPlatformMessage(new CreatePlatformMessageRequest(conversation,MessageType.TEXT,id,"Outbox test",null,Instant.now(),false),origin);
    }
    @Test void rollbackLeavesNeitherMessageNorOutboxAndDuplicateDoesNotPublishAgain() {
        tx.executeWithoutResult(status->{create("rollback",EventOrigin.LIVE);em.flush();status.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.message where conversationid=?",Integer.class,conversation));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notificationoutbox",Integer.class));
        tx.executeWithoutResult(status->create("live",EventOrigin.LIVE));
        var ids=jdbc.queryForList("select eventid from dbo.notificationoutbox order by eventid",UUID.class);
        tx.executeWithoutResult(status->create("live",EventOrigin.LIVE));
        assertEquals(ids,jdbc.queryForList("select eventid from dbo.notificationoutbox order by eventid",UUID.class));
        assertEquals(2,ids.size());
        relay.tick();
        assertEquals(2,jdbc.queryForObject("select count(*) from dbo.notificationoutbox where status='PUBLISHED' and publishedat is not null",Integer.class));
    }
    @Test void staleClaimCannotCompleteTakeoverAndManualRetryPreservesEventIdentity() {
        tx.executeWithoutResult(status->create("claim",EventOrigin.LIVE));
        var first=store.claim().orElseThrow();
        jdbc.update("delete from dbo.notificationoutbox where id<>?",first.id());
        jdbc.update("update dbo.notificationoutbox set leaseuntil=clock_timestamp()-interval '1 second' where id=?",first.id());
        var other=store.claim().orElseThrow();
        assertEquals(first.id(),other.id());assertTrue(other.version()>first.version());
        assertFalse(store.published(first));assertFalse(store.failed(first,true,"STALE"));
        assertTrue(store.failed(other,true,"INVALID_PAYLOAD"));
        assertTrue(store.retryQuarantined(other.id()));
        var retry=store.claim().orElseThrow();
        assertEquals(first.eventId(),retry.eventId());assertEquals(first.payload(),retry.payload());
        assertTrue(store.published(retry));assertFalse(store.retryQuarantined(retry.id()));
    }
    @Test void poisonPayloadIsQuarantinedWithoutBlockingNextRecord() {
        tx.executeWithoutResult(status->create("poison",EventOrigin.LIVE));
        UUID id=jdbc.queryForObject("select id from dbo.notificationoutbox order by createdat limit 1",UUID.class);
        jdbc.update("update dbo.notificationoutbox set payload='{}'::jsonb where id=?",id);
        relay.tick();
        assertEquals("QUARANTINED",jdbc.queryForObject("select status from dbo.notificationoutbox where id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notificationoutbox where status='PUBLISHED'",Integer.class));
        assertEquals(1,((Number)store.statistics().get("quarantined")).intValue());
    }
    @Test void concurrentWorkersClaimDifferentRows() throws Exception {
        tx.executeWithoutResult(status->create("concurrent",EventOrigin.LIVE));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(store::claim);var b=executor.submit(store::claim);
            assertNotEquals(a.get(3,TimeUnit.SECONDS).orElseThrow().id(),b.get(3,TimeUnit.SECONDS).orElseThrow().id());
        }
    }
    @Test void persistedOriginDistinguishesLiveHistoryAndSyncWithoutActivatingCenter() {
        for(var origin:EventOrigin.values()) tx.executeWithoutResult(status->create(origin.name(),origin));
        for(var origin:EventOrigin.values()) assertEquals(2,jdbc.queryForObject(
                "select count(*) from dbo.notificationoutbox where payload->>'origin'=?",Integer.class,origin.name()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notification",Integer.class));
    }
    @Test void statisticsExcludeLargePublishedArchiveAndKeepPipelineBacklogsAndQuarantineSeparate() throws Exception {
        int archiveRows=20_000;
        jdbc.update("""
            insert into dbo.notificationoutbox
              (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,
               nextattemptat,createdat,publishedat)
            select gen_random_uuid(),gen_random_uuid(),cast(? as uuid),case when row_number % 2=0 then ? else ? end,
              'STATISTICS_FIXTURE',1,'{}'::jsonb,'PUBLISHED',1,1,clock_timestamp()-interval '1 day',
              clock_timestamp()-interval '1 day',clock_timestamp()-interval '1 day'
            from generate_series(1,?) as archive(row_number)
            """,UUID.randomUUID(),UiKafkaSubscriber.TOPIC,HistorySyncConfiguration.TOPIC,archiveRows);
        Instant baseline=databaseNow();
        Instant uiOldest=baseline.minusSeconds(600),historyOldest=baseline.minusSeconds(1800);
        insertStatisticsRow(UiKafkaSubscriber.TOPIC,"PENDING",uiOldest);
        insertStatisticsRow(UiKafkaSubscriber.TOPIC,"RETRY",baseline.minusSeconds(300));
        insertStatisticsRow(UiKafkaSubscriber.TOPIC,"IN_FLIGHT",baseline.minusSeconds(60));
        insertStatisticsRow(HistorySyncConfiguration.TOPIC,"PENDING",historyOldest);
        insertStatisticsRow(HistorySyncConfiguration.TOPIC,"RETRY",baseline.minusSeconds(900));
        // Older quarantined rows must not inflate oldest pending age in either pipeline.
        insertStatisticsRow(UiKafkaSubscriber.TOPIC,"QUARANTINED",baseline.minusSeconds(172800));
        insertStatisticsRow(HistorySyncConfiguration.TOPIC,"QUARANTINED",baseline.minusSeconds(172800));
        assertEquals(archiveRows+7,jdbc.queryForObject("select count(*) from dbo.notificationoutbox",Integer.class));

        Instant before=databaseNow();var stats=store.statistics();Instant after=databaseNow();
        assertCount(stats,"pending",5);assertCount(stats,"quarantined",2);
        assertCount(stats,"uipending",3);assertCount(stats,"uiquarantined",1);
        assertCount(stats,"historypending",2);assertCount(stats,"historyquarantined",1);
        assertAgeBetween(stats,"oldest_seconds",historyOldest,before,after);
        assertAgeBetween(stats,"uioldest",uiOldest,before,after);
        assertAgeBetween(stats,"historyoldest",historyOldest,before,after);

        // Record the real planner decision on the production SQL. There is no forced index or timing assertion.
        jdbc.execute("analyze dbo.notificationoutbox");
        var plan=namedJdbc.queryForList("explain (analyze,buffers,format text) "+UiOutboxStore.STATISTICS_SQL,
                UiOutboxStore.statisticsParameters(),String.class);
        var artifact=Path.of(System.getProperty("basedir","."),"target","notification-11-11-outbox-statistics-plan.txt");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact,"PostgreSQL outbox statistics: "+archiveRows+" published archive rows; 5 active + 2 quarantined rows.\n"
                +"Production query, default planner; ANALYZE refreshed table statistics.\n\n"
                +String.join("\n",plan)+"\n",StandardCharsets.UTF_8);

        jdbc.update("delete from dbo.notificationoutbox where status in ('PENDING','RETRY','IN_FLIGHT')");
        var idle=store.statistics();
        for(String key:List.of("pending","uipending","historypending"))
            assertCount(idle,key,0);
        for(String key:List.of("oldest_seconds","uioldest","historyoldest"))
            assertEquals(0d,((Number)idle.get(key)).doubleValue(),key);
        assertCount(idle,"quarantined",2);assertCount(idle,"uiquarantined",1);assertCount(idle,"historyquarantined",1);
        assertEquals(archiveRows,jdbc.queryForObject("select count(*) from dbo.notificationoutbox where status='PUBLISHED'",Integer.class));
    }
    private void insertStatisticsRow(String topic,String status,Instant created) {
        boolean history=HistorySyncConfiguration.TOPIC.equals(topic);
        jdbc.update("""
            insert into dbo.notificationoutbox
              (id,eventid,organizationid,destination,eventtype,schemaversion,payload,status,attempts,claimversion,
               nextattemptat,createdat,leaseuntil,operationid)
            values (?,?,?,?,'STATISTICS_FIXTURE',1,'{}'::jsonb,?,1,1,clock_timestamp()+interval '1 day',?,?,?)
            """,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),topic,status,Timestamp.from(created),
                "IN_FLIGHT".equals(status)?Timestamp.from(created.plusSeconds(172800)):null,history?UUID.randomUUID():null);
    }
    private Instant databaseNow() {return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
    private void assertCount(Map<String,Object> statistics,String key,long expected) {
        assertEquals(expected,((Number)statistics.get(key)).longValue(),key);
    }
    private void assertAgeBetween(Map<String,Object> statistics,String key,Instant created,Instant before,Instant after) {
        double value=((Number)statistics.get(key)).doubleValue();
        double minimum=Duration.between(created,before).toNanos()/1_000_000_000d;
        double maximum=Duration.between(created,after).toNanos()/1_000_000_000d;
        assertTrue(value>=minimum-0.001 && value<=maximum+0.001,()->key+" must use active rows and the database clock: "+value);
    }
}
