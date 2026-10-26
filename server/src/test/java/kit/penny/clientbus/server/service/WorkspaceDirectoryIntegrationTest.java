package kit.penny.clientbus.server.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.jwt.JwtService;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.sse-enabled=false", "clientbus.notifications.cleanup-poll-ms=3600000"})
class WorkspaceDirectoryIntegrationTest extends AbstractIntegrationTest {
    @Autowired WorkspaceDirectoryService directory;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @Autowired JsonMapper json;
    @LocalServerPort int port;

    UUID organization, foreignOrganization, workspace, secondWorkspace, emptyWorkspace, foreignWorkspace;
    UUID admin, supervisor, worker, disabled, foreignAdmin, supervisorUser, adminUser;
    String adminToken, supervisorToken, workerToken, foreignToken;
    final Instant latest = Instant.parse("2026-10-20T10:00:00Z");

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            var org = TestDataFactory.organization(); em.persist(org);
            var other = TestDataFactory.organization(); em.persist(other);
            var first = TestDataFactory.workspace(org); em.persist(first);
            var second = TestDataFactory.workspace(org); em.persist(second);
            var empty = TestDataFactory.workspace(org); em.persist(empty);
            var outside = TestDataFactory.workspace(other); em.persist(outside);
            var administrator = employee(org, UserRole.SUPER_ADMIN);
            var manager = employee(org, UserRole.EMPLOYEE);
            var member = employee(org, UserRole.EMPLOYEE);
            var inactive = employee(org, UserRole.EMPLOYEE); inactive.getUser().setEnabled(false);
            var outsideAdmin = employee(other, UserRole.SUPER_ADMIN);
            var outsideMember = employee(other, UserRole.EMPLOYEE);
            membership(administrator, first, true);
            membership(manager, first, true); membership(manager, second, false);
            membership(member, first, false); membership(member, second, true);
            membership(inactive, first, false); membership(outsideMember, outside, true);
            var firstChannel = TestDataFactory.channel(first); em.persist(firstChannel);
            var secondChannel = TestDataFactory.channel(first); em.persist(secondChannel);
            var otherChannel = TestDataFactory.channel(second); em.persist(otherChannel);
            var outsideChannel = TestDataFactory.channel(outside); em.persist(outsideChannel);
            conversation(org, first, firstChannel, 3, latest);
            conversation(org, first, secondChannel, 7, latest.minusSeconds(3600));
            conversation(org, second, otherChannel, 2, latest.minusSeconds(7200));
            conversation(other, outside, outsideChannel, 100, latest.plusSeconds(3600));
            em.flush();
            organization = org.getId(); foreignOrganization = other.getId(); workspace = first.getId();
            secondWorkspace = second.getId(); emptyWorkspace = empty.getId(); foreignWorkspace = outside.getId();
            admin = administrator.getId(); supervisor = manager.getId(); worker = member.getId(); disabled = inactive.getId();
            foreignAdmin = outsideAdmin.getId(); supervisorUser = manager.getUser().getId(); adminUser = administrator.getUser().getId();
            adminToken = jwt.generateToken(administrator.getUser()); supervisorToken = jwt.generateToken(manager.getUser());
            workerToken = jwt.generateToken(member.getUser()); foreignToken = jwt.generateToken(outsideAdmin.getUser());
        });
    }

    @Test void organizationBulkReadIncludesEveryMembershipButNeverAnotherOrganizationsRows() {
        var memberships = directory.organizationMemberships(subject(admin), organization);
        assertEquals(6, memberships.size());
        assertEquals(Set.of(workspace, secondWorkspace), memberships.stream().map(row -> row.workspaceId()).collect(Collectors.toSet()));
        assertEquals(Set.of(admin, supervisor, worker, disabled), memberships.stream().map(row -> row.employeeId()).collect(Collectors.toSet()));
        assertTrue(memberships.stream().anyMatch(row -> row.employeeId().equals(supervisor) && row.workspaceId().equals(workspace) && row.isSupervisor()));
        assertTrue(memberships.stream().anyMatch(row -> row.employeeId().equals(supervisor) && row.workspaceId().equals(secondWorkspace) && !row.isSupervisor()));
        assertThrows(AccessDeniedException.class, () -> directory.organizationMemberships(subject(admin), foreignOrganization));
        assertThrows(AccessDeniedException.class, () -> directory.organizationMemberships(subject(supervisor), organization));
    }

    @Test void supervisorReadsOnlyManagedWorkspaceAndImmediatelyLosesReadAfterRevocation() {
        var memberships = directory.workspaceMemberships(subject(supervisor), workspace);
        assertEquals(4, memberships.size());
        assertTrue(memberships.stream().allMatch(row -> row.workspaceId().equals(workspace)));
        assertTrue(memberships.stream().anyMatch(row -> row.employeeId().equals(worker) && !row.isSupervisor()),
                "A supervisor flag from another workspace must not be exposed or applied here");
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(supervisor), secondWorkspace));
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(worker), workspace));
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(admin), foreignWorkspace));
        jdbc.update("update dbo.employeeworkspace set issupervisor=false where employeeid=? and workspaceid=?", supervisor, workspace);
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(supervisor), workspace));
        assertEquals(4, directory.workspaceMemberships(subject(admin), workspace).size());
    }

    @Test void statisticsAggregateEachRelationSeparatelyIncludeEmptyWorkspacesAndExcludeOtherOrganizations() {
        var statistics = directory.statistics(subject(admin));
        assertEquals(3, statistics.size());
        var first = statistics.stream().filter(row -> row.workspaceId().equals(workspace)).findFirst().orElseThrow();
        assertEquals(3, first.employees(), "Directory counts include disabled employees and exclude SUPER_ADMIN as before");
        assertEquals(2, first.channels()); assertEquals(2, first.conversations()); assertEquals(10, first.unread());
        assertEquals(latest, first.lastActivity());
        var second = statistics.stream().filter(row -> row.workspaceId().equals(secondWorkspace)).findFirst().orElseThrow();
        assertEquals(2, second.employees()); assertEquals(1, second.channels()); assertEquals(1, second.conversations()); assertEquals(2, second.unread());
        var empty = statistics.stream().filter(row -> row.workspaceId().equals(emptyWorkspace)).findFirst().orElseThrow();
        assertEquals(0, empty.employees()); assertEquals(0, empty.channels()); assertEquals(0, empty.conversations());
        assertEquals(0, empty.unread()); assertNull(empty.lastActivity());
        assertThrows(AccessDeniedException.class, () -> directory.statistics(subject(supervisor)));
        assertTrue(directory.statistics(subject(foreignAdmin)).stream().allMatch(row -> row.workspaceId().equals(foreignWorkspace)));
    }

    @Test void disabledExpiredAndDemotedActorsCannotReadEvenWithPreviouslyValidSubject() {
        var expired = AccessSubject.session(admin, Instant.now().minusSeconds(1));
        assertThrows(AccessDeniedException.class, () -> directory.statistics(expired));
        assertThrows(AccessDeniedException.class, () -> directory.organizationMemberships(expired, organization));
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(expired, workspace));
        jdbc.update("update dbo.users set isenabled=false where userid=?", supervisorUser);
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(supervisor), workspace));
        jdbc.update("update dbo.users set role='EMPLOYEE' where userid=?", adminUser);
        assertThrows(AccessDeniedException.class, () -> directory.statistics(subject(admin)));
        assertThrows(AccessDeniedException.class, () -> directory.organizationMemberships(subject(admin), organization));
        jdbc.update("update dbo.users set isenabled=false where userid=?", adminUser);
        assertThrows(AccessDeniedException.class, () -> directory.workspaceMemberships(subject(admin), workspace));
    }

    @Test void bulkHttpReadsAreAuthenticatedScopedAndNotCached() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var orgPath = "/api/employees/organization/" + organization + "/memberships";
            var workspacePath = "/api/workspaces/" + workspace + "/memberships";
            assertEquals(6, response(http, orgPath, adminToken, 200).size());
            assertEquals(4, response(http, workspacePath, supervisorToken, 200).size());
            assertEquals(3, response(http, "/api/workspaces/statistics", adminToken, 200).size());
            response(http, orgPath, supervisorToken, 403); response(http, orgPath, foreignToken, 403);
            response(http, workspacePath, workerToken, 403); response(http, workspacePath, foreignToken, 403);
            response(http, "/api/workspaces/statistics", supervisorToken, 403);
            for (var path : new String[]{orgPath, workspacePath, "/api/workspaces/statistics"}) response(http, path, null, 401);
        }
    }

    @Test void oneBulkHttpResponseAndAggregateHandleOverThreeHundredEmployeesWithoutTruncation() throws Exception {
        tx.executeWithoutResult(status -> {
            var org = em.find(OrganizationEntity.class, organization);
            var target = em.find(WorkspaceEntity.class, emptyWorkspace);
            for (int index = 0; index < 320; index++) membership(employee(org, UserRole.EMPLOYEE), target, index % 2 == 0);
        });
        try (var http = HttpClient.newHttpClient()) {
            var all = response(http, "/api/employees/organization/" + organization + "/memberships", adminToken, 200);
            assertEquals(326, all.size());
            var members = response(http, "/api/workspaces/" + emptyWorkspace + "/memberships", adminToken, 200);
            assertEquals(320, members.size());
            int supervisors = 0;
            for (var row : members) { assertEquals(emptyWorkspace.toString(), row.get("workspaceId").asText()); if (row.get("isSupervisor").asBoolean()) supervisors++; }
            assertEquals(160, supervisors);
            var statistics = response(http, "/api/workspaces/statistics", adminToken, 200);
            for (var row : statistics) if (row.get("workspaceId").asText().equals(emptyWorkspace.toString())) assertEquals(320, row.get("employees").asInt());
        }
    }

    private JsonNode response(HttpClient http, String path, String token, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return json.readTree(response.body());
    }

    private EmployeeEntity employee(OrganizationEntity organization, UserRole role) {
        var user = new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@test.local", "unused", role); em.persist(user);
        var employee = new EmployeeEntity(organization, user, "Directory", "Employee", null); em.persist(employee); return employee;
    }

    private void membership(EmployeeEntity employee, WorkspaceEntity workspace, boolean supervisor) {
        var membership = new EmployeeWorkspaceEntity(employee, workspace); membership.setSupervisor(supervisor); em.persist(membership);
    }

    private void conversation(OrganizationEntity organization, WorkspaceEntity workspace, ChannelEntity channel, int unread, Instant activity) {
        var account = TestDataFactory.channelAccount(channel); em.persist(account);
        var client = TestDataFactory.client(organization, "Directory", "Client"); em.persist(client);
        var clientAccount = TestDataFactory.clientAccount(client); em.persist(clientAccount);
        var conversation = TestDataFactory.conversation(workspace, account, clientAccount);
        conversation.setUnreadCount(unread); conversation.setLastMessageAt(activity); em.persist(conversation);
    }

    private AccessSubject subject(UUID employee) { return AccessSubject.session(employee, Instant.now().plusSeconds(3600)); }
}
