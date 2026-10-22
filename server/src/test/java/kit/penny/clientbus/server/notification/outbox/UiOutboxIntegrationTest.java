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
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"clientbus.notifications.sse-enabled=true","clientbus.notifications.outbox-poll-ms=3600000"})
class UiOutboxIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageService messages;
    @Autowired UiOutboxStore store;
    @Autowired UiOutboxRelay relay;
    UUID conversation;
    private <T> T save(T entity){em.persist(entity);return entity;}
    @BeforeEach void fixtures() {
        jdbc.update("delete from dbo.notification_outbox");
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
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notification_outbox",Integer.class));
        tx.executeWithoutResult(status->create("live",EventOrigin.LIVE));
        var ids=jdbc.queryForList("select event_id from dbo.notification_outbox order by event_id",UUID.class);
        tx.executeWithoutResult(status->create("live",EventOrigin.LIVE));
        assertEquals(ids,jdbc.queryForList("select event_id from dbo.notification_outbox order by event_id",UUID.class));
        assertEquals(2,ids.size());
        relay.tick();
        assertEquals(2,jdbc.queryForObject("select count(*) from dbo.notification_outbox where status='PUBLISHED' and published_at is not null",Integer.class));
    }
    @Test void staleClaimCannotCompleteTakeoverAndManualRetryPreservesEventIdentity() {
        tx.executeWithoutResult(status->create("claim",EventOrigin.LIVE));
        var first=store.claim().orElseThrow();
        jdbc.update("delete from dbo.notification_outbox where id<>?",first.id());
        jdbc.update("update dbo.notification_outbox set lease_until=clock_timestamp()-interval '1 second' where id=?",first.id());
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
        UUID id=jdbc.queryForObject("select id from dbo.notification_outbox order by created_at limit 1",UUID.class);
        jdbc.update("update dbo.notification_outbox set payload='{}'::jsonb where id=?",id);
        relay.tick();
        assertEquals("QUARANTINED",jdbc.queryForObject("select status from dbo.notification_outbox where id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.notification_outbox where status='PUBLISHED'",Integer.class));
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
                "select count(*) from dbo.notification_outbox where payload->>'origin'=?",Integer.class,origin.name()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.notification",Integer.class));
    }
}
