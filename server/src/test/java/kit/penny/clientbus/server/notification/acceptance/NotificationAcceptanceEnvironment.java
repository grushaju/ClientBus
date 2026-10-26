package kit.penny.clientbus.server.notification.acceptance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.ServerApplication;
import kit.penny.clientbus.server.connector.*;
import kit.penny.clientbus.server.connector.command.*;
import kit.penny.clientbus.server.connector.telegram.config.TelegramClientConfiguration;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import kit.penny.clientbus.server.notification.transport.UiSseGateway;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.service.MessageProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;
import kit.penny.tdlib.TdlibAutoConfiguration;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;

/**
 * Opt-in test environment: two independent HTTP/Security/JPA/Kafka application contexts.
 * The only external systems replaced are the channel connector and the push provider.
 * Container URLs are supplied as command-line properties, never read from a user database.
 */
public final class NotificationAcceptanceEnvironment extends AbstractIntegrationTest implements AutoCloseable {
    private static final Map<String, NotificationAcceptanceEnvironment> RUNS = new ConcurrentHashMap<>();
    private final String runId = UUID.randomUUID().toString();
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[2];
    private final int[] ports = new int[2];
    private final Map<String, Map<String, Object>> fixtures = new ConcurrentHashMap<>();
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final Path storage;
    private volatile boolean kafkaPaused;

    private NotificationAcceptanceEnvironment() throws java.io.IOException {
        storage = Files.createTempDirectory(Path.of("target"), "notification-acceptance-storage-").toAbsolutePath();
    }

    public static NotificationAcceptanceEnvironment start(Class<?>... extraSources) throws Exception {
        var environment = new NotificationAcceptanceEnvironment();
        RUNS.put(environment.runId, environment);
        try {
            environment.extraSources = extraSources.clone();
            environment.startNode(0, true);
            environment.startNode(1, false);
            return environment;
        } catch (Exception | Error failure) {
            environment.close();
            throw failure;
        }
    }

    private Class<?>[] extraSources = new Class<?>[0];

    private void startNode(int index, boolean createSchema) {
        var bootSources = new ArrayList<Class<?>>(); bootSources.add(Configuration.class);
        bootSources.addAll(List.of(extraSources));
        var app = new SpringApplication(bootSources.toArray(Class<?>[]::new));
        app.setAdditionalProfiles("test", "notification-stage-acceptance", "notification-push-test");
        app.setRegisterShutdownHook(false);
        nodes[index] = app.run(
                "--server.address=127.0.0.1", "--server.port=" + ports[index],
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=" + postgres.getDriverClassName(),
                "--spring.datasource.hikari.schema=dbo",
                "--spring.jpa.hibernate.ddl-auto=" + (createSchema ? "create" : "update"),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--clientbus.kafka.outbound.enabled-channels=WHATSAPP",
                "--clientbus.storage.local-path=" + storage,
                "--clientbus.notifications.acceptance.run-id=" + runId,
                "--clientbus.notifications.acceptance.node=" + index,
                "--clientbus.notifications.gateway.instance-id=acceptance-" + runId + "-" + index,
                "--clientbus.notifications.creation-enabled=true",
                "--clientbus.notifications.sse-enabled=true",
                "--clientbus.notifications.push-enabled=true",
                "--clientbus.notifications.history-submission-enabled=true",
                "--clientbus.notifications.heartbeat-seconds=1",
                "--clientbus.notifications.gateway.probe-interval=PT0.5S",
                "--clientbus.notifications.gateway.broker-timeout=PT1S",
                "--clientbus.notifications.gateway.pipeline-stale-after=PT3S",
                "--clientbus.notifications.cleanup-poll-ms=3600000",
                "--clientbus.notifications.history-worker-poll-ms=200",
                "--clientbus.notifications.push.endpoint-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--clientbus.notifications.push.allowed-apps[0].app-id=clientbus.test",
                "--clientbus.notifications.push.allowed-apps[0].provider=FCM",
                "--clientbus.notifications.push.allowed-apps[0].environment=SANDBOX");
        ports[index] = ((WebServerApplicationContext) nodes[index]).getWebServer().getPort();
        await().atMost(Duration.ofSeconds(60)).until(() -> context(index).getBean(UiSseGateway.class).ready());
    }

    public String baseUrl(int node) { return "http://127.0.0.1:" + ports[node]; }
    public ConfigurableApplicationContext context(int node) { return Objects.requireNonNull(nodes[node], "Node is stopped"); }
    public Map<String, Map<String, Object>> fixtureMap() { return Map.copyOf(fixtures); }

    /** Existing port and instance ID are reused, but every Spring bean and HTTP connection is recreated. */
    public synchronized void restart(int node) {
        if (node < 0 || node >= nodes.length) throw new IllegalArgumentException("Unknown isolated node");
        var old = nodes[node]; nodes[node] = null;
        if (old != null) old.close();
        startNode(node, false);
    }

    public synchronized void pauseKafka() {
        if (!kafkaPaused) { kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec(); kafkaPaused = true; }
    }
    public synchronized void resumeKafka() {
        if (kafkaPaused) { kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec(); kafkaPaused = false; }
    }

    /** Each browser project/load/mobile flow owns an organization and has no shared notification counters. */
    public synchronized Map<String, Object> fixture(String name) {
        if (fixtures.containsKey(name)) return fixtures.get(name);
        String password = UUID.randomUUID().toString();
        String hash = context(0).getBean(PasswordEncoder.class).encode(password);
        Map<String, Object> values = inTransaction(em -> {
            var org = persist(em, TestDataFactory.organization());
            var workspace = persist(em, TestDataFactory.workspace(org, "Stage 11 acceptance " + name));
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, EmployeeEntity> employees = new HashMap<>();
            for (String role : List.of("owner", "other", "supervisor", "admin", "foreign")) {
                var employeeOrg = role.equals("foreign") ? persist(em, TestDataFactory.organization()) : org;
                String username = "acc-" + UUID.randomUUID();
                var user = persist(em, new UserEntity(username, username + "@test.local", hash,
                        role.equals("admin") ? UserRole.SUPER_ADMIN : UserRole.EMPLOYEE));
                var employee = persist(em, new EmployeeEntity(employeeOrg, user, role, "Acceptance", null));
                if (!role.equals("foreign")) {
                    var membership = persist(em, new EmployeeWorkspaceEntity(employee, workspace));
                    membership.setSupervisor(role.equals("supervisor"));
                } else persist(em, new EmployeeWorkspaceEntity(employee, persist(em, TestDataFactory.workspace(employeeOrg))));
                result.put(role, Map.of("username", username, "password", password));
                result.put(role + "Id", employee.getId()); employees.put(role, employee);
            }
            // Generic connector acceptance must not restore real native Telegram sessions after restart.
            var channel = persist(em, TestDataFactory.channel(workspace, ChannelType.WHATSAPP, "Acceptance connector"));
            channel.setStatus(ChannelConnectionStatus.CONNECTED);
            var channelAccount = persist(em, TestDataFactory.channelAccount(channel));
            for (String role : List.of("owner", "other")) {
                var account = persist(em, TestDataFactory.clientAccount(persist(em, TestDataFactory.client(org)),
                        channel.getType(), "acceptance-" + UUID.randomUUID()));
                account.setDisplayName("Acceptance " + role + " " + name);
                var conversation = persist(em, TestDataFactory.conversation(workspace, channelAccount, account));
                conversation.setAssignedEmployee(employees.get(role)); conversation.setHistoryStartReached(false);
                conversation.setLastMessageAt(Instant.now()); conversation.setLastMessagePreview("Initial " + name + " " + role);
                var message = new MessageEntity(conversation, MessageType.TEXT, MessageDirection.INBOUND, MessageSenderType.CLIENT);
                message.setClientAccount(account); message.setExternalId("initial-" + role + "-" + name);
                message.setContent("Initial " + name + " " + role); message.setSentAt(Instant.now());
                message.setProcessingStatus(MessageProcessingStatus.PROCESSED); persist(em, message);
                String key = role.equals("owner") ? name : name + "-other";
                sources.put(key, new Source(key, org.getId(), workspace.getId(), conversation.getId(), channelAccount.getId(), account.getExternalId()));
                result.put(role.equals("owner") ? "conversationId" : "otherConversationId", conversation.getId());
                if (role.equals("owner")) { result.put("externalClient", account.getExternalId()); result.put("clientAccountId", account.getId()); }
            }
            result.put("organizationId", org.getId()); result.put("workspaceId", workspace.getId());
            result.put("channelAccountId", channelAccount.getId()); result.put("name", name); em.flush();
            return Collections.unmodifiableMap(result);
        });
        fixtures.put(name, values); return values;
    }

    /** Seed helpers use an explicit, short persistence context; application calls still use their normal transactions. */
    public <T> T inTransaction(Function<EntityManager, T> work) {
        try (var em = context(0).getBean(EntityManagerFactory.class).createEntityManager()) {
            var transaction = em.getTransaction(); transaction.begin();
            try { T result = work.apply(em); transaction.commit(); return result; }
            catch (RuntimeException | Error failure) { if (transaction.isActive()) transaction.rollback(); throw failure; }
        }
    }
    private static <T> T persist(EntityManager em, T entity) { em.persist(entity); return entity; }

    @Override public void close() {
        resumeKafka();
        for (int index = nodes.length - 1; index >= 0; index--) {
            if (nodes[index] != null) { try { nodes[index].close(); } finally { nodes[index] = null; } }
        }
        RUNS.remove(runId);
        // Only the newly created test-storage directory is eligible for cleanup.
        if (Files.exists(storage)) try (var paths = Files.walk(storage)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private record Source(String name, UUID organization, UUID workspace, UUID conversation, UUID account, String external) {}

    // Load the production package and the same auto-configuration exclusions as ServerApplication.
    // Plain SpringApplication has no test-runner TypeExcludeFilter; exclude unrelated test fixtures explicitly.
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("notification-stage-acceptance")
    @EnableScheduling
    @EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class, TdlibAutoConfiguration.class})
    @AutoConfigurationPackage(basePackages = "kit.penny.clientbus.server")
    @ConfigurationPropertiesScan(basePackages = "kit.penny.clientbus.server")
    @ComponentScan(basePackages = "kit.penny.clientbus.server", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {TestConfiguration.class, TestComponent.class}),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {ServerApplication.class, TelegramClientConfiguration.class})
    })
    @Import(Control.class)
    public static class Configuration {
        @Bean(destroyMethod = "") NotificationAcceptanceEnvironment acceptanceEnvironment(@Value("${clientbus.notifications.acceptance.run-id}") String id) {
            return Objects.requireNonNull(RUNS.get(id), "No isolated acceptance environment");
        }
        @Bean @Primary ChannelConnectorRegistry acceptanceConnectors(Control control) { return new ChannelConnectorRegistry(List.of(control)); }
    }

    /** These endpoints exist only in test sources and require the isolated fixture administrator. */
    @TestComponent
    @RestController
    @Profile("notification-stage-acceptance")
    @RequestMapping("/api/test/notification-acceptance")
    public static class Control implements IChannelConnector {
        @Autowired NotificationAcceptanceEnvironment environment;
        @Autowired MessageProcessingService messages;
        @Autowired IPlatformMessagePublisher publisher;
        @Autowired JdbcTemplate jdbc;
        @Value("${clientbus.notifications.acceptance.node}") int node;
        public record MessageInput(String fixture, String externalId, String content, Boolean viaKafka) {}
        @PostMapping("/message") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> message(@RequestBody MessageInput input) {
            var source = Objects.requireNonNull(environment.sources.get(input.fixture()), "Unknown isolated fixture");
            String external = input.externalId() == null ? UUID.randomUUID().toString() : input.externalId();
            var request = new PlatformMessageRequest(source.account(), source.external(), null, null, "Acceptance contact", source.external(),
                    external, MessageType.TEXT, input.content(), null, Instant.now(), List.of(), EventOrigin.LIVE);
            if (Boolean.TRUE.equals(input.viaKafka())) {
                publisher.publish(request);
                return Map.of("accepted", true, "publishedAt", Instant.now(), "externalId", external);
            }
            var message = messages.processPlatformMessage(request,
                    new NotificationSourceContext(EventOrigin.LIVE, Instant.now(), UUID.randomUUID(), null));
            // The proxied service transaction has committed before this response is constructed.
            return Map.of("messageId", message.id(), "committedAt", Instant.now(), "externalId", external);
        }
        @GetMapping("/stats/{fixture}") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> stats(@PathVariable String fixture) {
            var source = Objects.requireNonNull(environment.sources.get(fixture), "Unknown isolated fixture");
            var stats = new LinkedHashMap<String, Object>();
            stats.put("messages", jdbc.queryForObject("select count(*) from dbo.message where conversationid=?", Long.class, source.conversation()));
            stats.put("notifications", jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Long.class, source.organization()));
            stats.put("deliveries", jdbc.queryForObject("select count(*) from dbo.notificationdelivery d join dbo.notification n on n.id=d.notificationid where n.organizationid=?", Long.class, source.organization()));
            stats.put("acceptedDeliveries", jdbc.queryForObject("select count(*) from dbo.notificationdelivery d join dbo.notification n on n.id=d.notificationid where n.organizationid=? and d.status='PROVIDER_ACCEPTED'", Long.class, source.organization()));
            stats.put("outboxPending", jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=? and status in ('PENDING','RETRY','IN_FLIGHT')", Long.class, source.organization()));
            stats.put("history", jdbc.queryForList("select id,status from dbo.historysyncoperation where conversationid=? order by createdat,id", source.conversation()));
            stats.put("gateways", java.util.stream.IntStream.range(0, 2).mapToObj(index -> {
                var context = environment.nodes[index];
                return context == null ? Map.<String, Object>of("ready", false) : Map.<String, Object>of(
                        "ready", context.getBean(UiSseGateway.class).ready(), "connections", context.getBean(UiSseGateway.class).connectionCount(),
                        "group", context.getBean(UiKafkaSubscriber.class).groupId());
            }).toList());
            return stats;
        }
        public record KafkaInput(boolean paused) {}
        @PostMapping("/kafka") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> kafka(@RequestBody KafkaInput input) {
            if (input.paused()) environment.pauseKafka(); else environment.resumeKafka();
            return Map.of("paused", input.paused());
        }
        @PostMapping("/restart/{target}") @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Map<String, Object> restart(@PathVariable int target) {
            if (target == node) throw new IllegalArgumentException("Restart through the surviving HTTP node");
            environment.restart(target); return Map.of("restarted", target, "baseURL", environment.baseUrl(target));
        }
        public boolean supports(ChannelType type) { return true; }
        public SyncConversationHistoryResult handle(SyncConversationHistoryCommand command) {
            var source = environment.sources.values().stream().filter(value -> value.account().equals(command.channelAccountId())
                    && value.external().equals(command.conversationExternalId())).findFirst().orElseThrow();
            var message = new PlatformMessageRequest(source.account(), source.external(), null, null, "Acceptance contact", source.external(),
                    "history-" + source.name(), MessageType.TEXT, "Acceptance imported " + source.name(), null,
                    Instant.parse("2026-10-01T10:00:00Z"), List.of(), EventOrigin.HISTORY_IMPORT);
            return new SyncConversationHistoryResult(List.of(message), true);
        }
        public ConnectorSendResult handle(SendMessageCommand command) { throw new UnsupportedOperationException("No external send in acceptance"); }
        public void handle(MarkMessagesReadCommand command) { }
        public List<PlatformConversationRequest> handle(SyncRecentChatsCommand command) { return List.of(); }
        public ClientAccountProfile handle(SyncClientAccountCommand command) { throw new UnsupportedOperationException(); }
    }
}
