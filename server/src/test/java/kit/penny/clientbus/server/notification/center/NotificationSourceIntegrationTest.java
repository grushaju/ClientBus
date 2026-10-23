package kit.penny.clientbus.server.notification.center;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.consumer.KafkaPlatformMessageConsumer;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import kit.penny.clientbus.server.service.ConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Covers real domain/consumer wiring; no direct PersonalNotificationWriter calls. */
@SpringBootTest(properties = {"clientbus.notifications.creation-enabled=true",
        "clientbus.notifications.sse-enabled=false", "clientbus.notifications.cleanup-poll-ms=3600000"})
class NotificationSourceIntegrationTest extends AbstractIntegrationTest {
    @Autowired ConversationService conversations;
    @Autowired KafkaPlatformMessageConsumer consumer;
    @Autowired NotificationCenterService center;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    UUID organization, workspace, conversation, channelAccount, owner, other;
    String externalClient;
    UserEntity ownerUser, adminUser;

    private <T> T save(T entity) { em.persist(entity); return entity; }
    private <T> T as(UserEntity user, Supplier<T> action) {
        var principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try { return action.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org));
            ownerUser = save(new UserEntity("source-owner-" + UUID.randomUUID(), UUID.randomUUID() + "@test.local", "unused", UserRole.EMPLOYEE));
            var employee = save(new EmployeeEntity(org, ownerUser, "Owner", "Source", null));
            var secondUser = save(new UserEntity("source-other-" + UUID.randomUUID(), UUID.randomUUID() + "@test.local", "unused", UserRole.EMPLOYEE));
            var second = save(new EmployeeEntity(org, secondUser, "Other", "Source", null));
            adminUser = save(new UserEntity("source-admin-" + UUID.randomUUID(), UUID.randomUUID() + "@test.local", "unused", UserRole.SUPER_ADMIN));
            save(new EmployeeEntity(org, adminUser, "Admin", "Source", null));
            save(new EmployeeWorkspaceEntity(employee, w)); save(new EmployeeWorkspaceEntity(second, w));
            var account = save(TestDataFactory.channelAccount(save(TestDataFactory.channel(w)),
                    "source-channel-" + UUID.randomUUID(), null, null, "Source channel"));
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            var c = save(TestDataFactory.conversation(w, account, client));
            c.setHistoryStartReached(true); em.flush();
            organization = org.getId(); workspace = w.getId(); conversation = c.getId(); channelAccount = account.getId();
            owner = employee.getId(); other = second.getId(); externalClient = client.getExternalId();
        });
    }

    private long notifications(String type) {
        return jdbc.queryForObject("select count(*) from dbo.notification where organizationid=? and type=?", Long.class, organization, type);
    }
    private long occurrences() {
        return jdbc.queryForObject("select count(*) from dbo.notificationoccurrence where organizationid=?", Long.class, organization);
    }
    private long messages() {
        return jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Long.class, conversation);
    }
    private UUID assignmentOccurrence() {
        return jdbc.queryForObject("select assignmentoccurrenceid from dbo.conversation where conversationid=?", UUID.class, conversation);
    }
    private void assign(UUID employee) { as(adminUser, () -> conversations.assignEmployee(conversation, employee)); }
    private KafkaEvent<PlatformMessageRequest> event(String external, String client, EventOrigin origin, Instant observed) {
        var request = new PlatformMessageRequest(channelAccount, client, null, null, "Source client", client,
                external, MessageType.TEXT, "Source " + external, null, Instant.now(), List.of(), origin);
        return new KafkaEvent<>(UUID.randomUUID(), KafkaEventType.PLATFORM_MESSAGE, 1, observed, UUID.randomUUID(), request);
    }
    private void consume(KafkaEvent<PlatformMessageRequest> event) {
        // The actual listener delegates through the transactional processing proxy.
        consumer.consume(event);
    }
    private void initialOwner() {
        // Initial fixture state is not a source occurrence. Reassignment tests use the real service.
        jdbc.update("update dbo.conversation set assignedemployeeid=? where conversationid=?", owner, conversation);
    }

    @Test void administrativeAssignmentCreatesN1OnceAndNoOpKeepsItsOccurrenceAndAlert() {
        assign(owner);
        UUID occurrence = assignmentOccurrence();
        assertNotNull(occurrence);
        assertEquals(1, notifications("ASSIGNMENT")); assertEquals(1, occurrences());
        assertEquals(owner, jdbc.queryForObject("select recipientemployeeid from dbo.notification where organizationid=?", UUID.class, organization));
        assertEquals(occurrence.toString(), jdbc.queryForObject("select templateargs->>'assignmentOccurrenceId' from dbo.notification where organizationid=?", String.class, organization));
        assign(owner);
        assertEquals(occurrence, assignmentOccurrence());
        assertEquals(1, notifications("ASSIGNMENT")); assertEquals(1, occurrences());
        assertEquals(1, center.summary(AccessSubject.recipient(owner)).unreadCount());
        assertTrue(center.list(AccessSubject.recipient(owner), false, 30, null).items().getFirst().alertActive());
    }

    @Test void takingOneselfAndReleasingPersistEmptyDecisionsWithoutN1() {
        as(ownerUser, () -> conversations.assignmentToMe(conversation));
        UUID taken = assignmentOccurrence();
        assertEquals(owner, jdbc.queryForObject("select assignedemployeeid from dbo.conversation where conversationid=?", UUID.class, conversation));
        assertEquals(0, notifications("ASSIGNMENT")); assertEquals(1, occurrences());
        assertEquals(0, jdbc.queryForObject("select cardinality(recipientemployeeids) from dbo.notificationoccurrence where organizationid=?", Integer.class, organization));
        as(ownerUser, () -> conversations.assignmentToMe(conversation));
        assertEquals(taken, assignmentOccurrence()); assertEquals(1, occurrences());
        as(ownerUser, () -> conversations.unassignmentFromMe(conversation));
        assertNotEquals(taken, assignmentOccurrence());
        assertNull(jdbc.queryForObject("select assignedemployeeid from dbo.conversation where conversationid=?", UUID.class, conversation));
        assertEquals(0, notifications("ASSIGNMENT")); assertEquals(2, occurrences());
    }

    @Test void releaseClosesThePreviousAssignmentAlertWithoutDeletingItsUnreadCenterRecord() {
        assign(owner);
        as(adminUser, () -> conversations.unassignEmployee(conversation));
        assertEquals(1, notifications("ASSIGNMENT"));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select alertinvalidatedat is not null and readat is null from dbo.notification where organizationid=?", Boolean.class, organization)));
        assertEquals(1, center.summary(AccessSubject.recipient(owner)).unreadCount());
        assertFalse(center.list(AccessSubject.recipient(owner), false, 30, null).items().getFirst().alertActive());
    }

    @Test void rolledBackAssignmentLeavesNoPersonalSideEffectAndACommittedRetryCreatesOne() {
        UUID before = assignmentOccurrence();
        long outboxBefore = jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=?", Long.class, organization);
        as(adminUser, () -> tx.execute(status -> {
            conversations.assignEmployee(conversation, owner);
            assertEquals(1, notifications("ASSIGNMENT"));
            status.setRollbackOnly(); return null;
        }));
        assertNull(jdbc.queryForObject("select assignedemployeeid from dbo.conversation where conversationid=?", UUID.class, conversation));
        assertEquals(before, assignmentOccurrence());
        assertEquals(0, notifications("ASSIGNMENT")); assertEquals(0, occurrences());
        assertEquals(outboxBefore, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=?", Long.class, organization));
        assign(owner);
        assertEquals(1, notifications("ASSIGNMENT"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void consumerImportAndLiveInEitherOrderKeepOneBusinessMessageAndOneLiveDecision(boolean importFirst) {
        initialOwner();
        var live = event("import-live", externalClient, EventOrigin.LIVE, Instant.now());
        var imported = event("import-live", externalClient, EventOrigin.HISTORY_IMPORT, Instant.now());
        consume(importFirst ? imported : live);
        assertEquals(1, messages()); assertEquals(importFirst ? 0 : 1, notifications("INCOMING_MESSAGE"));
        consume(importFirst ? live : imported); consume(live);
        assertEquals(1, messages()); assertEquals(1, notifications("INCOMING_MESSAGE")); assertEquals(1, occurrences());
        assertEquals(owner, jdbc.queryForObject("select recipientemployeeid from dbo.notification where organizationid=?", UUID.class, organization));
    }

    @Test void legacyEnvelopeWithoutObservationTimePersistsBusinessAndDoesNotConsumeTheFirstTrustedLive() {
        initialOwner();
        consume(event("legacy-envelope", externalClient, EventOrigin.LIVE, null));
        assertEquals(1, messages()); assertEquals(0, notifications("INCOMING_MESSAGE")); assertEquals(0, occurrences());
        consume(event("legacy-envelope", externalClient, EventOrigin.LIVE, Instant.now()));
        assertEquals(1, messages()); assertEquals(1, notifications("INCOMING_MESSAGE")); assertEquals(1, occurrences());
    }

    @Test void consumerReplayAfterRealReassignmentNeverMovesTheInitialN2Audience() {
        initialOwner();
        var live = event("immutable-audience", externalClient, EventOrigin.LIVE, Instant.now());
        consume(live);
        assign(other);
        consume(live);
        assertEquals(1, messages()); assertEquals(1, notifications("INCOMING_MESSAGE"));
        assertEquals(owner, jdbc.queryForObject("select recipientemployeeid from dbo.notification where organizationid=? and type='INCOMING_MESSAGE'", UUID.class, organization));
        assertTrue(center.list(AccessSubject.recipient(other), false, 30, null).items().stream()
                .noneMatch(value -> value.type().name().equals("INCOMING_MESSAGE")));
        assertTrue(center.list(AccessSubject.recipient(owner), false, 30, null).items().isEmpty(), "Former owner loses target ACL");
    }

    @Test void consumerScopesSameExternalMessageIdToEachConversationOfOneChannelAccount() {
        initialOwner();
        String secondClient = tx.execute(status -> {
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(em.getReference(OrganizationEntity.class, organization)))));
            var second = save(TestDataFactory.conversation(em.getReference(WorkspaceEntity.class, workspace),
                    em.getReference(ChannelAccountEntity.class, channelAccount), client));
            second.setAssignedEmployee(em.getReference(EmployeeEntity.class, owner)); em.flush();
            return client.getExternalId();
        });
        consume(event("same-external-id", externalClient, EventOrigin.LIVE, Instant.now()));
        consume(event("same-external-id", secondClient, EventOrigin.LIVE, Instant.now()));
        assertEquals(2, notifications("INCOMING_MESSAGE")); assertEquals(2, occurrences());
        assertEquals(2, jdbc.queryForObject("select count(distinct targetid) from dbo.notification where organizationid=? and type='INCOMING_MESSAGE'", Long.class, organization));
        assertEquals(2, jdbc.queryForObject("select count(*) from dbo.message m join dbo.conversation c on c.conversationid=m.conversationid where c.channelaccountid=? and m.externalid='same-external-id'", Long.class, channelAccount));
    }
}
