package kit.penny.clientbus.server.integration;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.server.connector.command.ReadClientAccountAvatarCommand;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in browser acceptance with real HTTP, relational persistence and local object storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=false", "clientbus.notifications.sse-enabled=false",
        "clientbus.notifications.history-consumer-enabled=false"
})
@ActiveProfiles({"test", "new-conversation-avatar-browser"})
@Import(NewConversationAvatarBrowserAcceptance.Control.class)
class NewConversationAvatarBrowserAcceptance extends AbstractIntegrationTest {
    private static final Path STORAGE = projectRoot().resolve("server/target/new-conversation-avatar-storage-" + UUID.randomUUID());
    private static final byte[] PREVIEW_PHOTO = png(0xffaaccdd, 4);
    private static final byte[] FULL_PHOTO = png(0xff245588, 32);
    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwords;
    @Autowired Control control;
    @MockitoBean ChannelConnectorRegistry connectors;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("clientbus.storage.local-path", STORAGE::toString);
    }

    @Test
    void previewCancelAndPersistedFullAvatarOnDesktopAndMobile() throws Exception {
        Path project = projectRoot();
        try (var bundled = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull(bundled, "Build UI and run Maven process-resources before browser acceptance");
            assertArrayEquals(Files.readAllBytes(project.resolve("ui/dist/index.html")), bundled.readAllBytes(),
                    "Static UI is stale; run Maven process-resources before browser acceptance");
        }
        var connector = mock(IChannelConnector.class);
        when(connectors.getConnector(ChannelType.TELEGRAM)).thenReturn(connector);
        when(connector.handle(any(DiscoverClientAccountCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            DiscoverClientAccountCommand command = call.getArgument(0);
            var contact = control.contact(command.channelAccountId());
            assertTrue(Objects.equals(contact.phone(), command.phone()) || Objects.equals(contact.username(), command.username()));
            return new DiscoveredClientAccount(contact.externalId(), contact.username(), contact.phone(), contact.name(),
                    new ClientAccountAvatarPreview("image/png", Base64.getEncoder().encodeToString(PREVIEW_PHOTO)));
        });
        when(connector.handle(any(ReadClientAccountAvatarCommand.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ReadClientAccountAvatarCommand command = call.getArgument(0);
            assertEquals(control.contact(command.channelAccountId()).externalId(), command.externalId());
            return Optional.of(new ClientAccountAvatar(FULL_PHOTO, "image/png", "full-browser-photo"));
        });
        Map<String, Object> fixtures = new LinkedHashMap<>();
        for (String name : List.of("desktop", "mobile")) fixtures.put(name, fixture(name));
        Path manifest = project.resolve("server/target/new-conversation-avatar-browser.json");
        Path log = project.resolve("server/target/new-conversation-avatar-browser.log");
        Process browser = null;
        try {
            Files.writeString(manifest, new JsonMapper().writeValueAsString(Map.of(
                    "baseURL", "http://127.0.0.1:" + port, "fixtures", fixtures,
                    "fullPhotoBase64", Base64.getEncoder().encodeToString(FULL_PHOTO),
                    "previewPhotoBase64", Base64.getEncoder().encodeToString(PREVIEW_PHOTO))));
            var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                    "--config", "playwright.new-conversation-avatar.config.ts");
            builder.directory(project.resolve("ui").toFile());
            builder.environment().put("PW_NEW_CONVERSATION_AVATAR_MANIFEST", manifest.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            browser = builder.start();
            assertTrue(browser.waitFor(4, TimeUnit.MINUTES), "New-conversation avatar browser acceptance timed out; see " + log);
            assertEquals(0, browser.exitValue(), "New-conversation avatar browser acceptance failed; see " + log);
        } finally {
            if (browser != null) { browser.descendants().forEach(ProcessHandle::destroy); browser.destroy(); }
            Files.deleteIfExists(manifest);
        }
    }

    private Map<String, Object> fixture(String name) {
        String password = UUID.randomUUID().toString(), hash = passwords.encode(password);
        return tx.execute(status -> {
            var org = save(TestDataFactory.organization());
            var workspace = save(TestDataFactory.workspace(org, "Avatar acceptance " + name));
            Map<String, Object> values = new LinkedHashMap<>();
            for (var role : List.of(UserRole.EMPLOYEE, UserRole.SUPER_ADMIN)) {
                String username = "av-" + UUID.randomUUID();
                var user = save(new UserEntity(username, username + "@example.test", hash, role));
                var employee = save(new EmployeeEntity(org, user, "Avatar", "Acceptance", null));
                if (role == UserRole.EMPLOYEE) save(new EmployeeWorkspaceEntity(employee, workspace));
                values.put(role == UserRole.EMPLOYEE ? "employee" : "admin", Map.of("username", username, "password", password));
            }
            var channel = TestDataFactory.channel(workspace, ChannelType.TELEGRAM, "Avatar Telegram " + name);
            channel.setStatus(ChannelConnectionStatus.CONNECTED);
            save(channel);
            var account = save(TestDataFactory.channelAccount(channel));
            var contact = new Contact(account.getId(), UUID.randomUUID().toString(), "avatar_" + name,
                    name.equals("desktop") ? "+79991110001" : "+79991110002", "Avatar Contact " + name);
            control.register(name, contact);
            em.flush();
            values.put("workspaceId", workspace.getId());
            values.put("channelAccountId", account.getId());
            values.put("phone", contact.phone());
            values.put("username", contact.username());
            values.put("externalId", contact.externalId());
            values.put("displayName", contact.name());
            return values;
        });
    }

    private <T> T save(T entity) { em.persist(entity); return entity; }

    private static Path projectRoot() {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return project.getFileName().toString().equals("server") ? project.getParent() : project;
    }

    private static byte[] png(int color, int dimension) {
        try {
            BufferedImage image = new BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < dimension; y++) for (int x = 0; x < dimension; x++) image.setRGB(x, y, color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    record Contact(UUID channelAccountId, String externalId, String username, String phone, String name) {}

    @TestComponent
    @Profile("new-conversation-avatar-browser")
    @RestController
    @RequestMapping("/api/test/new-conversation-avatar")
    static class Control {
        @Autowired EntityManager em;
        @Autowired TransactionTemplate tx;
        private final Map<String, Contact> contacts = new ConcurrentHashMap<>();
        void register(String name, Contact contact) { contacts.put(name, contact); }
        Contact contact(UUID channelAccountId) {
            return contacts.values().stream().filter(c -> c.channelAccountId().equals(channelAccountId)).findFirst().orElseThrow();
        }

        @GetMapping("/{name}")
        @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> state(@PathVariable String name) throws Exception {
            Contact contact = Objects.requireNonNull(contacts.get(name), "Unknown isolated fixture");
            Map<String, Object> values = tx.execute(status -> {
                var accounts = em.createQuery("select a from ClientAccountEntity a where a.channelType = :type and a.externalId = :id", ClientAccountEntity.class)
                        .setParameter("type", ChannelType.TELEGRAM).setParameter("id", contact.externalId()).getResultList();
                long conversations = em.createQuery("select count(c) from ConversationEntity c where c.clientAccount.externalId = :id and c.clientAccount.channelType = :type", Long.class)
                        .setParameter("type", ChannelType.TELEGRAM).setParameter("id", contact.externalId()).getSingleResult();
                var account = accounts.isEmpty() ? null : accounts.getFirst();
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("accounts", accounts.size());
                state.put("conversations", conversations);
                state.put("accountId", account == null ? "" : account.getId().toString());
                state.put("avatarKey", account == null || account.getAvatarUrl() == null ? "" : account.getAvatarUrl());
                return state;
            });
            long files = 0;
            if (Files.exists(STORAGE)) try (var paths = Files.walk(STORAGE)) { files = paths.filter(Files::isRegularFile).count(); }
            values.put("storageObjects", files);
            return values;
        }
    }
}
