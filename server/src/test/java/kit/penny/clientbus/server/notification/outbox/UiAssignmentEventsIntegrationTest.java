package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import kit.penny.clientbus.server.service.ConversationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"clientbus.notifications.sse-enabled=true", "clientbus.notifications.creation-enabled=false",
        "clientbus.notifications.outbox-poll-ms=3600000", "clientbus.notifications.cleanup-poll-ms=3600000"})
class UiAssignmentEventsIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired ConversationService conversations;
    @Autowired NotificationRoutingPolicy routing;
    @Autowired AccessPolicy access;
    private UUID organization, workspace, conversation, owner, next, reader, supervisor, foreign;
    private UserEntity administrator, nextUser;

    private <T> T save(T value) { em.persist(value); return value; }
    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization()); var w = save(TestDataFactory.workspace(org));
            var people = new LinkedHashMap<String, EmployeeEntity>();
            for (String role : List.of("owner", "next", "reader", "supervisor", "admin", "foreign")) {
                var employeeOrg = role.equals("foreign") ? save(TestDataFactory.organization()) : org;
                String key = "assign-" + UUID.randomUUID();
                var user = save(new UserEntity(key, key + "@test.local", "unused", role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE));
                var employee = save(new EmployeeEntity(employeeOrg, user, role, "Assignment", null)); people.put(role, employee);
                if (!role.equals("foreign")) {
                    var membership = save(new EmployeeWorkspaceEntity(employee, w)); membership.setSupervisor(role.equals("supervisor"));
                }
                if (role.equals("admin")) administrator = user;
                if (role.equals("next")) nextUser = user;
            }
            var c = save(TestDataFactory.conversation(w, save(TestDataFactory.channelAccount(save(TestDataFactory.channel(w)))),
                    save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))))));
            c.setAssignedEmployee(people.get("owner")); em.flush();
            organization = org.getId(); workspace = w.getId(); conversation = c.getId();
            owner = people.get("owner").getId(); next = people.get("next").getId(); reader = people.get("reader").getId();
            supervisor = people.get("supervisor").getId(); foreign = people.get("foreign").getId();
        });
    }

    @Test void assignmentCleansFormerOwnerAndResyncsOnlyCurrentlyAllowedWorkspaceMembers() {
        as(administrator, () -> conversations.assignEmployee(conversation, next));
        var rows = events(); assertEquals(2, rows.size());
        var former = event(rows, UiInvalidation.Audience.FORMER_SCOPE);
        assertEquals(Set.of(owner), former.recipientEmployeeIds());
        assertEquals("access.invalidated", former.type());
        var cleanup = routing.forRecipient(AccessSubject.recipient(owner), former).orElseThrow();
        assertEquals("access.invalidated", cleanup.type()); assertNull(cleanup.resource()); assertNull(cleanup.workspaceId());
        assertTrue(routing.forRecipient(AccessSubject.recipient(next), former).isEmpty());
        assertTrue(routing.forRecipient(AccessSubject.recipient(foreign), former).isEmpty());
        var scope = event(rows, UiInvalidation.Audience.WORKSPACE);
        assertEquals("inbox.invalidated", scope.type()); assertEquals("workspace", scope.resource().kind());
        for (UUID employee : List.of(owner, next, reader, supervisor)) {
            var signal = routing.forRecipient(AccessSubject.recipient(employee), scope).orElseThrow();
            assertEquals("inbox.invalidated", signal.type()); assertNull(signal.resource());
        }
        assertTrue(routing.forRecipient(AccessSubject.recipient(foreign), scope).isEmpty());
        assertFalse(access.allows(AccessSubject.recipient(owner), AccessPolicy.Resource.CONVERSATION, conversation));
        assertTrue(access.allows(AccessSubject.recipient(next), AccessPolicy.Resource.CONVERSATION, conversation));
    }

    @Test void noOpAssignmentCreatesNoInvalidation() {
        as(administrator, () -> conversations.assignEmployee(conversation, owner));
        assertTrue(events().isEmpty()); assertEquals(owner, assignee());
    }

    @Test void rollbackRestoresAssignmentAndRemovesAllCleanupSignals() {
        as(administrator, () -> tx.execute(status -> {
            conversations.assignEmployee(conversation, next); em.flush(); assertEquals(2, events().size());
            status.setRollbackOnly(); return null;
        }));
        assertEquals(owner, assignee()); assertTrue(events().isEmpty());
    }

    @Test void selfAssignmentOfFreeConversationCleansEveryLostReaderAndUnassignKeepsTheirAccess() {
        as(administrator, () -> conversations.unassignEmployee(conversation));
        assertEquals(1, events().size()); assertEquals(UiInvalidation.Audience.WORKSPACE, events().getFirst().audience());
        as(nextUser, () -> conversations.assignmentToMe(conversation));
        var lost = event(events(), UiInvalidation.Audience.FORMER_SCOPE);
        assertEquals(Set.of(owner, reader), lost.recipientEmployeeIds(), "A formerly free conversation had several ordinary readers");
        assertTrue(access.allows(AccessSubject.recipient(supervisor), AccessPolicy.Resource.CONVERSATION, conversation));
        assertEquals(3, events().size());
        as(nextUser, () -> conversations.unassignmentFromMe(conversation));
        assertNull(assignee()); assertEquals(4, events().size());
        assertEquals(1, events().stream().filter(event -> event.audience() == UiInvalidation.Audience.FORMER_SCOPE).count());
        as(administrator, () -> conversations.unassignEmployee(conversation));
        assertEquals(4, events().size(), "Repeated unassign is a no-op");
    }

    @Test void queuedSignalsUseCurrentAclAndCleanupSurvivesDeletedTarget() {
        as(administrator, () -> conversations.assignEmployee(conversation, next));
        var rows = events(); var scope = event(rows, UiInvalidation.Audience.WORKSPACE);
        jdbc.update("delete from dbo.employeeworkspace where employeeid=? and workspaceid=?", next, workspace);
        assertTrue(routing.forRecipient(AccessSubject.recipient(next), scope).isEmpty(), "A queued scope event is no cached access permit");
        jdbc.update("delete from dbo.conversation where conversationid=?", conversation);
        var cleanup = routing.forRecipient(AccessSubject.recipient(owner), event(rows, UiInvalidation.Audience.FORMER_SCOPE)).orElseThrow();
        assertEquals("access.invalidated", cleanup.type()); assertNull(cleanup.resource());
        assertTrue(routing.forRecipient(AccessSubject.recipient(foreign), event(rows, UiInvalidation.Audience.FORMER_SCOPE)).isEmpty());
    }

    private UUID assignee() { return jdbc.queryForObject("select assignedemployeeid from dbo.conversation where conversationid=?", UUID.class, conversation); }
    private List<UiInvalidation> events() {
        return jdbc.query("select destination,payload::text from dbo.notificationoutbox where organizationid=? order by createdat,id", (rs, row) -> {
            assertEquals(UiKafkaSubscriber.TOPIC, rs.getString(1));
            var event = mapper.readValue(rs.getString(2), UiInvalidation.class);
            assertEquals(organization, event.organizationId()); assertEquals(workspace, event.workspaceId());
            assertEquals(1, event.schemaVersion()); assertNotNull(event.resource()); assertTrue(event.recipientEmployeeIds().size() <= 500);
            return event;
        }, organization);
    }
    private UiInvalidation event(List<UiInvalidation> events, UiInvalidation.Audience audience) {
        return events.stream().filter(event -> event.audience() == audience).findFirst().orElseThrow();
    }
    private <T> T as(UserEntity user, Supplier<T> action) {
        var principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }
}
