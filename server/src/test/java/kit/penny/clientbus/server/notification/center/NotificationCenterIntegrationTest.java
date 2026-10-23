package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.NotificationAudienceResolver;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.notification.push.PushIntentWriter;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.jwt.JwtService;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=true", "clientbus.notifications.sse-enabled=false",
        "clientbus.notifications.cleanup-poll-ms=3600000"})
class NotificationCenterIntegrationTest extends AbstractIntegrationTest {
    @Autowired PersonalNotificationWriter writer;
    @Autowired NotificationCenterService center;
    @Autowired NotificationCenterCleanup cleanup;
    @Autowired PersonalNotificationEvents events;
    @Autowired NotificationAudienceResolver audiences;
    @Autowired PushIntentWriter push;
    @Autowired NotificationProperties config;
    @Autowired MeterRegistry meters;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;
    @Autowired JwtService jwt;
    @Autowired DataSource dataSource;
    @LocalServerPort int port;
    UUID organization, workspace, conversation, account, owner, other, admin, channel;
    String token;

    private <T> T save(T entity) { em.persist(entity); return entity; }
    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org));
            var user = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID()+"@test.local", "unused", UserRole.EMPLOYEE));
            var employee = save(new EmployeeEntity(org, user, "First", "Last", null));
            save(new EmployeeWorkspaceEntity(employee, w));
            var user2 = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID()+"@test.local", "unused", UserRole.EMPLOYEE));
            var e2 = save(new EmployeeEntity(org, user2, "Other", "Employee", null));
            save(new EmployeeWorkspaceEntity(e2, w));
            var adminUser = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID()+"@test.local", "unused", UserRole.SUPER_ADMIN));
            var a = save(new EmployeeEntity(org, adminUser, "Admin", "User", null));
            var ch = save(TestDataFactory.channel(w));
            var ca = save(TestDataFactory.channelAccount(ch));
            var c = save(TestDataFactory.conversation(w, ca, save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))))));
            c.setAssignedEmployee(employee);
            em.flush();
            organization = org.getId(); workspace = w.getId(); conversation = c.getId(); account = ca.getId();
            owner = employee.getId(); other = e2.getId(); admin = a.getId(); channel = ch.getId(); token = jwt.generateToken(user);
        });
    }

    private AccessSubject subject() { return AccessSubject.recipient(owner); }
    private NotificationSourceContext source(EventOrigin origin) {
        return new NotificationSourceContext(origin, Instant.now(), UUID.randomUUID(), null);
    }
    private void incoming(String external) {
        tx.executeWithoutResult(status -> writer.incomingMessage(conversation, account, external, source(EventOrigin.LIVE)));
    }
    private UUID first() { return center.list(subject(), false, 100, null).items().getFirst().id(); }
    private long count(String table) {
        return jdbc.queryForObject("select count(*) from dbo." + table + " where organizationid=?", Long.class, organization);
    }

    @Test void writerRequiresTheBusinessTransactionAndRollbackLeavesNoNotificationOrIntent() {
        assertThrows(IllegalTransactionStateException.class, () -> writer.incomingMessage(conversation, account, "outside", source(EventOrigin.LIVE)));
        installation();
        double before = meters.counter("clientbus.notification.created", "type", "INCOMING_MESSAGE").count();
        tx.executeWithoutResult(status -> {
            writer.incomingMessage(conversation, account, "rollback", source(EventOrigin.LIVE));
            assertEquals(1, count("notification"));
            assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationdelivery d join dbo.notification n on n.id=d.notificationid where n.organizationid=?", Integer.class, organization));
            status.setRollbackOnly();
        });
        assertEquals(0, count("notification")); assertEquals(0, count("notificationdedup"));
        assertEquals(0, count("notificationoccurrence")); assertEquals(0, count("notificationoutbox"));
        assertEquals(before, meters.counter("clientbus.notification.created", "type", "INCOMING_MESSAGE").count());
    }

    @Test void importDoesNotConsumeLiveAndReplayCannotMoveTheInitialAudience() {
        tx.executeWithoutResult(status -> writer.incomingMessage(conversation, account, "shared", source(EventOrigin.HISTORY_IMPORT)));
        assertEquals(0, count("notificationoccurrence"));
        incoming("shared");
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?", other, conversation);
        incoming("shared");
        assertEquals(1, count("notification")); assertEquals(1, count("notificationoccurrence"));
        assertEquals(owner, jdbc.queryForObject("select recipientemployeeid from dbo.notification where organizationid=?", UUID.class, organization));
        assertTrue(center.list(AccessSubject.recipient(other), false, 30, null).items().isEmpty());
        assertTrue(center.list(subject(), false, 30, null).items().isEmpty());
    }

    @Test void emptyAndDisabledDecisionsAreImmutableAfterLaterAssignmentOrEnable() {
        jdbc.update("update dbo.conversation set assignedemployeeid=null where conversationid=?", conversation);
        incoming("unassigned");
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?", owner, conversation);
        incoming("unassigned");
        var disabledConfig = new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.creation-enabled", "false")))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        var disabled = new PersonalNotificationWriter(named, disabledConfig, audiences, push, events, mapper, meters);
        tx.executeWithoutResult(status -> disabled.incomingMessage(conversation, account, "disabled", source(EventOrigin.LIVE)));
        incoming("disabled");
        assertEquals(2, count("notificationoccurrence")); assertEquals(0, count("notification"));
        assertEquals(0, jdbc.queryForObject("select sum(cardinality(recipientemployeeids)) from dbo.notificationoccurrence where organizationid=?", Integer.class, organization));
    }

    @Test void staleSourceIsSuppressedAndConcurrentLiveDuplicatesProduceOneRecord() throws Exception {
        var stale = new NotificationSourceContext(EventOrigin.LIVE, Instant.now().minus(config.sourceReplayWindow()).minusSeconds(5), UUID.randomUUID(), null);
        tx.executeWithoutResult(status -> writer.incomingMessage(conversation, account, "too-old", stale));
        assertEquals(0, count("notificationoccurrence"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var go = new CountDownLatch(1);
            var a = executor.submit(() -> { go.await(); incoming("concurrent"); return null; });
            var b = executor.submit(() -> { go.await(); incoming("concurrent"); return null; });
            go.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, count("notification")); assertEquals(1, count("notificationdedup"));
        assertEquals(1, count("notificationoutbox"));
    }

    @Test void externalMessageIdsAreScopedToTheConversationWithinOneChannelAccount() {
        UUID secondConversation = tx.execute(status -> {
            var client = save(TestDataFactory.client(em.getReference(OrganizationEntity.class, organization)));
            var c = save(TestDataFactory.conversation(em.getReference(WorkspaceEntity.class, workspace),
                    em.getReference(ChannelAccountEntity.class, account), save(TestDataFactory.clientAccount(client))));
            c.setAssignedEmployee(em.getReference(EmployeeEntity.class, owner));
            em.flush();
            return c.getId();
        });
        incoming("same-external-id");
        tx.executeWithoutResult(status -> writer.incomingMessage(secondConversation, account, "same-external-id", source(EventOrigin.LIVE)));
        incoming("same-external-id");
        tx.executeWithoutResult(status -> writer.incomingMessage(secondConversation, account, "same-external-id", source(EventOrigin.LIVE)));
        assertEquals(2, count("notificationoccurrence"));
        assertEquals(2, count("notificationdedup"));
        assertEquals(Set.of(conversation, secondConversation), center.list(subject(), false, 30, null).items().stream()
                .map(n -> n.target().id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, count("notificationoutbox"));
    }

    @Test void replayNeverAddsAnInstallationRegisteredAfterTheOccurrence() {
        incoming("before-installation");
        installation();
        incoming("before-installation");
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notificationdelivery d join dbo.notification n on n.id=d.notificationid where n.organizationid=?", Integer.class, organization));
        incoming("after-installation");
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationdelivery d join dbo.notification n on n.id=d.notificationid where n.organizationid=?", Integer.class, organization));
    }

    @Test void listCountReadAndReadAllShareCurrentAclAndSingleReadDoesNotRepeatItsOutbox() {
        incoming("read"); UUID id = first();
        assertEquals(1, center.summary(subject()).unreadCount());
        assertEquals(0, center.summary(AccessSubject.recipient(admin)).unreadCount());
        assertEquals(404, assertThrows(NotificationApiException.class, () -> center.get(AccessSubject.recipient(admin), id)).status());
        var read = center.read(subject(), id);
        assertNotNull(read.readAt()); assertFalse(read.alertActive());
        assertEquals(read.readAt(), center.read(subject(), id).readAt());
        assertEquals(2, count("notificationoutbox"));
        incoming("revoked");
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?", owner);
        assertTrue(center.list(subject(), false, 30, null).items().isEmpty());
        assertEquals(0, center.summary(subject()).unreadCount());
        assertEquals(404, assertThrows(NotificationApiException.class, () -> center.read(subject(), id)).status());
        assertEquals(0, center.readAll(subject(), UUID.randomUUID().toString()).updatedCount());
    }

    @Test void readAllRetryDoesNotReadANewArrivalAndAConcurrentRepeatGetsTheSameResult() throws Exception {
        incoming("first"); String key = UUID.randomUUID().toString();
        var result = center.readAll(subject(), key);
        assertEquals(1, result.updatedCount());
        incoming("later");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> center.readAll(subject(), key));
            var b = executor.submit(() -> center.readAll(subject(), key));
            assertEquals(result, a.get(10, TimeUnit.SECONDS)); assertEquals(result, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, center.summary(subject()).unreadCount());
        assertEquals(1, count("notificationcommand"));
        var next = center.readAll(subject(), UUID.randomUUID().toString());
        assertEquals(1, next.updatedCount()); assertEquals(0, center.summary(subject()).unreadCount());
    }

    @Test void concurrentFirstReadAllClaimsOneExecutionAndOneInvalidation() throws Exception {
        incoming("first"); incoming("second");
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var go = new CountDownLatch(1);
            var a = executor.submit(() -> { go.await(); return center.readAll(subject(), key); });
            var b = executor.submit(() -> { go.await(); return center.readAll(subject(), key); });
            go.countDown();
            var result = a.get(10, TimeUnit.SECONDS);
            assertEquals(2, result.updatedCount());
            assertEquals(result, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("notificationcommand"));
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and eventtype='notifications.invalidated'", Integer.class, organization));
        assertEquals(0, center.summary(subject()).unreadCount());
    }

    @Test void emptyReadAllResultIsPersistedAndNeverConsumesALaterArrival() {
        String key = UUID.randomUUID().toString();
        var empty = center.readAll(subject(), key);
        assertEquals(0, empty.updatedCount());
        incoming("after-empty-command");
        assertEquals(empty, center.readAll(subject(), key));
        assertEquals(1, center.summary(subject()).unreadCount());
        assertEquals(1, count("notificationoutbox"));
    }

    @Test void readAllRollbackLeavesUnreadRowsAndNoClaimResultOrInvalidation() {
        incoming("before-rollback");
        String key = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            assertEquals(1, center.readAll(subject(), key).updatedCount());
            assertEquals(0, center.summary(subject()).unreadCount());
            status.setRollbackOnly();
        });
        assertEquals(1, center.summary(subject()).unreadCount());
        assertEquals(0, count("notificationcommand"));
        assertEquals(1, count("notificationoutbox"));
        assertEquals(1, center.readAll(subject(), key).updatedCount());
    }

    @Test void deletedTargetAndDisabledRecipientFailClosedForEveryReadPath() {
        incoming("target"); UUID id = first();
        jdbc.update("delete from dbo.conversation where conversationid=?", conversation);
        assertTrue(center.list(subject(), false, 30, null).items().isEmpty());
        assertEquals(0, center.summary(subject()).unreadCount());
        assertTrue(center.findForRecipient(id, owner).isEmpty());
        assertEquals(404, assertThrows(NotificationApiException.class, () -> center.read(subject(), id)).status());
        assertEquals(0, center.readAll(subject(), UUID.randomUUID().toString()).updatedCount());
        assertEquals(1, count("notification"));
        jdbc.update("update dbo.users set isenabled=false where userid=(select userid from dbo.employee where employeeid=?)", owner);
        assertTrue(center.findForRecipient(id, owner).isEmpty());
        assertEquals(403, assertThrows(NotificationApiException.class, () -> center.list(subject(), false, 30, null)).status());
        assertEquals(403, assertThrows(NotificationApiException.class, () -> center.summary(subject())).status());
        assertEquals(403, assertThrows(NotificationApiException.class, () -> center.readAll(subject(), UUID.randomUUID().toString())).status());
        var expired = AccessSubject.session(owner, Instant.now().minusSeconds(1));
        assertEquals(401, assertThrows(NotificationApiException.class, () -> center.get(expired, id)).status());
    }

    @Test void readAllUpdateUsesAFreshSnapshotAfterWaitingAndOneDatabaseTimestamp() throws Exception {
        incoming("before-wait");
        try (var held = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            held.setAutoCommit(false);
            held.createStatement().execute("lock table dbo.notificationcommand in share row exclusive mode");
            var future = executor.submit(() -> center.readAll(subject(), UUID.randomUUID().toString()));
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%insert into dbo.notificationcommand%'", Integer.class) > 0);
                incoming("while-waiting");
            } finally { held.commit(); }
            var result = future.get(10, TimeUnit.SECONDS);
            assertEquals(2, result.updatedCount());
            assertEquals(1, jdbc.queryForObject("select count(distinct readat) from dbo.notification where organizationid=?", Integer.class, organization));
            assertEquals(result.readAt(), jdbc.queryForObject("select min(readat) from dbo.notification where organizationid=?", java.sql.Timestamp.class, organization).toInstant());
        }
    }

    @Test void readAllRejectsDifferentStoredMeaningAndMalformedKeysWithoutUpdatingRows() {
        incoming("unread"); String key = UUID.randomUUID().toString();
        center.readAll(subject(), key);
        incoming("still-unread");
        jdbc.update("update dbo.notificationcommand set requesthash=? where organizationid=? and idempotencykey=?", "0".repeat(64), organization, key);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(NotificationApiException.class, () -> center.readAll(subject(), key)).code());
        assertEquals("INVALID_REQUEST", assertThrows(NotificationApiException.class, () -> center.readAll(subject(), "not-a-uuid")).code());
        assertEquals(1, center.summary(subject()).unreadCount());
    }

    @Test void keysetCursorIsBoundToRecipientOrganizationAndUnreadQuery() {
        incoming("page1"); incoming("page2"); incoming("page3");
        var one = center.list(subject(), false, 1, null);
        var two = center.list(subject(), false, 1, one.nextCursor());
        var three = center.list(subject(), false, 1, two.nextCursor());
        assertNull(three.nextCursor());
        assertEquals(3, Set.of(one.items().getFirst().id(), two.items().getFirst().id(), three.items().getFirst().id()).size());
        assertEquals("INVALID_CURSOR", assertThrows(NotificationApiException.class, () -> center.list(subject(), true, 1, one.nextCursor())).code());
        assertEquals("INVALID_CURSOR", assertThrows(NotificationApiException.class, () -> center.list(AccessSubject.recipient(other), false, 1, one.nextCursor())).code());
        assertEquals("INVALID_CURSOR", assertThrows(NotificationApiException.class, () -> center.list(subject(), false, 1, "a".repeat(1025))).code());
        assertEquals("INVALID_CURSOR", assertThrows(NotificationApiException.class, () -> center.list(subject(), false, 1, "%%%" )).code());
    }

    @Test void assignmentClosesAlertsEvenWithCreationDisabledAndReplayCannotCloseANewOccurrence() {
        incoming("before-assignment"); UUID first = first(); UUID occurrence = UUID.randomUUID();
        jdbc.update("update dbo.conversation set assignmentoccurrenceid=?,assignmentobservedat=clock_timestamp() where conversationid=?", occurrence, conversation);
        tx.executeWithoutResult(status -> writer.assignmentChanged(conversation, occurrence, admin));
        var assignment = center.list(subject(), false, 30, null).items().stream().filter(n -> n.type().name().equals("ASSIGNMENT")).findFirst().orElseThrow();
        assertFalse(center.get(subject(), first).alertActive()); assertTrue(assignment.alertActive());
        tx.executeWithoutResult(status -> writer.assignmentChanged(conversation, occurrence, admin));
        assertTrue(center.get(subject(), assignment.id()).alertActive());
        UUID next = UUID.randomUUID();
        jdbc.update("update dbo.conversation set assignmentoccurrenceid=?,assignmentobservedat=clock_timestamp() where conversationid=?", next, conversation);
        var disabled = new PersonalNotificationWriter(named, new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("clientbus.notifications", NotificationProperties.class), audiences, push, events, mapper, meters);
        tx.executeWithoutResult(status -> disabled.assignmentChanged(conversation, next, admin));
        assertFalse(center.get(subject(), assignment.id()).alertActive());
        assertEquals(2, count("notification"));
    }

    @Test void closingAnOutboundAttemptOrChannelIncidentPreservesTheCenterAndNeverReopensTheAlert() {
        UUID attempt = UUID.randomUUID(); UUID message = tx.execute(status -> {
            var m = save(new MessageEntity(em.getReference(ConversationEntity.class, conversation), MessageType.TEXT, MessageDirection.OUTBOUND, MessageSenderType.EMPLOYEE));
            m.setDeliveryStatus(MessageDeliveryStatus.FAILED); em.flush();
            jdbc.update("update dbo.message set sendattemptid=?,sendattemptstartedat=clock_timestamp() where messageid=?", attempt, m.getId());
            return m.getId();
        });
        tx.executeWithoutResult(status -> writer.outboundFailed(message, attempt));
        UUID failed = first(); assertTrue(center.get(subject(), failed).alertActive());
        tx.executeWithoutResult(status -> writer.closeOutboundAttempt(message, attempt));
        tx.executeWithoutResult(status -> writer.outboundFailed(message, attempt));
        assertFalse(center.get(subject(), failed).alertActive()); assertEquals(1, count("notification"));
        UUID incident = UUID.randomUUID();
        jdbc.update("update dbo.channel set status='ERROR',notificationincidentid=?,notificationincidentstartedat=clock_timestamp(),notificationexpecteddisconnect=false where channelid=?", incident, channel);
        tx.executeWithoutResult(status -> writer.channelIncident(account, incident, source(EventOrigin.LIVE)));
        var adminSubject = AccessSubject.recipient(admin);
        UUID alert = center.list(adminSubject, false, 30, null).items().getFirst().id();
        assertTrue(center.get(adminSubject, alert).alertActive());
        tx.executeWithoutResult(status -> writer.closeChannelIncident(account, incident));
        tx.executeWithoutResult(status -> writer.channelIncident(account, incident, source(EventOrigin.LIVE)));
        assertFalse(center.get(adminSubject, alert).alertActive());
    }

    @Test void boundedCleanupKeepsDedupAndDoesNotDeleteAnUnfinishedDelivery() {
        installation(); incoming("with-delivery"); UUID id = first();
        jdbc.update("update dbo.notification set createdat=clock_timestamp()-interval '40 days',alertexpiresat=clock_timestamp()-interval '39 days',retainuntil=clock_timestamp()-interval '1 day' where id=?", id);
        cleanup.cleanBatch(1);
        assertEquals(1, count("notification"));
        jdbc.update("update dbo.notificationdelivery set status='CANCELLED',leaseuntil=null,completedat=clock_timestamp(),retainuntil=clock_timestamp()+interval '1 day' where notificationid=?", id);
        cleanup.cleanBatch(1); assertEquals(1, count("notification"));
        jdbc.update("update dbo.notificationdelivery set createdat=clock_timestamp()-interval '10 days',completedat=clock_timestamp()-interval '9 days',retainuntil=clock_timestamp()-interval '1 day' where notificationid=?", id);
        cleanup.cleanBatch(1);
        assertEquals(0, count("notification")); assertEquals(1, count("notificationdedup")); assertEquals(1, count("notificationoccurrence"));
        incoming("with-delivery"); assertEquals(0, count("notification"));
    }

    @Test void cleanupRemovesExpiredFactsInFkOrderAndOldSourceCannotRecreateThem() {
        incoming("retained-source");
        jdbc.update("update dbo.notification set createdat=clock_timestamp()-interval '110 days',alertexpiresat=clock_timestamp()-interval '109 days',retainuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        jdbc.update("update dbo.notificationdedup set createdat=clock_timestamp()-interval '110 days',dedupuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        jdbc.update("update dbo.notificationoccurrence set createdat=clock_timestamp()-interval '110 days',dedupuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        cleanup.cleanBatch(1);
        assertEquals(0, count("notification")); assertEquals(0, count("notificationdedup")); assertEquals(0, count("notificationoccurrence"));
        var original = new NotificationSourceContext(EventOrigin.LIVE, Instant.now().minus(Duration.ofDays(110)), UUID.randomUUID(), null);
        tx.executeWithoutResult(status -> writer.incomingMessage(conversation, account, "retained-source", original));
        assertEquals(0, count("notificationoccurrence"));
        incoming("new-source");
        assertEquals(1, count("notification"));
    }

    @Test void channelWriterRejectsExpectedDisconnectRecoveredChannelAndWrongIncident() {
        UUID incident = UUID.randomUUID();
        jdbc.update("update dbo.channel set status='DISCONNECTED',notificationincidentid=?,notificationincidentstartedat=clock_timestamp(),notificationexpecteddisconnect=true where channelid=?", incident, channel);
        tx.executeWithoutResult(status -> writer.channelIncident(account, incident, source(EventOrigin.LIVE)));
        jdbc.update("update dbo.channel set status='CONNECTED',notificationexpecteddisconnect=false where channelid=?", channel);
        tx.executeWithoutResult(status -> writer.channelIncident(account, incident, source(EventOrigin.LIVE)));
        jdbc.update("update dbo.channel set status='ERROR' where channelid=?", channel);
        tx.executeWithoutResult(status -> writer.channelIncident(account, UUID.randomUUID(), source(EventOrigin.LIVE)));
        assertEquals(0, count("notificationoccurrence"));
        tx.executeWithoutResult(status -> writer.channelIncident(account, incident, source(EventOrigin.LIVE)));
        assertEquals(1, count("notification"));
        UUID reauthentication = UUID.randomUUID();
        jdbc.update("update dbo.channel set status='CONNECTING',notificationincidentid=?,notificationincidentstartedat=clock_timestamp(),notificationincidentreason='REAUTH_REQUIRED' where channelid=?", reauthentication, channel);
        tx.executeWithoutResult(status -> writer.channelIncident(account, reauthentication, source(EventOrigin.LIVE)));
        var active = center.list(AccessSubject.recipient(admin), false, 30, null).items().stream()
                .filter(n -> reauthentication.toString().equals(n.templateArgs().get("incidentId"))).findFirst().orElseThrow();
        assertTrue(active.alertActive());
    }

    @Test void stableDomainObservationTimesSuppressOldAssignmentAttemptAndIncidentAfterDedupCleanup() {
        UUID assignment = UUID.randomUUID(), attempt = UUID.randomUUID(), incident = UUID.randomUUID();
        jdbc.update("update dbo.conversation set assignmentoccurrenceid=?,assignmentobservedat=clock_timestamp() where conversationid=?", assignment, conversation);
        UUID message = tx.execute(status -> {
            var m = save(new MessageEntity(em.getReference(ConversationEntity.class, conversation), MessageType.TEXT, MessageDirection.OUTBOUND, MessageSenderType.EMPLOYEE));
            m.setSendAttemptId(attempt); m.setDeliveryStatus(MessageDeliveryStatus.FAILED); em.flush();
            return m.getId();
        });
        jdbc.update("update dbo.channel set status='ERROR',notificationincidentid=?,notificationincidentstartedat=clock_timestamp(),notificationexpecteddisconnect=false where channelid=?", incident, channel);
        tx.executeWithoutResult(status -> {
            writer.assignmentChanged(conversation, assignment, admin);
            writer.outboundFailed(message, attempt);
            writer.channelIncident(account, incident, source(EventOrigin.LIVE));
        });
        assertEquals(3, count("notification"));
        jdbc.update("update dbo.conversation set assignmentobservedat=clock_timestamp()-interval '110 days' where conversationid=?", conversation);
        jdbc.update("update dbo.message set sendattemptstartedat=clock_timestamp()-interval '110 days' where messageid=?", message);
        jdbc.update("update dbo.channel set notificationincidentstartedat=clock_timestamp()-interval '110 days' where channelid=?", channel);
        jdbc.update("update dbo.notification set createdat=clock_timestamp()-interval '110 days',alertexpiresat=clock_timestamp()-interval '109 days',retainuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        jdbc.update("update dbo.notificationdedup set createdat=clock_timestamp()-interval '110 days',dedupuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        jdbc.update("update dbo.notificationoccurrence set createdat=clock_timestamp()-interval '110 days',dedupuntil=clock_timestamp()-interval '1 day' where organizationid=?", organization);
        cleanup.cleanBatch(100);
        assertEquals(0, count("notificationoccurrence"));
        tx.executeWithoutResult(status -> {
            writer.assignmentChanged(conversation, assignment, admin);
            writer.outboundFailed(message, attempt);
            // A new callback context must not renew the age of the existing incident.
            writer.channelIncident(account, incident, source(EventOrigin.LIVE));
        });
        assertEquals(0, count("notificationoccurrence"));
        assertEquals(0, count("notification"));
        assertEquals(3, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and eventtype='notifications.created'", Integer.class, organization));
    }

    @Test void legacyNullObservationTimesSuppressNewEffectsWithoutDisablingAlertClosure() {
        incoming("before-legacy-transition"); UUID notification = first();
        UUID assignment = UUID.randomUUID(), attempt = UUID.randomUUID(), incident = UUID.randomUUID();
        jdbc.update("update dbo.conversation set assignmentoccurrenceid=?,assignmentobservedat=null where conversationid=?", assignment, conversation);
        UUID message = tx.execute(status -> {
            var m = save(new MessageEntity(em.getReference(ConversationEntity.class, conversation), MessageType.TEXT, MessageDirection.OUTBOUND, MessageSenderType.EMPLOYEE));
            m.setSendAttemptId(attempt); m.setDeliveryStatus(MessageDeliveryStatus.FAILED); em.flush();
            return m.getId();
        });
        jdbc.update("update dbo.message set sendattemptstartedat=null where messageid=?", message);
        jdbc.update("update dbo.channel set status='ERROR',notificationincidentid=?,notificationincidentstartedat=null,notificationexpecteddisconnect=false where channelid=?", incident, channel);
        tx.executeWithoutResult(status -> {
            writer.assignmentChanged(conversation, assignment, admin);
            writer.outboundFailed(message, attempt);
            writer.channelIncident(account, incident, source(EventOrigin.LIVE));
        });
        assertEquals(1, count("notificationoccurrence")); assertEquals(1, count("notification"));
        assertFalse(center.get(subject(), notification).alertActive());
    }

    @Test void httpContractUsesNoStoreTypedErrorsAndOwnResources() throws Exception {
        incoming("http"); UUID id = first();
        try (var http = HttpClient.newHttpClient()) {
            var detail = http.send(request("/api/notifications/"+id, token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detail.statusCode()); assertEquals("no-store", detail.headers().firstValue("Cache-Control").orElseThrow());
            var invalid = http.send(request("/api/notifications?pageCursor=bad", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode()); assertTrue(invalid.body().contains("INVALID_CURSOR"));
            var badLimit = http.send(request("/api/notifications?limit=0", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badLimit.statusCode()); assertTrue(badLimit.body().contains("INVALID_REQUEST"));
            var missing = http.send(request("/api/notifications/"+UUID.randomUUID(), token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode()); assertTrue(missing.body().contains("NOT_FOUND"));
            var unauthorized = http.send(request("/api/notifications/summary", null).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode()); assertTrue(unauthorized.body().contains("UNAUTHENTICATED"));
            var absentKey = http.send(request("/api/notifications/read-all", token).PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, absentKey.statusCode()); assertTrue(absentKey.body().contains("INVALID_REQUEST"));
        }
    }

    @Test void anonymousAndInvalidBearerResponsesAreNoStoreForCenterAndPush() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String credential : Arrays.asList(null, "invalid-bearer")) {
                var summary = http.send(request("/api/notifications/summary", credential).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, summary.statusCode());
                assertTrue(summary.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
                assertTrue(summary.body().contains("UNAUTHENTICATED"));
                var installation = http.send(request("/api/push/installations/" + UUID.randomUUID(), credential)
                        .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, installation.statusCode());
                assertTrue(installation.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
                assertTrue(installation.body().contains("UNAUTHENTICATED"));
            }
        }
    }

    @Test void disabledSseReturnsNotFoundWithoutInvalidatingAnAuthenticatedSession() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String path : List.of("/api/events", "/api/events?workspaceId=" + workspace)) {
                var response = http.send(request(path, token).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode(), "Disabled SSE must select REST fallback, not log out a valid session");
                assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
                assertTrue(response.body().contains("SSE_DISABLED"));
                var summary = http.send(request("/api/notifications/summary", token).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, summary.statusCode());
            }
            for (String credential : Arrays.asList(null, "invalid-bearer")) {
                var unauthorized = http.send(request("/api/events", credential).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauthorized.statusCode());
                assertTrue(unauthorized.body().contains("UNAUTHENTICATED"));
            }
        }
    }

    private HttpRequest.Builder request(String path, String credential) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if (credential != null) request.header("Authorization", "Bearer "+credential);
        return request;
    }

    private void installation() {
        String fingerprint = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
                insert into dbo.pushinstallation (id,registrationversion,organizationid,recipientemployeeid,platform,provider,appid,environment,
                    encryptedendpoint,endpointfingerprint,activeendpointfingerprint,ownershipsecrethash,bindingversion,endpointversion,enabled,
                    reportedpermission,lastseenat,activeuntil)
                values (?,1,?,?,'ANDROID','FCM','test','SANDBOX',decode('01','hex'),?,?,decode(repeat('00',32),'hex'),1,1,true,'GRANTED',clock_timestamp(),clock_timestamp()+interval '30 days')
                """, UUID.randomUUID(), organization, owner, fingerprint, fingerprint);
    }
}
