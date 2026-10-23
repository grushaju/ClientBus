package kit.penny.clientbus.server.notification;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.service.MessageProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-browser center acceptance against the production static bundle. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=true", "clientbus.notifications.sse-enabled=true",
        "clientbus.notifications.heartbeat-seconds=1", "clientbus.notifications.cleanup-poll-ms=3600000"})
@ActiveProfiles({"test", "notification-center-browser"})
@Import(NotificationCenterBrowserAcceptance.Control.class)
class NotificationCenterBrowserAcceptance extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwords;
    @Autowired Control control;
    private <T> T save(T entity) { em.persist(entity); return entity; }

    @Test void centerDesktopAndMobile() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (project.getFileName().toString().equals("server")) project = project.getParent();
        // Maven process-resources copies ui/dist into the classpath served by this server.
        try (var bundled = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull(bundled, "Build UI and run Maven process-resources before browser acceptance");
            assertArrayEquals(Files.readAllBytes(project.resolve("ui/dist/index.html")), bundled.readAllBytes(),
                    "Static UI is stale; run Maven process-resources before browser acceptance");
        }
        Path manifest = project.resolve("server/target/notification-center-browser.json");
        Path log = project.resolve("server/target/notification-center-browser.log");
        Map<String, Object> fixtures = new LinkedHashMap<>();
        for (String name : List.of("desktop", "mobile")) fixtures.put(name, fixture(name));
        Process browser = null;
        try {
            Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of(
                    "baseURL", "http://127.0.0.1:" + port, "fixtures", fixtures)));
            var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                    "--config", "playwright.notification-center.config.ts");
            builder.directory(project.resolve("ui").toFile());
            builder.environment().put("PW_NOTIFICATION_CENTER_MANIFEST", manifest.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            browser = builder.start();
            assertTrue(browser.waitFor(4, TimeUnit.MINUTES), "Center browser acceptance timed out; see " + log);
            assertEquals(0, browser.exitValue(), "Center browser acceptance failed; see " + log);
        } finally {
            if (browser != null) { browser.descendants().forEach(ProcessHandle::destroy); browser.destroy(); }
            Files.deleteIfExists(manifest);
        }
    }

    private Map<String, Object> fixture(String name) {
        String password = UUID.randomUUID().toString(), hash = passwords.encode(password);
        return tx.execute(status -> {
            var org = save(TestDataFactory.organization());
            var selected = save(TestDataFactory.workspace(org, "Center selected " + name));
            var target = save(TestDataFactory.workspace(org, "Center target " + name));
            Map<String, Object> values = new LinkedHashMap<>();
            EmployeeEntity owner = null, other = null;
            for (String role : List.of("allowed", "other", "admin")) {
                String username = "center-" + UUID.randomUUID();
                var user = save(new UserEntity(username, username + "@example.test", hash,
                        role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE));
                var employee = save(new EmployeeEntity(org, user, role, "Center acceptance", null));
                if (!role.equals("admin")) {
                    save(new EmployeeWorkspaceEntity(employee, selected));
                    save(new EmployeeWorkspaceEntity(employee, target));
                }
                values.put(role, Map.of("username", username, "password", password));
                if (role.equals("allowed")) owner = employee;
                if (role.equals("other")) other = employee;
            }
            var channelAccount = save(TestDataFactory.channelAccount(save(TestDataFactory.channel(target))));
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            client.setDisplayName("Center incoming contact");
            var conversation = save(TestDataFactory.conversation(target, channelAccount, client));
            conversation.setAssignedEmployee(other); conversation.setHistoryStartReached(true);
            conversation.setLastMessageAt(Instant.now()); conversation.setLastMessagePreview("Center initial message");
            var message = new MessageEntity(conversation, MessageType.TEXT, MessageDirection.INBOUND, MessageSenderType.CLIENT);
            message.setClientAccount(client); message.setContent("Center initial message");
            message.setExternalId("initial-" + name); message.setSentAt(Instant.now());
            message.setProcessingStatus(MessageProcessingStatus.PROCESSED); save(message); em.flush();
            values.put("workspaceId", selected.getId()); values.put("targetWorkspaceId", target.getId());
            values.put("conversationId", conversation.getId()); values.put("ownerId", owner.getId()); values.put("otherId", other.getId());
            control.register(name, new Source(channelAccount.getId(), client.getExternalId()));
            return values;
        });
    }

    record Source(UUID accountId, String externalClient) {}

    /** Test-source controls are never component-scanned by a production build. */
    @TestComponent
    @Profile("notification-center-browser")
    @RestController
    @RequestMapping("/api/test/notification-center")
    static class Control {
        @Autowired MessageProcessingService messages;
        private final Map<String, Source> sources = new ConcurrentHashMap<>();
        public void register(String fixture, Source source) { sources.put(fixture, source); }
        record Input(String fixture, String externalId, String content) {}
        @PostMapping("/message")
        @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> message(@RequestBody Input input) {
            var source = Objects.requireNonNull(sources.get(input.fixture()), "Unknown isolated fixture");
            var request = new PlatformMessageRequest(source.accountId(), source.externalClient(), null, null,
                    "Center incoming contact", source.externalClient(), input.externalId(), MessageType.TEXT,
                    input.content(), null, Instant.now(), List.of(), EventOrigin.LIVE);
            var result = messages.processPlatformMessage(request,
                    new NotificationSourceContext(EventOrigin.LIVE, Instant.now(), UUID.randomUUID(), null));
            return Map.of("messageId", result.id());
        }
    }
}
