package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"clientbus.notifications.creation-enabled=true",
        "clientbus.notifications.callback-cleanup-ms=3600000"})
class OutboundAttemptIntegrationTest extends AbstractIntegrationTest {
    @Autowired MessageService messages;
    @Autowired MessageProcessingService processing;
    @Autowired OutboundCallbackStore callbacks;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    UUID organization, conversation, account, employee;
    String recipient;

    private <T> T save(T entity) { em.persist(entity); return entity; }
    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org));
            var user = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID()+"@test.local", "unused", UserRole.EMPLOYEE));
            var owner = save(new EmployeeEntity(org, user, "Owner", "Test", null));
            save(new EmployeeWorkspaceEntity(owner, w));
            var ch = save(TestDataFactory.channel(w)); ch.setStatus(ChannelConnectionStatus.CONNECTED);
            var ca = save(TestDataFactory.channelAccount(ch));
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            var c = save(TestDataFactory.conversation(w, ca, client)); c.setAssignedEmployee(owner);
            em.flush();
            organization=org.getId(); conversation=c.getId(); account=ca.getId(); employee=owner.getId(); recipient=client.getExternalId();
            var principal = new UserPrincipal(user);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        });
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    private UUID outbound() {
        return tx.execute(status -> {
            var m = new MessageEntity(em.find(ConversationEntity.class, conversation), MessageType.TEXT,
                    MessageDirection.OUTBOUND, MessageSenderType.EMPLOYEE);
            m.setEmployee(em.find(EmployeeEntity.class, employee)); m.setContent("test");
            m.setProcessingStatus(MessageProcessingStatus.PROCESSING); m.setSendAttemptId(UUID.randomUUID());
            em.persist(m); em.flush(); return m.getId();
        });
    }
    private UUID attempt(UUID id) { return jdbc.queryForObject("select sendattemptid from dbo.message where messageid=?", UUID.class, id); }
    private String value(UUID id, String column) { return jdbc.queryForObject("select "+column+" from dbo.message where messageid=?", String.class, id); }
    private int notices() { return jdbc.queryForObject("select count(*) from dbo.notification where organizationid=? and type='OUTBOUND_FAILED'", Integer.class, organization); }
    private void bind(UUID id, UUID attempt, String pending) { messages.registerExternalAttempt(id,attempt,account,recipient,pending); }
    private UUID result(String pending, String finalId, boolean failed) { return messages.recordExternalOutcome(account,recipient,pending,finalId,failed); }
    private Path temporary(UUID id, UUID attempt) throws Exception {
        return Files.createTempFile("clientbus-telegram-"+account+"-"+id+"-"+attempt+"-", ".test");
    }

    @Test void actualCreationInitializesPersistentAttemptAndObservedTime() {
        var created = messages.createOutboundMessage(new CreateOutboundMessageRequest(conversation, MessageType.TEXT, "created", null, null));
        assertNotNull(attempt(created.id()));
        assertNotNull(jdbc.queryForObject("select sendattemptstartedat from dbo.message where messageid=?", java.sql.Timestamp.class, created.id()));
    }

    @Test void earlyFailureIsReplayedOnceInTheRegistrationTransaction() {
        UUID id=outbound(), a=attempt(id);
        assertNull(result("pending", "final", true)); assertEquals(0,notices());
        bind(id,a,"pending");
        assertEquals("FAILED",value(id,"deliverystatus")); assertEquals("PROCESSED",value(id,"processingstatus"));
        assertEquals(1,notices());
        result("pending","final",true); bind(id,a,"pending"); assertEquals(1,notices());
    }

    @Test void earlySuccessCommitsFinalIdAndReleasesOnlyItsAttemptFiles() throws Exception {
        UUID id=outbound(), a=attempt(id), other=UUID.randomUUID();
        Path own=temporary(id,a), newer=temporary(id,other);
        try {
            assertNull(result("pending","final",false)); assertTrue(Files.exists(own));
            bind(id,a,"pending");
            assertEquals("SENT",value(id,"deliverystatus")); assertEquals("final",value(id,"externalid"));
            assertFalse(Files.exists(own)); assertTrue(Files.exists(newer)); assertEquals(0,notices());
        } finally { Files.deleteIfExists(own); Files.deleteIfExists(newer); }
    }

    @Test void rollbackDoesNotPublishFailureOrDeleteTemporaryFiles() throws Exception {
        UUID id=outbound(), a=attempt(id); Path own=temporary(id,a);
        try {
            tx.executeWithoutResult(status -> {
                result("rollback","final",true); bind(id,a,"rollback");
                assertEquals(1,notices()); status.setRollbackOnly();
            });
            assertTrue(Files.exists(own)); assertEquals(0,notices()); assertEquals("PENDING",value(id,"deliverystatus"));
            assertEquals(0,jdbc.queryForObject("select count(*) from dbo.outboundsendcallback where channelaccountid=?",Integer.class,account));
        } finally { Files.deleteIfExists(own); }
    }

    @Test void lateCallbacksAndQueuedCommandsCannotTouchTheManualRetry() throws Exception {
        UUID id=outbound(), old=attempt(id); bind(id,old,"old"); result("old","old-final",true);
        messages.retryDelivery(id); UUID current=attempt(id); assertNotEquals(old,current);
        assertNotNull(jdbc.queryForObject("select alertinvalidatedat from dbo.notification where organizationid=?",java.sql.Timestamp.class,organization));
        Path currentFile=temporary(id,current);
        try {
            assertNull(result("old","old-final",false)); assertNull(result("old","old-final",true));
            messages.markDeliveryFailed(id,old); messages.markProcessingFailed(id,old);
            messages.markRead(id,old); messages.markDelivered(id,old);
            assertFalse(messages.claimOutboundDelivery(id,old)); messages.releaseOutboundDeliveryClaim(id,old);
            assertEquals(current,attempt(id)); assertEquals("PROCESSING",value(id,"processingstatus"));
            assertEquals("PENDING",value(id,"deliverystatus")); assertEquals(1,notices()); assertTrue(Files.exists(currentFile));
            bind(id,current,"new"); result("new","new-final",true); assertEquals(2,notices());
        } finally { Files.deleteIfExists(currentFile); }
    }

    @Test void callbackAndRegistrationSerializeInEitherOrder() throws Exception {
        UUID id=outbound(), a=attempt(id); var gate=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var bind=pool.submit(() -> { gate.await(); bind(id,a,"race"); return null; });
            var callback=pool.submit(() -> { gate.await(); return result("race","race-final",true); });
            gate.countDown(); bind.get(15,TimeUnit.SECONDS); callback.get(15,TimeUnit.SECONDS);
        }
        assertEquals("FAILED",value(id,"deliverystatus")); assertEquals(1,notices());
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.outboundsendcallback where channelaccountid=?",Integer.class,account));
    }

    @Test void identicalExternalIdsInTwoChatsRemainIndependent() {
        UUID first=outbound(); UUID firstAttempt=attempt(first); String firstRecipient=recipient;
        conversation=tx.execute(status -> {
            var previous=em.find(ConversationEntity.class,conversation);
            var client=save(TestDataFactory.clientAccount(save(TestDataFactory.client(previous.getWorkspace().getOrganization()))));
            var next=save(TestDataFactory.conversation(previous.getWorkspace(),previous.getChannelAccount(),client));
            next.setAssignedEmployee(em.find(EmployeeEntity.class,employee)); em.flush(); recipient=client.getExternalId(); return next.getId();
        });
        UUID second=outbound();
        messages.registerExternalAttempt(first,firstAttempt,account,firstRecipient,"same"); bind(second,attempt(second),"same");
        messages.recordExternalOutcome(account,firstRecipient,"same","final",true);
        assertEquals("FAILED",value(first,"deliverystatus")); assertEquals("PENDING",value(second,"deliverystatus")); assertEquals(1,notices());
        result("same","final",false); assertEquals("SENT",value(second,"deliverystatus"));
    }

    @Test void unqualifiedLegacyPlatformEventsCannotMutateAnIdentifiedAttempt() {
        UUID id=outbound(), a=attempt(id); bind(id,a,"platform-id");
        processing.processPlatformEvent(new PlatformMessageEvent(account,"platform-id",PlatformMessageEventType.FAILED,Instant.now(),null));
        assertEquals("PENDING",value(id,"deliverystatus")); assertEquals(0,notices());
        processing.processPlatformEvent(new PlatformMessageEvent(account,"platform-id",PlatformMessageEventType.FAILED,Instant.now(),null,a));
        assertEquals("FAILED",value(id,"deliverystatus")); assertEquals(1,notices());
    }

    @Test void invalidScopeAndRebindingRollbackWithoutChangingMessages() {
        UUID id=outbound(), a=attempt(id), second=outbound();
        assertThrows(IllegalArgumentException.class,()->messages.registerExternalAttempt(id,a,account,"another-chat","pending"));
        assertNull(value(id,"externalid")); bind(id,a,"pending");
        assertThrows(IllegalStateException.class,()->bind(second,attempt(second),"pending"));
        assertNull(value(second,"externalid")); assertEquals("pending",value(id,"externalid"));
    }

    @Test void processingFailureRemainsTerminalWhenAResultArrivesLater() {
        UUID id=outbound(), a=attempt(id); bind(id,a,"pending");
        messages.markProcessingFailed(id,a); assertEquals(1,notices());
        assertNull(result("pending","final",false)); assertNull(result("pending","final",true));
        messages.markDeliveryFailed(id,a); bind(id,a,"pending");
        assertEquals("FAILED",value(id,"processingstatus"));
        assertEquals("PENDING",value(id,"deliverystatus")); assertEquals(1,notices());
    }

    @Test void cleanupSkipsTheCorrelationRowUsedByAnInFlightResult() throws Exception {
        UUID id=outbound(), a=attempt(id); bind(id,a,"locked");
        jdbc.update("update dbo.outboundsendcallback set retainuntil=clock_timestamp()-interval '1 second' where channelaccountid=?",account);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->tx.execute(status->{
                jdbc.queryForList("select id from dbo.outboundsendcallback where channelaccountid=? for update",account);
                locked.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);} return null;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            try { callbacks.cleanup(); assertEquals(1,jdbc.queryForObject("select count(*) from dbo.outboundsendcallback where channelaccountid=?",Integer.class,account)); }
            finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
        result("locked","final",true); assertEquals(1,notices());
        callbacks.cleanup(); assertEquals(0,jdbc.queryForObject("select count(*) from dbo.outboundsendcallback where channelaccountid=?",Integer.class,account));
    }
}
