package kit.penny.clientbus.server.notification.history;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.connector.command.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=true", "clientbus.notifications.sse-enabled=false",
        "clientbus.notifications.history-submission-enabled=true", "clientbus.notifications.history-worker-poll-ms=100"})
@ActiveProfiles({"test", "history-browser"})
@Import({HistorySyncBrowserAcceptance.Control.class, HistorySyncBrowserAcceptance.Connectors.class})
class HistorySyncBrowserAcceptance extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwords;
    @Autowired Control control;
    @LocalServerPort int port;
    private <T> T save(T entity) { em.persist(entity); return entity; }

    @Test void realKafkaHistoryRecoveryDesktopAndMobile() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (project.getFileName().toString().equals("server")) project = project.getParent();
        try (var resource = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull(resource);
            assertArrayEquals(Files.readAllBytes(project.resolve("ui/dist/index.html")), resource.readAllBytes(), "Refresh UI resources before browser acceptance");
        }
        var fixtures = new LinkedHashMap<String, Object>();
        for (String device : List.of("desktop", "mobile")) for (String flow : List.of("recovery", "inline", "logout", "discovery")) {
            String name = device + "-" + flow; fixtures.put(name, fixture(name, flow.equals("inline")));
        }
        Path manifest = project.resolve("server/target/history-browser.json"), log = project.resolve("server/target/history-browser.log");
        Process browser = null;
        try {
            Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of("baseURL", "http://127.0.0.1:" + port, "fixtures", fixtures)));
            var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "--config", "playwright.history.config.ts");
            builder.directory(project.resolve("ui").toFile()); builder.environment().put("PW_HISTORY_MANIFEST", manifest.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile()); browser = builder.start();
            assertTrue(browser.waitFor(4, TimeUnit.MINUTES), "History browser timeout: " + log);
            assertEquals(0, browser.exitValue(), "History browser failure: " + log);
        } finally {
            if (browser != null) { browser.descendants().forEach(ProcessHandle::destroy); browser.destroy(); }
            Files.deleteIfExists(manifest);
            control.releaseAll();
        }
    }
    private Map<String, Object> fixture(String name, boolean fail) {
        String password = UUID.randomUUID().toString(), hash = passwords.encode(password);
        return tx.execute(status -> {
            var org = save(TestDataFactory.organization()); var w = save(TestDataFactory.workspace(org, "History " + name));
            var credentials = new LinkedHashMap<String, Object>(); EmployeeEntity owner = null;
            for (String role : List.of("employee", "admin")) {
                String username = "history-" + UUID.randomUUID();
                var user = save(new UserEntity(username, username + "@test.local", hash, role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE));
                var employee = save(new EmployeeEntity(org, user, "History", role, null));
                if (role.equals("employee")) { owner = employee; save(new EmployeeWorkspaceEntity(employee, w)); }
                credentials.put(role, Map.of("username", username, "password", password));
            }
            var channel = save(TestDataFactory.channel(w)); channel.setStatus(ChannelConnectionStatus.CONNECTED);
            var account = save(TestDataFactory.channelAccount(channel));
            var client = save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))));
            var conversation = save(TestDataFactory.conversation(w, account, client)); conversation.setAssignedEmployee(owner);
            conversation.setLastMessageAt(Instant.now()); conversation.setLastMessagePreview("History cache " + name);
            var message = new MessageEntity(conversation, MessageType.TEXT, MessageDirection.INBOUND, MessageSenderType.CLIENT);
            message.setClientAccount(client); message.setContent("History cache " + name); message.setExternalId("initial-" + name);
            message.setSentAt(Instant.now()); message.setProcessingStatus(MessageProcessingStatus.PROCESSED); save(message); em.flush();
            control.register(name, new Source(name, org.getId(), conversation.getId(), account.getId(), client.getExternalId(), fail));
            credentials.put("workspaceId", w.getId()); credentials.put("conversationId", conversation.getId()); return credentials;
        });
    }
    static class Source {
        final String name, external; final UUID organization, conversation, account;
        final Semaphore gate = new Semaphore(0); final AtomicInteger calls = new AtomicInteger(); final AtomicBoolean fail;
        Source(String name, UUID organization, UUID conversation, UUID account, String external, boolean fail) {
            this.name = name; this.organization = organization; this.conversation = conversation;
            this.account = account; this.external = external; this.fail = new AtomicBoolean(fail);
        }
    }
    @TestConfiguration @Profile("history-browser")
    static class Connectors {
        @Bean @Primary ChannelConnectorRegistry historyRegistry(Control control) { return new ChannelConnectorRegistry(List.of(control)); }
    }
    @TestComponent @Profile("history-browser") @RestController
    @RequestMapping("/api/test/history")
    static class Control implements IChannelConnector {
        final Map<String, Source> sources = new ConcurrentHashMap<>();
        @Autowired JdbcTemplate jdbc;
        public void register(String name, Source source) { sources.put(name, source); }
        public void releaseAll() { sources.values().forEach(source -> source.gate.release(100)); }
        record Release(String fixture, Boolean fail) {}
        @PostMapping("/release") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public void release(@RequestBody Release input) {
            var source = Objects.requireNonNull(sources.get(input.fixture()));
            if (input.fail() != null) source.fail.set(input.fail()); source.gate.release();
        }
        @GetMapping("/state/{name}") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> state(@PathVariable String name) {
            var source = Objects.requireNonNull(sources.get(name));
            var statuses = jdbc.queryForList("select status from dbo.historysyncoperation where conversationid=? order by createdat desc,id desc", String.class, source.conversation);
            return Map.of("calls", source.calls.get(), "operations", statuses.size(), "status", statuses.isEmpty() ? "NONE" : statuses.getFirst(),
                    "notifications", jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Long.class, source.organization));
        }
        public boolean supports(ChannelType type) { return true; }
        public SyncConversationHistoryResult handle(SyncConversationHistoryCommand command) {
            var source = sources.values().stream().filter(value -> value.account.equals(command.channelAccountId())).findFirst().orElseThrow();
            source.calls.incrementAndGet();
            try { if (!source.gate.tryAcquire(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
            if (source.fail.get()) throw new IllegalStateException("Test connector unavailable");
            var items = new ArrayList<PlatformMessageRequest>();
            for (int i = 1; i <= 2; i++) items.add(new PlatformMessageRequest(source.account, source.external, null, null,
                    "History contact", source.external, "old-" + source.name + "-" + i, MessageType.TEXT,
                    "History imported " + source.name + " " + i, null, Instant.parse("2026-10-01T10:00:00Z").plusSeconds(i), List.of(), EventOrigin.LIVE));
            return new SyncConversationHistoryResult(items, true);
        }
        public ConnectorSendResult handle(SendMessageCommand command) { throw new UnsupportedOperationException(); }
        public void handle(MarkMessagesReadCommand command) { }
        public List<PlatformConversationRequest> handle(SyncRecentChatsCommand command) { return List.of(); }
        public ClientAccountProfile handle(SyncClientAccountCommand command) { throw new UnsupportedOperationException(); }
    }
}
