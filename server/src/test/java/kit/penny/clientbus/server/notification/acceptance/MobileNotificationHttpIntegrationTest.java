package kit.penny.clientbus.server.notification.acceptance;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.dto.notification.NotificationDto;
import kit.penny.clientbus.common.dto.notification.PushInstallationDto;
import kit.penny.clientbus.common.dto.notification.PushInstallationRequest;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.notification.center.PersonalNotificationWriter;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.notification.contract.PushProvider;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP/native boundary plus the real scheduled worker; no manual worker claim or deliver calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.creation-enabled=true", "clientbus.notifications.push-enabled=true",
        "clientbus.notifications.sse-enabled=false", "clientbus.notifications.push-poll-ms=50",
        "clientbus.notifications.cleanup-poll-ms=3600000",
        "clientbus.notifications.push.endpoint-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "clientbus.notifications.push.allowed-apps[0].app-id=clientbus.test",
        "clientbus.notifications.push.allowed-apps[0].provider=FCM",
        "clientbus.notifications.push.allowed-apps[0].environment=SANDBOX",
        "clientbus.notifications.push.provider-timeout=PT2S"})
@Import(MobileNotificationHttpIntegrationTest.ProviderConfiguration.class)
class MobileNotificationHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PROOF = "a".repeat(43);
    private static final String WRONG_PROOF = "b".repeat(43);
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PersonalNotificationWriter writer;
    @Autowired JwtService jwt;
    @Autowired JsonMapper mapper;
    @Autowired ControlledProvider provider;
    @LocalServerPort int port;

    UUID organization, workspace, conversation, channelAccount, owner, other;
    String ownerToken, otherToken;

    @BeforeEach void fixture() {
        tx.executeWithoutResult(status -> {
            var org = save(TestDataFactory.organization());
            var w = save(TestDataFactory.workspace(org));
            var user = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@test.local", "unused", UserRole.EMPLOYEE));
            var employee = save(new EmployeeEntity(org, user, "Mobile", "Owner", null));
            save(new EmployeeWorkspaceEntity(employee, w));
            var foreignOrg = save(TestDataFactory.organization());
            var foreignUser = save(new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@test.local", "unused", UserRole.EMPLOYEE));
            var foreignEmployee = save(new EmployeeEntity(foreignOrg, foreignUser, "Other", "Account", null));
            var channel = save(TestDataFactory.channel(w));
            var account = save(TestDataFactory.channelAccount(channel));
            var c = save(TestDataFactory.conversation(w, account, save(TestDataFactory.clientAccount(save(TestDataFactory.client(org))))));
            c.setAssignedEmployee(employee);
            em.flush();
            organization = org.getId(); workspace = w.getId(); conversation = c.getId(); channelAccount = account.getId();
            owner = employee.getId(); other = foreignEmployee.getId(); ownerToken = jwt.generateToken(user);
            otherToken = jwt.generateToken(foreignUser);
        });
    }

    @Test void twoHttpInstallationsReceiveOneNotificationAndShareRestReadState() throws Exception {
        try (var first = HttpClient.newHttpClient(); var second = HttpClient.newHttpClient()) {
            var android = UUID.randomUUID(); var ios = UUID.randomUUID();
            String androidEndpoint = "android-" + android, iosEndpoint = "ios-" + ios;
            register(first, ownerToken, android, 0, androidEndpoint, PROOF, PushPlatform.ANDROID, 200);
            register(second, ownerToken, ios, 0, iosEndpoint, PROOF, PushPlatform.IOS, 200);
            String external = "mobile-live-" + UUID.randomUUID();
            UUID id = incoming(external);
            incoming(external); // Replay the source, rather than writing a second notification by hand.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(2, countDeliveries(id));
                assertEquals(2, jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=? and status='PROVIDER_ACCEPTED'", Integer.class, id));
            });
            assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notification where organizationid=?", Integer.class, organization));
            assertEquals(Set.of(android, ios), new HashSet<>(jdbc.queryForList(
                    "select registrationid from dbo.notificationdelivery where notificationid=?", UUID.class, id)));
            for (String endpoint : List.of(androidEndpoint, iosEndpoint)) {
                var attempts = provider.attempts(endpoint);
                assertEquals(1, attempts.size());
                var request = attempts.getFirst().request();
                assertEquals(id, request.notificationId());
                assertEquals(1, request.schemaVersion());
                assertFalse(request.remainingTtl().isNegative() || request.remainingTtl().isZero());
                assertTrue(request.remainingTtl().compareTo(Duration.ofMinutes(5)) <= 0);
                assertFalse(attempts.getFirst().transactionActive(), "Provider I/O must remain outside a DB transaction");
                assertFalse(request.toString().contains(endpoint), "Diagnostics must redact the provider endpoint");
            }
            var beforeFirst = detail(first, ownerToken, id, 200);
            var beforeSecond = detail(second, ownerToken, id, 200);
            assertEquals(beforeFirst.id(), beforeSecond.id());
            assertNull(beforeFirst.readAt()); assertNull(beforeSecond.readAt());
            var response = first.send(request("/api/notifications/" + id + "/read", ownerToken)
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(response, 200);
            var read = mapper.readValue(response.body(), NotificationDto.class);
            assertNotNull(read.readAt());
            assertEquals(read.readAt(), detail(second, ownerToken, id, 200).readAt());
            assertEquals(2, countDeliveries(id), "Reading must not create a second delivery intent");
            assertEquals(2, jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=? and status='PROVIDER_ACCEPTED'", Integer.class, id));
            detail(second, otherToken, id, 404);
        }
    }

    @Test void httpProofVersionsRotationRebindAndLateLogoutRetainTheCurrentOwner() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID installation = UUID.randomUUID();
            var initial = register(http, ownerToken, installation, 0, "initial-" + installation, PROOF, PushPlatform.ANDROID, 200);
            assertEquals(1, initial.registrationVersion());
            register(http, otherToken, installation, 1, "stolen-" + installation, WRONG_PROOF, PushPlatform.ANDROID, 403);
            String rotatedEndpoint = "rotated-" + installation;
            provider.plan(rotatedEndpoint, attempt -> new PushProvider.Result(PushProvider.Outcome.RETRYABLE, null, Duration.ofSeconds(3)));
            var rotated = register(http, ownerToken, installation, 1, rotatedEndpoint, PROOF, PushPlatform.ANDROID, 200);
            assertEquals(2, rotated.registrationVersion()); assertEquals(2, rotated.endpointVersion());
            assertEquals(1, rotated.bindingVersion());
            UUID notification = incoming("before-account-change-" + UUID.randomUUID());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals("RETRY", state(notification)));
            var rebound = register(http, otherToken, installation, 2, "rebound-" + installation, PROOF, PushPlatform.ANDROID, 200);
            assertEquals(3, rebound.registrationVersion()); assertEquals(2, rebound.bindingVersion());
            assertEquals("CANCELLED", state(notification));
            assertNull(detail(http, ownerToken, notification, 200).readAt());
            detail(http, otherToken, notification, 404);
            var oldLogout = http.send(request("/api/push/installations/" + installation, ownerToken)
                    .header("X-Installation-Secret", PROOF).header("If-Match", "\"2\"").DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(oldLogout, 403);
            var staleLogout = http.send(request("/api/push/installations/" + installation, otherToken)
                    .header("X-Installation-Secret", PROOF).header("If-Match", "\"2\"").DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(staleLogout, 409);
            assertEquals("\"3\"", staleLogout.headers().firstValue("ETag").orElseThrow());
            assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?", Boolean.class, installation));
            assertEquals(other, jdbc.queryForObject("select recipientemployeeid from dbo.pushinstallation where id=?", UUID.class, installation));
            var logout = http.send(request("/api/push/installations/" + installation, otherToken)
                    .header("X-Installation-Secret", PROOF).header("If-Match", "\"3\"").DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(logout, 204);
            assertFalse(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?", Boolean.class, installation));
        }
    }

    @Test void scheduledRetryHonorsProviderRateLimitAndKeepsTheOriginalTtlAndLogicalDelivery() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID installation = UUID.randomUUID(); String endpoint = "rate-limit-" + installation;
            provider.plan(endpoint, attempt -> attempt.number() == 1
                    ? new PushProvider.Result(PushProvider.Outcome.RETRYABLE, null, Duration.ofSeconds(2)) : accepted());
            register(http, ownerToken, installation, 0, endpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID id = incoming("rate-limit-" + UUID.randomUUID());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals("RETRY", state(id)));
            Instant expiry = expiry(id);
            assertEquals("PROVIDER_RETRY", jdbc.queryForObject("select errorcode from dbo.notificationdelivery where notificationid=?", String.class, id));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("PROVIDER_ACCEPTED", state(id)));
            var attempts = provider.attempts(endpoint);
            assertEquals(2, attempts.size()); assertEquals(1, countDeliveries(id));
            assertTrue(Duration.between(attempts.getFirst().at(), attempts.getLast().at()).compareTo(Duration.ofSeconds(2)) >= 0,
                    "Provider Retry-After must remain a minimum delay");
            assertTrue(attempts.getLast().request().remainingTtl().compareTo(attempts.getFirst().request().remainingTtl()) < 0);
            assertEquals(expiry, expiry(id), "Retry must not extend the original alert expiry");
            assertNull(detail(http, ownerToken, id, 200).readAt());
        }
    }

    @Test void scheduledProviderTimeoutRetriesAndProviderHintBeyondTtlExpiresWithoutAnotherCall() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID timedOutInstallation = UUID.randomUUID(); String timedOutEndpoint = "timeout-" + timedOutInstallation;
            var interrupted = new CountDownLatch(1);
            provider.plan(timedOutEndpoint, attempt -> {
                if (attempt.number() == 1) {
                    try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                }
                return accepted();
            });
            register(http, ownerToken, timedOutInstallation, 0, timedOutEndpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID timeoutId = incoming("timeout-" + UUID.randomUUID());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Scheduled worker must cancel the timed-out provider attempt");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("RETRY", state(timeoutId));
                assertEquals("PROVIDER_TIMEOUT", jdbc.queryForObject("select errorcode from dbo.notificationdelivery where notificationid=?", String.class, timeoutId));
            });
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("PROVIDER_ACCEPTED", state(timeoutId)));
            assertEquals(2, provider.attempts(timedOutEndpoint).size()); assertEquals(1, countDeliveries(timeoutId));

            // Disable that installation through the real REST API before checking a new device.
            assertResponse(http.send(request("/api/push/installations/" + timedOutInstallation, ownerToken)
                    .header("X-Installation-Secret", PROOF).header("If-Match", "\"1\"").DELETE().build(), HttpResponse.BodyHandlers.ofString()), 204);
            UUID ttlInstallation = UUID.randomUUID(); String ttlEndpoint = "ttl-" + ttlInstallation;
            provider.plan(ttlEndpoint, attempt -> new PushProvider.Result(PushProvider.Outcome.RETRYABLE, null, Duration.ofDays(1)));
            register(http, ownerToken, ttlInstallation, 0, ttlEndpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID ttlId = incoming("ttl-" + UUID.randomUUID());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("EXPIRED", state(ttlId)));
            assertEquals(1, provider.attempts(ttlEndpoint).size());
            assertEquals(1, countDeliveries(ttlId)); assertNull(detail(http, ownerToken, ttlId, 200).readAt());
            assertNull(detail(http, ownerToken, timeoutId, 200).readAt());
        }
    }

    @Test void scheduledRetryRechecksCurrentAclBeforeAnotherProviderCall() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID installation = UUID.randomUUID(); String endpoint = "acl-" + installation;
            provider.plan(endpoint, attempt -> new PushProvider.Result(PushProvider.Outcome.RETRYABLE, null, Duration.ofSeconds(3)));
            register(http, ownerToken, installation, 0, endpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID id = incoming("acl-" + UUID.randomUUID());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals("RETRY", state(id)));
            jdbc.update("delete from dbo.employeeworkspace where employeeid=? and workspaceid=?", owner, workspace);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("CANCELLED", state(id)));
            assertEquals(1, provider.attempts(endpoint).size(), "Access revoked before retry must block further provider I/O");
            detail(http, ownerToken, id, 404);
            assertNull(jdbc.queryForObject("select readat from dbo.notification where id=?", Timestamp.class, id));
        }
    }

    @Test void httpRevocationCancelsPendingRetryWithoutReadingTheNotification() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID installation = UUID.randomUUID(); String endpoint = "logout-" + installation;
            provider.plan(endpoint, attempt -> new PushProvider.Result(PushProvider.Outcome.RETRYABLE, null, Duration.ofSeconds(3)));
            register(http, ownerToken, installation, 0, endpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID id = incoming("logout-" + UUID.randomUUID());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals("RETRY", state(id)));
            assertResponse(http.send(request("/api/push/installations/" + installation, ownerToken)
                    .header("X-Installation-Secret", PROOF).header("If-Match", "\"1\"").DELETE().build(), HttpResponse.BodyHandlers.ofString()), 204);
            assertEquals("CANCELLED", state(id));
            assertFalse(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?", Boolean.class, installation));
            assertEquals(1, provider.attempts(endpoint).size());
            assertNull(detail(http, ownerToken, id, 200).readAt());
        }
    }

    @Test void scheduledInvalidEndpointResponseFromBeforeHttpRotationDoesNotDisableTheNewEndpoint() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID installation = UUID.randomUUID(); String oldEndpoint = "old-" + installation, nextEndpoint = "next-" + installation;
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            provider.plan(oldEndpoint, attempt -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("HTTP rotation did not release the provider call");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return new PushProvider.Result(PushProvider.Outcome.INVALID_ENDPOINT, null, null);
            });
            register(http, ownerToken, installation, 0, oldEndpoint, PROOF, PushPlatform.ANDROID, 200);
            UUID id = incoming("rotate-in-flight-" + UUID.randomUUID());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Scheduled worker did not enter the old endpoint call");
                var rotated = register(http, ownerToken, installation, 1, nextEndpoint, PROOF, PushPlatform.ANDROID, 200);
                assertEquals(2, rotated.endpointVersion());
            } finally { release.countDown(); }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("PROVIDER_ACCEPTED", state(id)));
            assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?", Boolean.class, installation));
            assertEquals(2, jdbc.queryForObject("select registrationversion from dbo.pushinstallation where id=?", Long.class, installation));
            assertEquals(1, countDeliveries(id));
            assertEquals(1, provider.attempts(oldEndpoint).size()); assertEquals(1, provider.attempts(nextEndpoint).size());
            assertEquals(id, provider.attempts(nextEndpoint).getFirst().request().notificationId());
        }
    }

    private <T> T save(T entity) { em.persist(entity); return entity; }
    private UUID incoming(String external) {
        tx.executeWithoutResult(status -> writer.incomingMessage(conversation, channelAccount, external,
                new NotificationSourceContext(EventOrigin.LIVE, Instant.now(), UUID.randomUUID(), null)));
        return jdbc.queryForObject("select id from dbo.notification where organizationid=? order by createdat desc limit 1", UUID.class, organization);
    }
    private int countDeliveries(UUID id) {
        return jdbc.queryForObject("select count(*) from dbo.notificationdelivery where notificationid=?", Integer.class, id);
    }
    private String state(UUID id) {
        return jdbc.queryForObject("select status from dbo.notificationdelivery where notificationid=?", String.class, id);
    }
    private Instant expiry(UUID id) {
        return jdbc.queryForObject("select expiresat from dbo.notificationdelivery where notificationid=?", Timestamp.class, id).toInstant();
    }
    private PushInstallationDto register(HttpClient http, String credential, UUID id, long version, String endpoint,
            String proof, PushPlatform platform, int expectedStatus) throws Exception {
        var body = new PushInstallationRequest(version, platform, PushProviderType.FCM, "clientbus.test",
                PushEnvironment.SANDBOX, endpoint, proof, true, PushPermission.GRANTED);
        var response = http.send(request("/api/push/installations/" + id, credential).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertResponse(response, expectedStatus);
        assertFalse(response.body().contains(endpoint)); assertFalse(response.body().contains(proof));
        if (expectedStatus != 200) return null;
        var result = mapper.readValue(response.body(), PushInstallationDto.class);
        assertEquals("\"" + result.registrationVersion() + "\"", response.headers().firstValue("ETag").orElseThrow());
        return result;
    }
    private NotificationDto detail(HttpClient http, String credential, UUID id, int expectedStatus) throws Exception {
        var response = http.send(request("/api/notifications/" + id, credential).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertResponse(response, expectedStatus);
        return expectedStatus == 200 ? mapper.readValue(response.body(), NotificationDto.class) : null;
    }
    private HttpRequest.Builder request(String path, String credential) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + credential);
    }
    private void assertResponse(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    private static PushProvider.Result accepted() { return new PushProvider.Result(PushProvider.Outcome.ACCEPTED, "provider-accepted", null); }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean ControlledProvider mobileAcceptanceProvider() { return new ControlledProvider(); }
    }
    static class ControlledProvider implements PushProvider {
        record Attempt(Request request, int number, Instant at, boolean transactionActive) {}
        private final Map<String, Function<Attempt, Result>> plans = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
        private final Map<String, List<Attempt>> captured = new ConcurrentHashMap<>();
        void plan(String endpoint, Function<Attempt, Result> action) { plans.put(endpoint, action); }
        List<Attempt> attempts(String endpoint) { return List.copyOf(captured.getOrDefault(endpoint, List.of())); }
        @Override public PushProviderType provider() { return PushProviderType.FCM; }
        @Override public Result send(Request request) {
            var endpoint = request.registrationReference();
            var attempt = new Attempt(request, sequences.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet(),
                    Instant.now(), TransactionSynchronizationManager.isActualTransactionActive());
            captured.computeIfAbsent(endpoint, ignored -> new CopyOnWriteArrayList<>()).add(attempt);
            return plans.getOrDefault(endpoint, ignored -> accepted()).apply(attempt);
        }
    }
}
