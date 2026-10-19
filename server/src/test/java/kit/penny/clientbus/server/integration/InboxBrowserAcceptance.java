package kit.penny.clientbus.server.integration;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in browser acceptance against real HTTP/JWT/PostgreSQL/Kafka.
 * The name deliberately does not match Surefire's default *Test patterns.
 * Run explicitly with -Dtest=InboxBrowserAcceptance (see ui/e2e/README.md).
 * No connected external channel or production database is used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxBrowserAcceptance extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwords;

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("clientbus.storage.local-path", () -> Path.of("target/inbox-acceptance-storage").toAbsolutePath().toString());
    }

    @Test
    void browserAcceptance() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (project.getFileName().toString().equals("server")) project = project.getParent();
        Path manifest = project.resolve("server/target/inbox-acceptance.json");
        Path log = project.resolve("server/target/inbox-browser.log");
        Map<String, Object> fixtures = new LinkedHashMap<>();
        String password = UUID.randomUUID().toString();
        String passwordHash = passwords.encode(password);
        for (String device : new String[]{"desktop", "mobile"}) {
            for (String scenario : new String[]{"navigation", "groups", "clients", "assignments", "concurrency", "errors", "history", "isolation", "transitions", "visibility"}) {
                Map<String, Object> fixture = new TransactionTemplate(transactionManager)
                        .execute(status -> seed(device + "-" + scenario, password, passwordHash));
                fixtures.put(device + ":" + scenario, fixture);
            }
        }
        Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of(
                "baseURL", "http://127.0.0.1:" + port, "fixtures", fixtures)));
        ProcessBuilder builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                "--config", "playwright.acceptance.config.ts");
        builder.directory(project.resolve("ui").toFile());
        builder.environment().put("PW_ACCEPTANCE_MANIFEST", manifest.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process browser = builder.start();
        try {
            assertTrue(browser.waitFor(10, TimeUnit.MINUTES), "Browser acceptance timed out; see " + log);
            assertEquals(0, browser.exitValue(), "Browser acceptance failed; see " + log);
        } finally {
            browser.descendants().forEach(ProcessHandle::destroy);
            browser.destroy();
            // Credentials belong only to this ephemeral database. Never retain them in reports.
            Files.deleteIfExists(manifest);
        }
    }

    private Map<String, Object> seed(String name, String password, String passwordHash) {
        OrganizationEntity organization = TestDataFactory.organization();
        entityManager.persist(organization);
        WorkspaceEntity workspace = TestDataFactory.workspace(organization, "Acceptance " + name);
        WorkspaceEntity hiddenWorkspace = TestDataFactory.workspace(organization, "Isolated " + name);
        entityManager.persist(workspace);
        entityManager.persist(hiddenWorkspace);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspace.getId());
        result.put("workspaceName", workspace.getName());
        result.put("hiddenWorkspaceId", hiddenWorkspace.getId());
        EmployeeEntity first = null, second = null;
        for (String role : new String[]{"first", "second", "supervisor", "admin"}) {
            String username = "pw-" + role + "-" + UUID.randomUUID().toString().replace("-", "");
            UserEntity user = new UserEntity(username, username + "@example.test", passwordHash,
                    role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE);
            entityManager.persist(user);
            EmployeeEntity employee = new EmployeeEntity(organization, user, role, "Acceptance", null);
            entityManager.persist(employee);
            if (!role.equals("admin")) {
                EmployeeWorkspaceEntity membership = new EmployeeWorkspaceEntity(employee, workspace);
                membership.setSupervisor(role.equals("supervisor"));
                entityManager.persist(membership);
            }
            result.put(role, Map.of("username", username, "password", password, "employeeId", employee.getId()));
            if (role.equals("first")) first = employee;
            if (role.equals("second")) second = employee;
        }
        WorkspaceEntity anotherWorkspace = TestDataFactory.workspace(organization, "Another " + name);
        entityManager.persist(anotherWorkspace);
        entityManager.persist(new EmployeeWorkspaceEntity(first, anotherWorkspace));
        result.put("anotherWorkspaceId", anotherWorkspace.getId());
        result.put("anotherWorkspaceName", anotherWorkspace.getName());
        OrganizationEntity foreignOrganization = TestDataFactory.organization();
        entityManager.persist(foreignOrganization);
        WorkspaceEntity foreignWorkspace = TestDataFactory.workspace(foreignOrganization);
        entityManager.persist(foreignWorkspace);
        result.put("foreignWorkspaceId", foreignWorkspace.getId());
        ChannelEntity channel = TestDataFactory.channel(workspace);
        entityManager.persist(channel);
        ChannelAccountEntity channelAccount = TestDataFactory.channelAccount(channel);
        entityManager.persist(channelAccount);
        ClientEntity client = TestDataFactory.client(organization, "Existing", name);
        entityManager.persist(client);
        result.put("clientId", client.getId());
        result.put("clientName", "Existing " + name);
        for (String kind : new String[]{"mine", "free", "other", "linked"}) {
            ClientAccountEntity account = TestDataFactory.clientAccount(kind.equals("other") || kind.equals("linked") ? client : null);
            account.setDisplayName("Contact " + kind);
            entityManager.persist(account);
            ConversationEntity conversation = TestDataFactory.conversation(workspace, channelAccount, account);
            conversation.setAssignedEmployee(kind.equals("mine") || kind.equals("linked") ? first : kind.equals("other") ? second : null);
            conversation.setLastMessageAt(Instant.now().minusSeconds(kind.equals("mine") ? 0 : 60));
            conversation.setLastMessagePreview("History " + kind);
            conversation.setHistoryStartReached(true);
            entityManager.persist(conversation);
            result.put(kind + "Id", conversation.getId());
            result.put(kind + "AccountId", account.getId());
            int count = name.endsWith("history") && kind.equals("mine") ? 125 : 1;
            for (int index = 1; index <= count; index++) {
                MessageEntity message = new MessageEntity(conversation, MessageType.TEXT, MessageDirection.INBOUND, MessageSenderType.CLIENT);
                message.setClientAccount(account);
                message.setExternalId(String.valueOf(index));
                message.setContent("History " + kind + " " + index);
                message.setSentAt(Instant.now().minusSeconds((count - index) * 60L));
                message.setProcessingStatus(MessageProcessingStatus.PROCESSED);
                entityManager.persist(message);
            }
        }
        ConversationGroupEntity group = new ConversationGroupEntity(workspace, first, "Private first");
        ConversationGroupEntity otherGroup = new ConversationGroupEntity(workspace, second, "Private second");
        entityManager.persist(group);
        entityManager.persist(otherGroup);
        result.put("groupId", group.getId());
        result.put("otherGroupId", otherGroup.getId());
        entityManager.flush();
        return result;
    }
}
