package kit.penny.clientbus.server.notification.outbox;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.channel.UpdateChannelRequest;
import kit.penny.clientbus.common.dto.conversation.*;
import kit.penny.clientbus.common.dto.employee.SetEmployeeEnabledRequest;
import kit.penny.clientbus.common.dto.organization.UpdateOrganizationRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.NotificationRoutingPolicy;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.UserPrincipal;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import kit.penny.clientbus.server.service.*;
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
class UiDomainEventsIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;
    @Autowired NotificationRoutingPolicy routing;
    @Autowired ConversationGroupService groups;
    @Autowired SavedConversationFilterService filters;
    @Autowired EmployeeWorkspaceService memberships;
    @Autowired WorkspaceService workspaces;
    @Autowired OrganizationService organizations;
    @Autowired EmployeeService employees;
    @Autowired ChannelService channels;
    @Autowired ChannelStatusService statuses;
    @Autowired ClientAccountService accounts;
    @Autowired ClientService clients;
    @Autowired MessageService messages;
    @Autowired UiOutboxRelay relay;
    UUID organization, foreignOrganization, workspace, foreignWorkspace, conversation, account, channel, channelAccount;
    UUID owner, peer, outsider, admin;
    UserEntity ownerUser, adminUser;

    private <T> T save(T value) { em.persist(value); return value; }
    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization()); var foreign = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org)); var fw = save(TestDataFactory.workspace(foreign));
            var people = new LinkedHashMap<String, EmployeeEntity>();
            for (String role : List.of("owner", "peer", "admin", "foreign")) {
                String key = "domain-" + UUID.randomUUID();
                var user = save(new UserEntity(key, key + "@test.local", "unused",
                        role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE));
                var person = save(new EmployeeEntity(role.equals("foreign") ? foreign : org, user, role, "Domain", null));
                save(new EmployeeWorkspaceEntity(person, role.equals("foreign") ? fw : w));
                people.put(role, person);
                if (role.equals("owner")) ownerUser = user;
                if (role.equals("admin")) adminUser = user;
            }
            var ch = save(TestDataFactory.channel(w));
            var ca = save(TestDataFactory.channelAccount(ch));
            var a = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            var c = save(TestDataFactory.conversation(w, ca, a)); c.setAssignedEmployee(people.get("owner"));
            em.flush();
            organization = org.getId(); foreignOrganization = foreign.getId(); workspace = w.getId(); foreignWorkspace = fw.getId();
            conversation = c.getId(); account = a.getId(); channel = ch.getId(); channelAccount = ca.getId();
            owner = people.get("owner").getId(); peer = people.get("peer").getId(); outsider = people.get("foreign").getId();
            admin = people.get("admin").getId();
        });
    }

    @Test void groupCrudAndMembershipReachOnlyOwnersOtherSessionsIncludingAfterDeletion() {
        var group = as(ownerUser, () -> groups.createGroup(workspace, "Private", "folder"));
        as(ownerUser, () -> { groups.addConversation(workspace, group.id(), conversation); return null; });
        as(ownerUser, () -> groups.renameGroup(workspace, group.id(), "Renamed"));
        as(ownerUser, () -> { groups.removeConversation(workspace, group.id(), conversation); groups.deleteGroup(workspace, group.id()); return null; });
        assertEquals(5, events().size());
        for (var event : events()) {
            assertEquals("groups.invalidated", event.type()); assertEquals(Set.of(owner), event.recipientEmployeeIds());
            var visible = routing.forRecipient(AccessSubject.recipient(owner), event).orElseThrow();
            assertEquals("groups.invalidated", visible.type()); assertEquals("employee", visible.resource().kind());
            assertTrue(routing.forRecipient(AccessSubject.recipient(peer), event).isEmpty());
            assertTrue(routing.forRecipient(AccessSubject.recipient(outsider), event).isEmpty());
        }
    }

    @Test void privateAndSystemFiltersUseDifferentAudiencesAndDeletedFilterDoesNotBlockItsSignal() {
        var definition = new ConversationSearchRequest(null, null, null, null, null, null, null, null, null, null, null, null);
        var filter = as(ownerUser, () -> filters.createFilter(workspace, new SaveConversationFilterRequest("Private", SavedFilterType.PRIVATE, definition)));
        as(ownerUser, () -> { filters.deleteFilter(workspace, filter.id()); return null; });
        as(adminUser, () -> filters.createFilter(workspace, new SaveConversationFilterRequest("System", SavedFilterType.SYSTEM, definition)));
        assertEquals(3, events().size());
        for (var event : events()) {
            assertEquals("filters.invalidated", event.type());
            if (event.audience() == UiInvalidation.Audience.PERSONAL) {
                assertTrue(routing.forRecipient(AccessSubject.recipient(owner), event).isPresent());
                assertTrue(routing.forRecipient(AccessSubject.recipient(peer), event).isEmpty());
            } else {
                var signal = routing.forRecipient(AccessSubject.recipient(peer), event).orElseThrow();
                assertEquals("filters.invalidated", signal.type()); assertNull(signal.resource());
            }
        }
    }

    @Test void rollbackRemovesDomainChangeAndItsOutboxRows() {
        as(ownerUser, () -> tx.execute(status -> {
            groups.createGroup(workspace, "Rollback"); em.flush(); assertEquals(1, events().size());
            status.setRollbackOnly(); return null;
        }));
        assertTrue(events().isEmpty()); assertTrue(as(ownerUser, () -> groups.getGroups(workspace)).isEmpty());
    }

    @Test void removedMembershipStillReceivesIdentityCleanupAndCannotReceiveWorkspaceData() {
        as(adminUser, () -> { memberships.removeWorkspace(owner, workspace); return null; });
        var identity = events().stream().filter(e -> e.type().equals("identity.invalidated")).findFirst().orElseThrow();
        assertEquals("identity.invalidated", routing.forRecipient(AccessSubject.recipient(owner), identity).orElseThrow().type());
        var scope = events().stream().filter(e -> e.audience() == UiInvalidation.Audience.WORKSPACE).findFirst().orElseThrow();
        assertTrue(routing.forRecipient(AccessSubject.recipient(owner), scope).isEmpty());
        assertTrue(routing.forRecipient(AccessSubject.recipient(peer), identity).isEmpty());
        as(adminUser, () -> memberships.assignWorkspace(owner, workspace, true));
        assertEquals(2, events().stream().filter(e -> e.type().equals("identity.invalidated")).count());
    }

    @Test void deletedWorkspaceUsesFormerAudienceAndNoDeletedTargetInBrowserPayload() {
        UUID empty = tx.execute(status -> {
            var w = save(TestDataFactory.workspace(em.find(OrganizationEntity.class, organization)));
            em.flush(); return w.getId();
        });
        as(adminUser, () -> { workspaces.deleteWorkspace(empty); return null; });
        assertNull(jdbc.queryForObject("select max(workspaceid::text) from dbo.workspace where workspaceid=?", String.class, empty));
        var former = events().stream().filter(e -> e.audience() == UiInvalidation.Audience.FORMER_SCOPE).findFirst().orElseThrow();
        var cleanup = routing.forRecipient(AccessSubject.recipient(admin), former).orElseThrow();
        assertEquals("access.invalidated", cleanup.type()); assertNull(cleanup.workspaceId()); assertNull(cleanup.resource());
        assertTrue(routing.forRecipient(AccessSubject.recipient(outsider), former).isEmpty());
    }

    @Test void effectiveGlobalProfileChangeInvalidatesEveryUsingWorkspaceWithoutCrossOrganizationDisclosure() {
        tx.executeWithoutResult(status -> {
            var fw = em.find(WorkspaceEntity.class, foreignWorkspace);
            var fc = save(TestDataFactory.channelAccount(save(TestDataFactory.channel(fw))));
            save(TestDataFactory.conversation(fw, fc, em.find(ClientAccountEntity.class, account)));
        });
        accounts.updateExistingProfile(account, null, null, "Changed", null);
        var scopeEvents = events().stream().filter(e -> e.audience() == UiInvalidation.Audience.WORKSPACE).toList();
        assertEquals(Set.of(workspace, foreignWorkspace), new HashSet<>(scopeEvents.stream().map(UiInvalidation::workspaceId).toList()));
        for (var event : scopeEvents) {
            UUID allowed = event.organizationId().equals(organization) ? owner : outsider;
            UUID denied = allowed.equals(owner) ? outsider : owner;
            var signal = routing.forRecipient(AccessSubject.recipient(allowed), event).orElseThrow(); assertNull(signal.resource());
            assertTrue(routing.forRecipient(AccessSubject.recipient(denied), event).isEmpty());
        }
        int count = events().size(); accounts.updateExistingProfile(account, null, null, "Changed", null);
        assertEquals(count, events().size(), "Repeated inbound profile is not an invalidation storm");
    }

    @Test void connectorLifecyclePublishesOnlyEffectiveSharedTransitions() {
        statuses.beginConnecting(channelAccount); statuses.beginConnecting(channelAccount);
        statuses.transition(channelAccount, ChannelConnectionStatus.CONNECTED, "CONNECTED");
        statuses.transition(channelAccount, ChannelConnectionStatus.CONNECTED, "CONNECTED");
        assertEquals(2, events().size());
        assertTrue(events().stream().allMatch(e -> e.type().equals("channels.invalidated") && e.audience() == UiInvalidation.Audience.WORKSPACE));
    }

    @Test void outboundStatusAndReadAreAtomicVisibleAndIgnoreLateAttempt() {
        UUID attempt = UUID.randomUUID();
        UUID id = tx.execute(status -> {
            var message = new MessageEntity(em.find(ConversationEntity.class, conversation), MessageType.TEXT, MessageDirection.OUTBOUND, MessageSenderType.EMPLOYEE);
            message.setEmployee(em.find(EmployeeEntity.class, owner)); message.setDeliveryStatus(MessageDeliveryStatus.SENT);
            message.setProcessingStatus(MessageProcessingStatus.PROCESSED); message.setSendAttemptId(attempt); save(message); em.flush(); return message.getId();
        });
        messages.markDelivered(id, attempt); assertEquals(2, events().size());
        messages.markDelivered(id, attempt); messages.markDeliveryFailed(id, UUID.randomUUID()); assertEquals(2, events().size());
        tx.executeWithoutResult(status -> { messages.markRead(id, attempt); em.flush(); assertEquals(4, events().size()); status.setRollbackOnly(); });
        assertEquals(2, events().size());
        assertEquals("DELIVERED", jdbc.queryForObject("select deliverystatus from dbo.message where messageid=?", String.class, id));
        messages.markRead(id, attempt); assertEquals(4, events().size());
        for (var event : events()) {
            assertEquals(UiInvalidation.Audience.CONVERSATION, event.audience());
            assertTrue(routing.forRecipient(AccessSubject.recipient(owner), event).isPresent());
            assertTrue(routing.forRecipient(AccessSubject.recipient(peer), event).isEmpty());
        }
    }

    @Test void metadataWritersUseExistingMinimalScopesAndDisabledEmployeeFailsCurrentPolicy() {
        as(adminUser, () -> channels.updateChannel(channel, new UpdateChannelRequest("Renamed")));
        as(adminUser, () -> workspaces.updateWorkspace(workspace, new UpdateWorkspaceRequest("Workspace " + UUID.randomUUID())));
        as(adminUser, () -> organizations.updateOrganization(organization, new UpdateOrganizationRequest("Organization " + UUID.randomUUID())));
        as(adminUser, () -> employees.setEnabled(owner, new SetEmployeeEnabledRequest(false)));
        assertEquals(5, events().size());
        for (var event : events()) {
            assertTrue(routing.forRecipient(AccessSubject.recipient(owner), event).isEmpty());
            assertTrue(routing.forRecipient(AccessSubject.recipient(outsider), event).isEmpty());
            if (event.audience() != UiInvalidation.Audience.PERSONAL) {
                var visible = routing.forRecipient(AccessSubject.recipient(peer), event).orElseThrow();
                assertNull(visible.resource()); assertNull(visible.workspaceId());
            }
        }
    }

    @Test void unlinkRefreshesAccountScopesAndOldClientDirectoryInSameTransaction() {
        UUID client = jdbc.queryForObject("select clientid from dbo.clientaccount where clientaccountid=?", UUID.class, account);
        as(ownerUser, () -> clients.unassignClientAccount(client, account));
        assertEquals(2, events().size());
        assertEquals(Set.of(UiInvalidation.Audience.ORGANIZATION, UiInvalidation.Audience.WORKSPACE),
                new HashSet<>(events().stream().map(UiInvalidation::audience).toList()));
    }

    @Test void organizationAndDeletedScopeSignalsPassActualRelayValidationAndReachKafka() {
        as(ownerUser, () -> groups.createGroup(workspace, "Publish private group"));
        as(adminUser, () -> organizations.updateOrganization(organization, new UpdateOrganizationRequest("Publish " + UUID.randomUUID())));
        as(adminUser, () -> channels.updateChannel(channel, new UpdateChannelRequest("Publish channel")));
        UUID empty = tx.execute(status -> {
            var w = save(TestDataFactory.workspace(em.find(OrganizationEntity.class, organization))); em.flush(); return w.getId();
        });
        as(adminUser, () -> { workspaces.deleteWorkspace(empty); return null; });
        assertEquals(5, events().size());
        assertTrue(events().stream().allMatch(event -> event.resource() != null && event.resource().id() != null));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            relay.tick();
            assertEquals(5, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and status='PUBLISHED' and publishedat is not null", Integer.class, organization));
            assertEquals(0, jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and status='QUARANTINED'", Integer.class, organization));
        });
    }

    private List<UiInvalidation> events() {
        return jdbc.query("select payload::text from dbo.notificationoutbox where organizationid in (?,?) order by createdat,id",
                (rs, row) -> mapper.readValue(rs.getString(1), UiInvalidation.class), organization, foreignOrganization);
    }
    private <T> T as(UserEntity user, Supplier<T> action) {
        var principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }
}
