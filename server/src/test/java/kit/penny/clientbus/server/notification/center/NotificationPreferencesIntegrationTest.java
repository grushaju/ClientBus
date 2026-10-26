package kit.penny.clientbus.server.notification.center;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.security.jwt.JwtService;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clientbus.notifications.sse-enabled=false", "clientbus.notifications.cleanup-poll-ms=3600000"})
class NotificationPreferencesIntegrationTest extends AbstractIntegrationTest {
    @Autowired NotificationPreferencesService preferences;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @LocalServerPort int port;

    UUID organization, owner, other, admin, ownerUser;
    String ownerToken, otherToken, adminToken;

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            var org = TestDataFactory.organization();
            em.persist(org);
            var user = user(UserRole.EMPLOYEE);
            var employee = new EmployeeEntity(org, user, "First", "Last", null);
            em.persist(employee);
            var secondUser = user(UserRole.EMPLOYEE);
            var second = new EmployeeEntity(org, secondUser, "Second", "Employee", null);
            em.persist(second);
            var adminUser = user(UserRole.SUPER_ADMIN);
            var administrator = new EmployeeEntity(org, adminUser, "Admin", "User", null);
            em.persist(administrator);
            em.flush();
            organization = org.getId(); owner = employee.getId(); other = second.getId(); admin = administrator.getId();
            ownerUser = user.getId(); ownerToken = jwt.generateToken(user); otherToken = jwt.generateToken(secondUser);
            adminToken = jwt.generateToken(adminUser);
        });
    }

    private UserEntity user(UserRole role) {
        var user = new UserEntity(UUID.randomUUID().toString(), UUID.randomUUID() + "@test.local", "unused", role);
        em.persist(user);
        return user;
    }

    @Test void newAndLegacyEmployeesDefaultToEnabledWithoutWritingOnRead() {
        assertTrue(new EmployeeEntity().isNotificationSoundEnabled());
        assertEquals(Boolean.TRUE, stored(owner));
        assertTrue(preferences.get(subject(owner)).soundEnabled());
        jdbc.update("update dbo.employee set notificationsoundenabled=null where employeeid=?", owner);
        assertTrue(preferences.get(subject(owner)).soundEnabled());
        assertTrue(Boolean.TRUE.equals(tx.execute(status -> em.find(EmployeeEntity.class, owner).isNotificationSoundEnabled())));
        assertNull(stored(owner), "Reading a legacy row must not trigger a write or backfill");
        assertEquals(0, events());
    }

    @Test void changingPreferencePersistsOnlyTheOwnerAndEmitsOnePersonalInvalidationPerEffectiveChange() {
        assertFalse(preferences.update(subject(owner), false).soundEnabled());
        assertEquals(Boolean.FALSE, stored(owner));
        assertFalse(preferences.get(subject(owner)).soundEnabled());
        assertEquals(Boolean.TRUE, stored(other));
        assertEquals(1, events());
        var event = jdbc.queryForObject("select payload::text from dbo.notificationoutbox where organizationid=?",
                String.class, organization);
        assertNotNull(event);
        assertTrue(event.contains("notifications.invalidated"));
        assertTrue(event.contains("PERSONAL"));
        assertTrue(event.contains(owner.toString()));
        assertFalse(event.contains(other.toString()));
        assertFalse(preferences.update(subject(owner), false).soundEnabled());
        assertEquals(1, events(), "Retrying the same preference is an effective no-op");
        assertTrue(preferences.update(subject(owner), true).soundEnabled());
        assertEquals(Boolean.TRUE, stored(owner));
        assertEquals(2, events());
    }

    @Test void enablingLegacyDefaultIsANoOpWithoutEventOrBackfill() {
        jdbc.update("update dbo.employee set notificationsoundenabled=null where employeeid=?", owner);
        assertTrue(preferences.update(subject(owner), true).soundEnabled());
        assertNull(stored(owner));
        assertEquals(0, events());
    }

    @Test void rollbackRevertsBothPreferenceAndPersonalOutbox() {
        tx.executeWithoutResult(status -> {
            assertFalse(preferences.update(subject(owner), false).soundEnabled());
            assertEquals(Boolean.FALSE, stored(owner));
            assertEquals(1, events());
            status.setRollbackOnly();
        });
        assertEquals(Boolean.TRUE, stored(owner));
        assertEquals(0, events());
    }

    @Test void httpContractIsSelfOnlyEvenForAdminAndUsesNoStore() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var initial = http.send(request(ownerToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(initial, 200, "\"soundEnabled\":true");
            var disabled = http.send(request(ownerToken).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"soundEnabled\":false}")).build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(disabled, 200, "\"soundEnabled\":false");
            assertResponse(http.send(request(ownerToken).GET().build(), HttpResponse.BodyHandlers.ofString()), 200,
                    "\"soundEnabled\":false");
            assertResponse(http.send(request(otherToken).GET().build(), HttpResponse.BodyHandlers.ofString()), 200,
                    "\"soundEnabled\":true");

            // Neither an administrative role nor an arbitrary recipient parameter changes the session owner.
            var attemptedOverride = request("/api/notifications/preferences?employeeId=" + other, adminToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"soundEnabled\":false}")).build();
            assertResponse(http.send(attemptedOverride, HttpResponse.BodyHandlers.ofString()), 200, "\"soundEnabled\":false");
            assertEquals(Boolean.FALSE, stored(admin));
            assertEquals(Boolean.TRUE, stored(other), "Admin cannot turn off another employee's sound");
            var notASelfRoute = request("/api/notifications/preferences/" + other, adminToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"soundEnabled\":false}")).build();
            assertNotEquals(200, http.send(notASelfRoute, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(Boolean.TRUE, stored(other));
        }
    }

    @Test void missingNullAndMalformedRequestsDoNotChangePreferenceOrPublish() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String body : Arrays.asList("{}", "{\"soundEnabled\":null}", "null", "{broken")) {
                var response = http.send(request(ownerToken).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertResponse(response, 400, "INVALID_REQUEST");
            }
            var absent = http.send(request(ownerToken).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertResponse(absent, 400, "INVALID_REQUEST");
        }
        assertEquals(Boolean.TRUE, stored(owner));
        assertEquals(0, events());
    }

    @Test void unauthenticatedRequestsAreRejectedWithoutCaching() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String credential : Arrays.asList(null, "invalid-token")) {
                assertResponse(http.send(request(credential).GET().build(), HttpResponse.BodyHandlers.ofString()), 401,
                        "UNAUTHENTICATED");
                var put = request(credential).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"soundEnabled\":false}")).build();
                assertResponse(http.send(put, HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHENTICATED");
            }
        }
        assertEquals(Boolean.TRUE, stored(owner));
        assertEquals(0, events());
    }

    @Test void disabledAndExpiredSubjectsCannotReadOrChangePreference() {
        var expired = AccessSubject.session(owner, Instant.now().minusSeconds(1));
        assertEquals(401, assertThrows(NotificationApiException.class, () -> preferences.get(expired)).status());
        assertEquals(401, assertThrows(NotificationApiException.class, () -> preferences.update(expired, false)).status());
        jdbc.update("update dbo.users set isenabled=false where userid=?", ownerUser);
        assertEquals(403, assertThrows(NotificationApiException.class, () -> preferences.get(subject(owner))).status());
        assertEquals(403, assertThrows(NotificationApiException.class, () -> preferences.update(subject(owner), false)).status());
        assertEquals(Boolean.TRUE, stored(owner));
        assertEquals(0, events());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleProfileCommitCannotRestoreEnabledSound(boolean legacyNull) throws Exception {
        if (legacyNull) jdbc.update("update dbo.employee set notificationsoundenabled=null where employeeid=?", owner);
        var profileLoaded = new CountDownLatch(1);
        var allowProfileCommit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var updateProfile = executor.submit(() -> tx.executeWithoutResult(status -> {
                var employee = em.find(EmployeeEntity.class, owner);
                assertTrue(employee.isNotificationSoundEnabled());
                employee.setFirstName("Concurrent profile");
                profileLoaded.countDown();
                try {
                    assertTrue(allowProfileCommit.await(10, TimeUnit.SECONDS), "Preference update did not release the profile writer");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }));
            try {
                assertTrue(profileLoaded.await(10, TimeUnit.SECONDS), "Profile writer did not reach its read boundary");
                assertFalse(preferences.update(subject(owner), false).soundEnabled());
            } finally {
                allowProfileCommit.countDown();
            }
            updateProfile.get(10, TimeUnit.SECONDS);
        }
        assertEquals(Boolean.FALSE, stored(owner), "A profile-only update must not restore its old sound preference");
        assertEquals("Concurrent profile", jdbc.queryForObject("select firstname from dbo.employee where employeeid=?", String.class, owner));
        assertEquals(1, events());
    }

    private AccessSubject subject(UUID employee) { return AccessSubject.session(employee, Instant.now().plusSeconds(600)); }
    private Boolean stored(UUID employee) {
        return jdbc.queryForObject("select notificationsoundenabled from dbo.employee where employeeid=?", Boolean.class, employee);
    }
    private int events() {
        return jdbc.queryForObject("select count(*) from dbo.notificationoutbox where organizationid=?", Integer.class, organization);
    }
    private HttpRequest.Builder request(String credential) { return request("/api/notifications/preferences", credential); }
    private HttpRequest.Builder request(String path, String credential) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        return request;
    }
    private void assertResponse(HttpResponse<String> response, int status, String body) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.body().contains(body), response.body());
    }
}
