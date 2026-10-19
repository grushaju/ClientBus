package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.exception.ConversationAssignmentConflictException;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupMembershipRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConversationAssignmentConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired ConversationService service;
    @Autowired ConversationGroupService groups;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationGroupMembershipRepository memberships;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    record Fixture(UUID conversationId, UUID workspaceId, UUID groupId,
                   UUID firstEmployeeId, UUID secondEmployeeId, UserEntity firstUser, UserEntity secondUser) {}

    private <T> T tx(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private <T> T as(UserEntity user, Supplier<T> operation) {
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try { return operation.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private Fixture fixture(boolean assigned) {
        return tx(() -> {
            OrganizationEntity organization = TestDataFactory.organization();
            entityManager.persist(organization);
            WorkspaceEntity workspace = TestDataFactory.workspace(organization);
            entityManager.persist(workspace);
            UserEntity firstUser = TestDataFactory.user("first-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com", "hash");
            UserEntity secondUser = TestDataFactory.user("second-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com", "hash");
            firstUser.setRole(UserRole.EMPLOYEE);
            secondUser.setRole(UserRole.EMPLOYEE);
            entityManager.persist(firstUser);
            entityManager.persist(secondUser);
            EmployeeEntity first = new EmployeeEntity(organization, firstUser, "First", "Employee", null);
            EmployeeEntity second = new EmployeeEntity(organization, secondUser, "Second", "Employee", null);
            entityManager.persist(first);
            entityManager.persist(second);
            EmployeeWorkspaceEntity supervisor = new EmployeeWorkspaceEntity(first, workspace);
            supervisor.setSupervisor(true);
            entityManager.persist(supervisor);
            entityManager.persist(new EmployeeWorkspaceEntity(second, workspace));
            ChannelEntity channel = TestDataFactory.channel(workspace);
            entityManager.persist(channel);
            ChannelAccountEntity channelAccount = TestDataFactory.channelAccount(channel);
            entityManager.persist(channelAccount);
            ClientAccountEntity clientAccount = TestDataFactory.clientAccount();
            entityManager.persist(clientAccount);
            ConversationEntity conversation = TestDataFactory.conversation(workspace, channelAccount, clientAccount);
            if (assigned) conversation.setAssignedEmployee(first);
            entityManager.persist(conversation);
            ConversationGroupEntity group = new ConversationGroupEntity(workspace, first, "Group");
            entityManager.persist(group);
            entityManager.flush();
            return new Fixture(conversation.getId(), workspace.getId(), group.getId(), first.getId(), second.getId(), firstUser, secondUser);
        });
    }

    // Hold the conversation row in the first transaction, then wait until the
    // second PostgreSQL transaction is actually blocked on it. This exercises
    // the database ordering rather than depending on thread scheduling speed.
    private Throwable orderedRace(Fixture f, Runnable first, UserEntity secondUser, Runnable second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), entered = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> as(f.firstUser(), () -> tx(() -> {
                conversations.findByIdForUpdate(f.conversationId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock release timed out");
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                first.run();
                return null;
            })));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Throwable> secondResult = executor.submit(() -> {
                try {
                    as(secondUser, () -> tx(() -> {
                        secondPid.set(((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue());
                        entered.countDown();
                        second.run();
                        return null;
                    }));
                    return null;
                } catch (RuntimeException failure) { return failure; }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "select wait_event_type = 'Lock' from pg_stat_activity where pid = ?", Boolean.class, secondPid.get())));
            release.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            return secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void twoConcurrentTakesHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture(false);
        Throwable loser = orderedRace(f, () -> service.assignmentToMe(f.conversationId()), f.secondUser(),
                () -> service.assignmentToMe(f.conversationId()));
        assertInstanceOf(ConversationAssignmentConflictException.class, loser);
        assertEquals(f.firstEmployeeId(), tx(() -> conversations.findById(f.conversationId()).orElseThrow().getAssignedEmployee().getId()));
    }

    @Test
    void aGroupAddAfterReassignmentCannotCreateStaleMembership() throws Exception {
        Fixture f = fixture(true);
        Throwable rejected = orderedRace(f, () -> service.assignEmployee(f.conversationId(), f.secondEmployeeId()), f.firstUser(),
                () -> groups.addConversation(f.workspaceId(), f.groupId(), f.conversationId()));
        assertInstanceOf(AccessDeniedException.class, rejected);
        assertFalse(memberships.existsByGroupIdAndConversationId(f.groupId(), f.conversationId()));
    }

    @Test
    void reassignmentAfterAGroupAddRemovesTheMembershipAtomically() throws Exception {
        Fixture f = fixture(true);
        Throwable failure = orderedRace(f, () -> groups.addConversation(f.workspaceId(), f.groupId(), f.conversationId()), f.firstUser(),
                () -> service.assignEmployee(f.conversationId(), f.secondEmployeeId()));
        assertNull(failure);
        assertFalse(memberships.existsByGroupIdAndConversationId(f.groupId(), f.conversationId()));
    }

    @Test
    void aGroupAddAfterReleaseIsRejected() throws Exception {
        Fixture f = fixture(true);
        Throwable rejected = orderedRace(f, () -> service.unassignmentFromMe(f.conversationId()), f.firstUser(),
                () -> groups.addConversation(f.workspaceId(), f.groupId(), f.conversationId()));
        assertInstanceOf(AccessDeniedException.class, rejected);
        assertFalse(memberships.existsByGroupIdAndConversationId(f.groupId(), f.conversationId()));
    }

    @Test
    void anOlderMetadataWriteCannotRestoreThePreviousAssignee() throws Exception {
        Fixture f = fixture(false);
        CountDownLatch loaded = new CountDownLatch(1), continueWrite = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> metadata = executor.submit(() -> tx(() -> {
                ConversationEntity stale = conversations.findById(f.conversationId()).orElseThrow();
                loaded.countDown();
                try { if (!continueWrite.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                stale.setLastMessagePreview("New inbound message");
                stale.setUnreadCount(1);
                return null;
            }));
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            as(f.secondUser(), () -> service.assignmentToMe(f.conversationId()));
            continueWrite.countDown();
            metadata.get(10, TimeUnit.SECONDS);
            tx(() -> {
                ConversationEntity result = conversations.findById(f.conversationId()).orElseThrow();
                assertEquals(f.secondEmployeeId(), result.getAssignedEmployee().getId());
                assertEquals("New inbound message", result.getLastMessagePreview());
                assertEquals(1, result.getUnreadCount());
                return null;
            });
        } finally { continueWrite.countDown(); executor.shutdownNow(); }
    }
}
