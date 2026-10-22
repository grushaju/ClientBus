package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.server.persistence.entity.notification.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class NotificationSchemaTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_contract").withUsername("clientbus").withPassword("clientbus");

    private static final UUID ORGANIZATION = UUID.randomUUID(), RECIPIENT = UUID.randomUUID();
    private static JdbcTemplate jdbc;
    private static SessionFactory factory;
    private static StandardServiceRegistry registry;

    @BeforeAll
    static void createWithHibernateUpdate() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("create schema dbo");
        factory = buildFactory("update");
    }

    private static SessionFactory buildFactory(String action) {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", postgres.getJdbcUrl())
                .applySetting("hibernate.connection.username", postgres.getUsername())
                .applySetting("hibernate.connection.password", postgres.getPassword())
                .applySetting("hibernate.default_schema", "dbo")
                .applySetting("hibernate.hbm2ddl.auto", action)
                .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                .build();
        return new MetadataSources(registry)
                .addAnnotatedClass(NotificationOccurrenceEntity.class)
                .addAnnotatedClass(NotificationDedupEntity.class)
                .addAnnotatedClass(NotificationEntity.class)
                .addAnnotatedClass(NotificationCommandEntity.class)
                .addAnnotatedClass(NotificationOutboxEntity.class)
                .addAnnotatedClass(PushInstallationEntity.class)
                .addAnnotatedClass(NotificationDeliveryEntity.class)
                .addAnnotatedClass(HistorySyncOperationEntity.class)
                .buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @BeforeEach
    void clearOnlyTestDatabase() {
        jdbc.execute("truncate dbo.notification_delivery, dbo.notification, dbo.notification_dedup, "
                + "dbo.notification_occurrence, dbo.notification_command, dbo.notification_outbox, "
                + "dbo.push_installation, dbo.history_sync_operation cascade");
    }

    @Test
    void updateCreatesConstraintsAndASecondUpdatePreservesData() {
        UUID occurrence = occurrence(new UUID[0]);
        factory.close();
        StandardServiceRegistryBuilder.destroy(registry);
        factory = buildFactory("update");
        assertEquals(1, count("notification_occurrence"));
        assertArrayEquals(new Object[0], jdbc.queryForObject(
                "select recipient_employee_ids from dbo.notification_occurrence where id = ?",
                (rs, row) -> (Object[]) rs.getArray(1).getArray(), occurrence));
        assertTrue(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='dbo'",
                Integer.class) >= 20);
    }

    @Test
    void hibernatePersistsUuidArrayJsonAndDatabaseCreationTime() {
        UUID id;
        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            var occurrence = new NotificationOccurrenceEntity();
            occurrence.setOrganizationId(ORGANIZATION);
            occurrence.setOccurrenceKey("orm");
            occurrence.setOrigin(kit.penny.clientbus.common.notification.EventOrigin.LIVE);
            occurrence.setRecipientEmployeeIds(new UUID[]{RECIPIENT});
            occurrence.setDedupUntil(Instant.now().plusSeconds(90 * 86400));
            session.persist(occurrence);
            var dedup = new NotificationDedupEntity();
            dedup.setOccurrence(occurrence);
            dedup.setOrganizationId(ORGANIZATION);
            dedup.setRecipientEmployeeId(RECIPIENT);
            dedup.setDeduplicationKey("orm");
            dedup.setDedupUntil(occurrence.getDedupUntil());
            session.persist(dedup);
            var notification = new NotificationEntity();
            notification.setDedup(dedup);
            notification.setOrganizationId(ORGANIZATION);
            notification.setRecipientEmployeeId(RECIPIENT);
            notification.setType(kit.penny.clientbus.common.notification.NotificationType.INCOMING_MESSAGE);
            notification.setTargetKind(kit.penny.clientbus.common.notification.NotificationTargetKind.CONVERSATION);
            notification.setTargetId(UUID.randomUUID());
            notification.setTemplateArgs(Map.of());
            notification.setAlertExpiresAt(Instant.now().plusSeconds(300));
            notification.setRetainUntil(Instant.now().plusSeconds(30 * 86400));
            session.persist(notification);
            session.flush();
            assertNotNull(notification.getCreatedAt());
            assertNotNull(occurrence.getCreatedAt());
            id = notification.getId();
            transaction.commit();
        }
        try (var session = factory.openSession()) {
            var notification = session.find(NotificationEntity.class, id);
            assertEquals(Map.of(), notification.getTemplateArgs());
            assertArrayEquals(new UUID[]{RECIPIENT}, notification.getDedup().getOccurrence().getRecipientEmployeeIds());
        }
    }

    @Test
    void emptyAudienceSurvivesRepeatAfterAssignmentAndImportCannotDecideLive() {
        UUID occurrence = occurrence(new UUID[0]);
        int inserted = jdbc.update("insert into dbo.notification_occurrence "
                + "(id, organization_id, occurrence_key, origin, recipient_employee_ids, dedup_until) "
                + "values (?, ?, 'key', 'LIVE', array[?]::uuid[], clock_timestamp()+interval '90 days') "
                + "on conflict (organization_id, occurrence_key) do nothing",
                UUID.randomUUID(), ORGANIZATION, UUID.randomUUID());
        assertEquals(0, inserted);
        assertEquals(0, jdbc.queryForObject("select cardinality(recipient_employee_ids) "
                + "from dbo.notification_occurrence where id=?", Integer.class, occurrence));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification_occurrence set origin='HISTORY_IMPORT' where id=?", occurrence));
    }

    @Test
    void notificationRetentionDoesNotEraseDedupOrOriginalAudience() {
        UUID occurrence = occurrence(new UUID[]{RECIPIENT});
        UUID dedup = dedup(occurrence), notification = notification(dedup);
        jdbc.update("delete from dbo.notification where id=?", notification);
        assertEquals(1, count("notification_dedup"));
        assertEquals(1, count("notification_occurrence"));
        assertEquals(0, jdbc.update("insert into dbo.notification_dedup "
                + "(id, occurrence_id, organization_id, recipient_employee_id, deduplication_key, dedup_until) "
                + "values (?, ?, ?, ?, 'key', clock_timestamp()+interval '90 days') "
                + "on conflict (organization_id, recipient_employee_id, deduplication_key) do nothing",
                UUID.randomUUID(), occurrence, ORGANIZATION, RECIPIENT));
    }

    @Test
    void foreignKeysAndAlertBoundsAreEnforced() {
        UUID dedup = dedup(occurrence(new UUID[]{RECIPIENT}));
        UUID notification = notification(dedup);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("delete from dbo.notification_dedup where id=?", dedup));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification set alert_expires_at=created_at where id=?", notification));
    }

    @Test
    void readAllUsesFirstUpdateSnapshotAndStoredResultOnRetry() {
        UUID dedup = dedup(occurrence(new UUID[]{RECIPIENT}));
        notification(dedup);
        UUID command = UUID.randomUUID();
        jdbc.update("insert into dbo.notification_command "
                + "(id, organization_id, recipient_employee_id, idempotency_key, request_hash, scope, read_at, updated_count, retain_until) "
                + "values (?, ?, ?, 'request', 'hash', 'ALL_ACCESSIBLE', clock_timestamp(), 0, clock_timestamp()+interval '7 days')",
                command, ORGANIZATION, RECIPIENT);
        // ACL predicate is deliberately a test fixture; production policy is implemented in 11.3.
        int updated = jdbc.update("update dbo.notification set read_at=clock_timestamp() "
                + "where organization_id=? and recipient_employee_id=? and read_at is null", ORGANIZATION, RECIPIENT);
        jdbc.update("update dbo.notification_command set updated_count=? where id=?", updated, command);
        UUID secondOccurrence = UUID.randomUUID(), secondDedup = UUID.randomUUID();
        jdbc.update("insert into dbo.notification_occurrence "
                + "(id, organization_id, occurrence_key, origin, recipient_employee_ids, dedup_until) "
                + "values (?, ?, 'second', 'LIVE', array[?]::uuid[], clock_timestamp()+interval '90 days')",
                secondOccurrence, ORGANIZATION, RECIPIENT);
        jdbc.update("insert into dbo.notification_dedup "
                + "(id, occurrence_id, organization_id, recipient_employee_id, deduplication_key, dedup_until) "
                + "values (?, ?, ?, ?, 'second', clock_timestamp()+interval '90 days')",
                secondDedup, secondOccurrence, ORGANIZATION, RECIPIENT);
        notification(secondDedup);
        assertEquals(1, jdbc.queryForObject(
                "select updated_count from dbo.notification_command where id=?", Integer.class, command));
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notification where read_at is null", Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification_command set updated_count=-1 where id=?", command));
    }

    @Test
    void outboxClaimFencesLateAckAndDoesNotFakePublished() {
        UUID id = outbox();
        assertEquals(1, jdbc.update("update dbo.notification_outbox set status='IN_FLIGHT', claim_version=1, "
                + "lease_until=clock_timestamp()+interval '30 seconds' where id=? and status='PENDING'", id));
        assertEquals(1, jdbc.update("update dbo.notification_outbox set claim_version=2, "
                + "lease_until=clock_timestamp()+interval '30 seconds' where id=? and status='IN_FLIGHT'", id));
        assertEquals(0, jdbc.update("update dbo.notification_outbox set status='PUBLISHED', published_at=clock_timestamp(), "
                + "lease_until=null where id=? and status='IN_FLIGHT' and claim_version=1", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification_outbox set status='PUBLISHED', lease_until=null where id=?", id));
        assertEquals(1, jdbc.update("update dbo.notification_outbox set status='QUARANTINED', lease_until=null, "
                + "error_code='INVALID_PAYLOAD' where id=? and claim_version=2", id));
    }

    @Test
    void activeEndpointIsUniqueButDisabledRegistrationsCanKeepFingerprint() {
        String fingerprint = "a".repeat(64);
        UUID installation = installation(fingerprint);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> installation(fingerprint));
        jdbc.update("update dbo.push_installation set enabled=false, active_endpoint_fingerprint=null where id=?", installation);
        assertDoesNotThrow(() -> installation(fingerprint));
    }

    @Test
    void lateEndpointErrorCannotDisableRotation() {
        UUID id = installation("b".repeat(64));
        jdbc.update("update dbo.push_installation set endpoint_version=2, endpoint_fingerprint=?, "
                + "active_endpoint_fingerprint=? where id=?", "c".repeat(64), "c".repeat(64), id);
        assertEquals(0, jdbc.update("update dbo.push_installation set enabled=false, active_endpoint_fingerprint=null "
                + "where id=? and endpoint_version=1 and endpoint_fingerprint=?", id, "b".repeat(64)));
        assertTrue(jdbc.queryForObject("select enabled from dbo.push_installation where id=?", Boolean.class, id));
    }

    @Test
    void deliveryIsUniqueAndOldClaimCannotCompleteIt() {
        UUID notification = notification(dedup(occurrence(new UUID[]{RECIPIENT})));
        UUID installation = installation("d".repeat(64));
        UUID delivery = delivery(notification, installation);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> delivery(notification, installation));
        jdbc.update("update dbo.notification_delivery set status='IN_FLIGHT', claim_version=2, "
                + "attempt_endpoint_version=1, lease_until=clock_timestamp()+interval '30 seconds' where id=?", delivery);
        assertEquals(0, jdbc.update("update dbo.notification_delivery set status='PROVIDER_ACCEPTED', lease_until=null, completed_at=clock_timestamp(), retain_until=clock_timestamp()+interval '7 days' "
                + "where id=? and status='IN_FLIGHT' and claim_version=1", delivery));
        assertEquals(1, jdbc.update("update dbo.notification_delivery set status='PROVIDER_ACCEPTED', lease_until=null, completed_at=clock_timestamp(), retain_until=clock_timestamp()+interval '7 days' "
                + "where id=? and status='IN_FLIGHT' and claim_version=2", delivery));
    }

    @Test
    void installationMutationVersionFencesOldAccountAndTokenUpdates() {
        UUID id = installation("e".repeat(64));
        assertEquals(1, jdbc.update("update dbo.push_installation set recipient_employee_id=?, "
                + "binding_version=binding_version+1, registration_version=registration_version+1 "
                + "where id=? and registration_version=1", UUID.randomUUID(), id));
        assertEquals(0, jdbc.update("update dbo.push_installation set recipient_employee_id=?, "
                + "registration_version=registration_version+1 where id=? and registration_version=1", RECIPIENT, id));
        assertEquals(0, jdbc.update("update dbo.push_installation set enabled=false, active_endpoint_fingerprint=null, "
                + "registration_version=registration_version+1 where id=? and registration_version=1", id));
        assertEquals(1, jdbc.update("update dbo.push_installation set endpoint_version=endpoint_version+1, "
                + "registration_version=registration_version+1 where id=? and registration_version=2", id));
        assertEquals(0, jdbc.update("update dbo.push_installation set endpoint_version=1, "
                + "registration_version=registration_version+1 where id=? and registration_version=2", id));
    }

    @Test
    void terminalDeliveryRequiresCompletionAndIndependentRetention() {
        UUID id = delivery(notification(dedup(occurrence(new UUID[]{RECIPIENT}))), installation("f".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification_delivery set status='CANCELLED' where id=?", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification_delivery set retain_until=clock_timestamp()+interval '7 days' where id=?", id));
        assertEquals(1, jdbc.update("update dbo.notification_delivery set status='CANCELLED', completed_at=clock_timestamp(), "
                + "retain_until=clock_timestamp()+interval '7 days' where id=?", id));
        assertTrue(jdbc.queryForObject("select retain_until > expires_at from dbo.notification_delivery where id=?", Boolean.class, id));
    }

    @Test
    void closedAlertRemainsClosedWithoutMarkingCenterRead() {
        UUID id = notification(dedup(occurrence(new UUID[]{RECIPIENT})));
        assertEquals(1, jdbc.update("update dbo.notification set alert_invalidated_at=clock_timestamp() "
                + "where id=? and alert_invalidated_at is null", id));
        var closedAt = jdbc.queryForObject("select alert_invalidated_at from dbo.notification where id=?", java.sql.Timestamp.class, id);
        assertEquals(0, jdbc.update("update dbo.notification set alert_invalidated_at=clock_timestamp() "
                + "where id=? and alert_invalidated_at is null", id));
        try (var session = factory.openSession()) {
            var value = session.find(NotificationEntity.class, id);
            value.invalidateAlert(java.time.Instant.now().plusSeconds(60));
            assertEquals(closedAt.toInstant(), value.getAlertInvalidatedAt());
            assertNull(value.getReadAt());
        }
    }

    @Test
    void terminalHistoryOperationIsRecoverableByKeyAndNotResurrectedByConditionalResult() {
        UUID id = operation();
        jdbc.update("update dbo.history_sync_operation set status='FAILED', error_code='DEADLINE_EXCEEDED', "
                + "completed_at=clock_timestamp() where id=? and status='PENDING'", id);
        assertEquals(id, jdbc.queryForObject("select id from dbo.history_sync_operation "
                + "where organization_id=? and initiator_employee_id=? and idempotency_key='request'",
                UUID.class, ORGANIZATION, RECIPIENT));
        assertEquals(0, jdbc.update("update dbo.history_sync_operation set status='SUCCEEDED', result='{}', "
                + "error_code=null, completed_at=clock_timestamp() "
                + "where id=? and status='RUNNING' and claim_version=1", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.history_sync_operation set status='RUNNING', completed_at=null where id=?", id));
    }

    @Test
    void concurrentOccurrenceInsertCommitsOneDecision() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> insert = () -> {
                start.await(10, TimeUnit.SECONDS);
                return jdbc.update("insert into dbo.notification_occurrence "
                        + "(id, organization_id, occurrence_key, origin, recipient_employee_ids, dedup_until) "
                        + "values (?, ?, 'concurrent', 'LIVE', '{}'::uuid[], clock_timestamp()+interval '90 days') "
                        + "on conflict (organization_id, occurrence_key) do nothing", UUID.randomUUID(), ORGANIZATION);
            };
            Future<Integer> first = executor.submit(insert), second = executor.submit(insert);
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from dbo." + table, Integer.class);
    }

    private UUID occurrence(UUID[] recipients) {
        UUID id = UUID.randomUUID();
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.prepareStatement("insert into dbo.notification_occurrence "
                    + "(id, organization_id, occurrence_key, origin, recipient_employee_ids, dedup_until) "
                    + "values (?, ?, 'key', 'LIVE', ?, clock_timestamp()+interval '90 days')")) {
                statement.setObject(1, id); statement.setObject(2, ORGANIZATION);
                statement.setArray(3, connection.createArrayOf("uuid", recipients));
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private UUID dedup(UUID occurrence) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notification_dedup (id, occurrence_id, organization_id, recipient_employee_id, "
                + "deduplication_key, dedup_until) values (?, ?, ?, ?, 'key', clock_timestamp()+interval '90 days')",
                id, occurrence, ORGANIZATION, RECIPIENT);
        return id;
    }

    private UUID notification(UUID dedup) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notification (id, dedup_id, organization_id, recipient_employee_id, type, "
                + "target_kind, target_id, template_args, alert_expires_at, retain_until) "
                + "values (?, ?, ?, ?, 'INCOMING_MESSAGE', 'CONVERSATION', ?, '{}', "
                + "clock_timestamp()+interval '5 minutes', clock_timestamp()+interval '30 days')",
                id, dedup, ORGANIZATION, RECIPIENT, UUID.randomUUID());
        return id;
    }

    private UUID outbox() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notification_outbox (id, event_id, organization_id, destination, event_type, "
                + "schema_version, payload, status, attempts, claim_version, next_attempt_at) "
                + "values (?, ?, ?, 'clientbus.ui.invalidations.v1', 'inbox.invalidated', 1, '{}', 'PENDING', 0, 0, clock_timestamp())",
                id, UUID.randomUUID(), ORGANIZATION);
        return id;
    }

    private UUID installation(String fingerprint) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.push_installation (id, organization_id, recipient_employee_id, platform, provider, "
                + "app_id, environment, encrypted_endpoint, endpoint_fingerprint, active_endpoint_fingerprint, ownership_secret_hash, "
                + "binding_version, endpoint_version, enabled, reported_permission, last_seen_at, active_until) "
                + "values (?, ?, ?, 'ANDROID', 'FCM', 'test-app', 'PRODUCTION', ?, ?, ?, ?, 1, 1, true, 'GRANTED', "
                + "clock_timestamp(), clock_timestamp()+interval '30 days')",
                id, ORGANIZATION, RECIPIENT, new byte[]{1}, fingerprint, fingerprint, new byte[32]);
        return id;
    }

    private UUID delivery(UUID notification, UUID installation) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notification_delivery (id, notification_id, registration_id, binding_version, channel, "
                + "status, attempts, claim_version, next_attempt_at, expires_at) "
                + "values (?, ?, ?, 1, 'MOBILE_PUSH', 'PENDING', 0, 0, clock_timestamp(), clock_timestamp()+interval '5 minutes')",
                id, notification, installation);
        return id;
    }

    private UUID operation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.history_sync_operation (id, organization_id, workspace_id, conversation_id, channel_account_id, "
                + "initiator_employee_id, idempotency_key, request_hash, request_limit, status, attempts, claim_version, deadline_at, "
                + "updated_at, retain_until) values (?, ?, ?, ?, ?, ?, 'request', 'hash', 50, 'PENDING', 0, 0, "
                + "clock_timestamp()+interval '2 minutes', clock_timestamp(), clock_timestamp()+interval '7 days')",
                id, ORGANIZATION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RECIPIENT);
        return id;
    }
}
