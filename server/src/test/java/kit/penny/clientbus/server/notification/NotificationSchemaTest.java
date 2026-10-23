package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.server.persistence.entity.notification.*;
import kit.penny.clientbus.server.persistence.entity.OutboundCallbackEntity;
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
            .withDatabaseName("notificationcontract").withUsername("clientbus").withPassword("clientbus");

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
                .addAnnotatedClass(OutboundCallbackEntity.class)
                .buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @BeforeEach
    void clearOnlyTestDatabase() {
        jdbc.execute("truncate dbo.notificationdelivery, dbo.notification, dbo.notificationdedup, "
                + "dbo.notificationoccurrence, dbo.notificationcommand, dbo.notificationoutbox, "
                + "dbo.pushinstallation, dbo.historysyncoperation, dbo.outboundsendcallback cascade");
    }

    @Test
    void databaseTablesColumnsIndexesAndConstraintsHaveNoUnderscores() {
        var expected = Set.of("notification", "notificationoccurrence", "notificationdedup", "notificationcommand",
                "notificationoutbox", "notificationdelivery", "pushinstallation", "historysyncoperation", "outboundsendcallback");
        assertEquals(expected, new HashSet<>(jdbc.queryForList("select tablename from pg_tables where schemaname='dbo'", String.class)));
        for (String table : expected) {
            var columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema='dbo' and table_name=?", String.class, table);
            var indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname='dbo' and tablename=?", String.class, table);
            var constraints = jdbc.queryForList("""
                    select con.conname from pg_constraint con join pg_class t on t.oid=con.conrelid
                    join pg_namespace ns on ns.oid=t.relnamespace where ns.nspname='dbo' and t.relname=?
                    """, String.class, table);
            assertFalse(columns.isEmpty(), table);
            assertFalse(indexes.isEmpty(), table);
            assertFalse(constraints.isEmpty(), table);
            assertEquals(table + "pk", jdbc.queryForObject("""
                    select con.conname from pg_constraint con join pg_class t on t.oid=con.conrelid
                    join pg_namespace ns on ns.oid=t.relnamespace
                    where ns.nspname='dbo' and t.relname=? and con.contype='p'
                    """, String.class, table));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from pg_constraint uk join pg_constraint pk
                    on uk.conrelid=pk.conrelid and uk.conkey=pk.conkey and pk.contype='p'
                    join pg_class t on t.oid=uk.conrelid join pg_namespace ns on ns.oid=t.relnamespace
                    where ns.nspname='dbo' and t.relname=? and uk.contype='u'
                    """, Integer.class, table), "A named primary key must not add a duplicate unique index");
            for (var names : List.of(columns, indexes, constraints))
                assertTrue(names.stream().noneMatch(name -> name.contains("_")), table + ": " + names);
        }
    }

    @Test
    void updateCreatesConstraintsAndASecondUpdatePreservesData() {
        UUID occurrence = occurrence(new UUID[0]);
        factory.close();
        StandardServiceRegistryBuilder.destroy(registry);
        factory = buildFactory("update");
        assertEquals(1, count("notificationoccurrence"));
        assertArrayEquals(new Object[0], jdbc.queryForObject(
                "select recipientemployeeids from dbo.notificationoccurrence where id = ?",
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
        int inserted = jdbc.update("insert into dbo.notificationoccurrence "
                + "(id, organizationid, occurrencekey, origin, recipientemployeeids, dedupuntil) "
                + "values (?, ?, 'key', 'LIVE', array[?]::uuid[], clock_timestamp()+interval '90 days') "
                + "on conflict (organizationid, occurrencekey) do nothing",
                UUID.randomUUID(), ORGANIZATION, UUID.randomUUID());
        assertEquals(0, inserted);
        assertEquals(0, jdbc.queryForObject("select cardinality(recipientemployeeids) "
                + "from dbo.notificationoccurrence where id=?", Integer.class, occurrence));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notificationoccurrence set origin='HISTORY_IMPORT' where id=?", occurrence));
    }

    @Test
    void notificationRetentionDoesNotEraseDedupOrOriginalAudience() {
        UUID occurrence = occurrence(new UUID[]{RECIPIENT});
        UUID dedup = dedup(occurrence), notification = notification(dedup);
        jdbc.update("delete from dbo.notification where id=?", notification);
        assertEquals(1, count("notificationdedup"));
        assertEquals(1, count("notificationoccurrence"));
        assertEquals(0, jdbc.update("insert into dbo.notificationdedup "
                + "(id, occurrenceid, organizationid, recipientemployeeid, deduplicationkey, dedupuntil) "
                + "values (?, ?, ?, ?, 'key', clock_timestamp()+interval '90 days') "
                + "on conflict (organizationid, recipientemployeeid, deduplicationkey) do nothing",
                UUID.randomUUID(), occurrence, ORGANIZATION, RECIPIENT));
    }

    @Test
    void foreignKeysAndAlertBoundsAreEnforced() {
        UUID dedup = dedup(occurrence(new UUID[]{RECIPIENT}));
        UUID notification = notification(dedup);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("delete from dbo.notificationdedup where id=?", dedup));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notification set alertexpiresat=createdat where id=?", notification));
    }

    @Test
    void readAllUsesFirstUpdateSnapshotAndStoredResultOnRetry() {
        UUID dedup = dedup(occurrence(new UUID[]{RECIPIENT}));
        notification(dedup);
        UUID command = UUID.randomUUID();
        jdbc.update("insert into dbo.notificationcommand "
                + "(id, organizationid, recipientemployeeid, idempotencykey, requesthash, scope, readat, updatedcount, retainuntil) "
                + "values (?, ?, ?, 'request', 'hash', 'ALL_ACCESSIBLE', clock_timestamp(), 0, clock_timestamp()+interval '7 days')",
                command, ORGANIZATION, RECIPIENT);
        // ACL predicate is deliberately a test fixture; production policy is implemented in 11.3.
        int updated = jdbc.update("update dbo.notification set readat=clock_timestamp() "
                + "where organizationid=? and recipientemployeeid=? and readat is null", ORGANIZATION, RECIPIENT);
        jdbc.update("update dbo.notificationcommand set updatedcount=? where id=?", updated, command);
        UUID secondOccurrence = UUID.randomUUID(), secondDedup = UUID.randomUUID();
        jdbc.update("insert into dbo.notificationoccurrence "
                + "(id, organizationid, occurrencekey, origin, recipientemployeeids, dedupuntil) "
                + "values (?, ?, 'second', 'LIVE', array[?]::uuid[], clock_timestamp()+interval '90 days')",
                secondOccurrence, ORGANIZATION, RECIPIENT);
        jdbc.update("insert into dbo.notificationdedup "
                + "(id, occurrenceid, organizationid, recipientemployeeid, deduplicationkey, dedupuntil) "
                + "values (?, ?, ?, ?, 'second', clock_timestamp()+interval '90 days')",
                secondDedup, secondOccurrence, ORGANIZATION, RECIPIENT);
        notification(secondDedup);
        assertEquals(1, jdbc.queryForObject(
                "select updatedcount from dbo.notificationcommand where id=?", Integer.class, command));
        assertEquals(1, jdbc.queryForObject("select count(*) from dbo.notification where readat is null", Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notificationcommand set updatedcount=-1 where id=?", command));
    }

    @Test
    void outboxClaimFencesLateAckAndDoesNotFakePublished() {
        UUID id = outbox();
        assertEquals(1, jdbc.update("update dbo.notificationoutbox set status='IN_FLIGHT', claimversion=1, "
                + "leaseuntil=clock_timestamp()+interval '30 seconds' where id=? and status='PENDING'", id));
        assertEquals(1, jdbc.update("update dbo.notificationoutbox set claimversion=2, "
                + "leaseuntil=clock_timestamp()+interval '30 seconds' where id=? and status='IN_FLIGHT'", id));
        assertEquals(0, jdbc.update("update dbo.notificationoutbox set status='PUBLISHED', publishedat=clock_timestamp(), "
                + "leaseuntil=null where id=? and status='IN_FLIGHT' and claimversion=1", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notificationoutbox set status='PUBLISHED', leaseuntil=null where id=?", id));
        assertEquals(1, jdbc.update("update dbo.notificationoutbox set status='QUARANTINED', leaseuntil=null, "
                + "errorcode='INVALID_PAYLOAD' where id=? and claimversion=2", id));
    }

    @Test
    void activeEndpointIsUniqueButDisabledRegistrationsCanKeepFingerprint() {
        String fingerprint = "a".repeat(64);
        UUID installation = installation(fingerprint);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> installation(fingerprint));
        jdbc.update("update dbo.pushinstallation set enabled=false, activeendpointfingerprint=null where id=?", installation);
        assertDoesNotThrow(() -> installation(fingerprint));
    }

    @Test
    void lateEndpointErrorCannotDisableRotation() {
        UUID id = installation("b".repeat(64));
        jdbc.update("update dbo.pushinstallation set endpointversion=2, endpointfingerprint=?, "
                + "activeendpointfingerprint=? where id=?", "c".repeat(64), "c".repeat(64), id);
        assertEquals(0, jdbc.update("update dbo.pushinstallation set enabled=false, activeendpointfingerprint=null "
                + "where id=? and endpointversion=1 and endpointfingerprint=?", id, "b".repeat(64)));
        assertTrue(jdbc.queryForObject("select enabled from dbo.pushinstallation where id=?", Boolean.class, id));
    }

    @Test
    void deliveryIsUniqueAndOldClaimCannotCompleteIt() {
        UUID notification = notification(dedup(occurrence(new UUID[]{RECIPIENT})));
        UUID installation = installation("d".repeat(64));
        UUID delivery = delivery(notification, installation);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> delivery(notification, installation));
        jdbc.update("update dbo.notificationdelivery set status='IN_FLIGHT', claimversion=2, "
                + "attemptendpointversion=1, leaseuntil=clock_timestamp()+interval '30 seconds' where id=?", delivery);
        assertEquals(0, jdbc.update("update dbo.notificationdelivery set status='PROVIDER_ACCEPTED', leaseuntil=null, completedat=clock_timestamp(), retainuntil=clock_timestamp()+interval '7 days' "
                + "where id=? and status='IN_FLIGHT' and claimversion=1", delivery));
        assertEquals(1, jdbc.update("update dbo.notificationdelivery set status='PROVIDER_ACCEPTED', leaseuntil=null, completedat=clock_timestamp(), retainuntil=clock_timestamp()+interval '7 days' "
                + "where id=? and status='IN_FLIGHT' and claimversion=2", delivery));
    }

    @Test
    void installationMutationVersionFencesOldAccountAndTokenUpdates() {
        UUID id = installation("e".repeat(64));
        assertEquals(1, jdbc.update("update dbo.pushinstallation set recipientemployeeid=?, "
                + "bindingversion=bindingversion+1, registrationversion=registrationversion+1 "
                + "where id=? and registrationversion=1", UUID.randomUUID(), id));
        assertEquals(0, jdbc.update("update dbo.pushinstallation set recipientemployeeid=?, "
                + "registrationversion=registrationversion+1 where id=? and registrationversion=1", RECIPIENT, id));
        assertEquals(0, jdbc.update("update dbo.pushinstallation set enabled=false, activeendpointfingerprint=null, "
                + "registrationversion=registrationversion+1 where id=? and registrationversion=1", id));
        assertEquals(1, jdbc.update("update dbo.pushinstallation set endpointversion=endpointversion+1, "
                + "registrationversion=registrationversion+1 where id=? and registrationversion=2", id));
        assertEquals(0, jdbc.update("update dbo.pushinstallation set endpointversion=1, "
                + "registrationversion=registrationversion+1 where id=? and registrationversion=2", id));
    }

    @Test
    void terminalDeliveryRequiresCompletionAndIndependentRetention() {
        UUID id = delivery(notification(dedup(occurrence(new UUID[]{RECIPIENT}))), installation("f".repeat(64)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notificationdelivery set status='CANCELLED' where id=?", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.notificationdelivery set retainuntil=clock_timestamp()+interval '7 days' where id=?", id));
        assertEquals(1, jdbc.update("update dbo.notificationdelivery set status='CANCELLED', completedat=clock_timestamp(), "
                + "retainuntil=clock_timestamp()+interval '7 days' where id=?", id));
        assertTrue(jdbc.queryForObject("select retainuntil > expiresat from dbo.notificationdelivery where id=?", Boolean.class, id));
    }

    @Test
    void closedAlertRemainsClosedWithoutMarkingCenterRead() {
        UUID id = notification(dedup(occurrence(new UUID[]{RECIPIENT})));
        assertEquals(1, jdbc.update("update dbo.notification set alertinvalidatedat=clock_timestamp() "
                + "where id=? and alertinvalidatedat is null", id));
        var closedAt = jdbc.queryForObject("select alertinvalidatedat from dbo.notification where id=?", java.sql.Timestamp.class, id);
        assertEquals(0, jdbc.update("update dbo.notification set alertinvalidatedat=clock_timestamp() "
                + "where id=? and alertinvalidatedat is null", id));
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
        jdbc.update("update dbo.historysyncoperation set status='FAILED', errorcode='DEADLINE_EXCEEDED', "
                + "completedat=clock_timestamp() where id=? and status='PENDING'", id);
        assertEquals(id, jdbc.queryForObject("select id from dbo.historysyncoperation "
                + "where organizationid=? and initiatoremployeeid=? and idempotencykey='request'",
                UUID.class, ORGANIZATION, RECIPIENT));
        assertEquals(0, jdbc.update("update dbo.historysyncoperation set status='SUCCEEDED', result='{}', "
                + "errorcode=null, completedat=clock_timestamp() "
                + "where id=? and status='RUNNING' and claimversion=1", id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("update dbo.historysyncoperation set status='RUNNING', completedat=null where id=?", id));
    }

    @Test
    void concurrentOccurrenceInsertCommitsOneDecision() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> insert = () -> {
                start.await(10, TimeUnit.SECONDS);
                return jdbc.update("insert into dbo.notificationoccurrence "
                        + "(id, organizationid, occurrencekey, origin, recipientemployeeids, dedupuntil) "
                        + "values (?, ?, 'concurrent', 'LIVE', '{}'::uuid[], clock_timestamp()+interval '90 days') "
                        + "on conflict (organizationid, occurrencekey) do nothing", UUID.randomUUID(), ORGANIZATION);
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
            try (var statement = connection.prepareStatement("insert into dbo.notificationoccurrence "
                    + "(id, organizationid, occurrencekey, origin, recipientemployeeids, dedupuntil) "
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
        jdbc.update("insert into dbo.notificationdedup (id, occurrenceid, organizationid, recipientemployeeid, "
                + "deduplicationkey, dedupuntil) values (?, ?, ?, ?, 'key', clock_timestamp()+interval '90 days')",
                id, occurrence, ORGANIZATION, RECIPIENT);
        return id;
    }

    private UUID notification(UUID dedup) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notification (id, dedupid, organizationid, recipientemployeeid, type, "
                + "targetkind, targetid, templateargs, alertexpiresat, retainuntil) "
                + "values (?, ?, ?, ?, 'INCOMING_MESSAGE', 'CONVERSATION', ?, '{}', "
                + "clock_timestamp()+interval '5 minutes', clock_timestamp()+interval '30 days')",
                id, dedup, ORGANIZATION, RECIPIENT, UUID.randomUUID());
        return id;
    }

    private UUID outbox() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notificationoutbox (id, eventid, organizationid, destination, eventtype, "
                + "schemaversion, payload, status, attempts, claimversion, nextattemptat) "
                + "values (?, ?, ?, 'clientbus.ui.invalidations.v1', 'inbox.invalidated', 1, '{}', 'PENDING', 0, 0, clock_timestamp())",
                id, UUID.randomUUID(), ORGANIZATION);
        return id;
    }

    private UUID installation(String fingerprint) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.pushinstallation (id, organizationid, recipientemployeeid, platform, provider, "
                + "appid, environment, encryptedendpoint, endpointfingerprint, activeendpointfingerprint, ownershipsecrethash, "
                + "bindingversion, endpointversion, enabled, reportedpermission, lastseenat, activeuntil) "
                + "values (?, ?, ?, 'ANDROID', 'FCM', 'test-app', 'PRODUCTION', ?, ?, ?, ?, 1, 1, true, 'GRANTED', "
                + "clock_timestamp(), clock_timestamp()+interval '30 days')",
                id, ORGANIZATION, RECIPIENT, new byte[]{1}, fingerprint, fingerprint, new byte[32]);
        return id;
    }

    private UUID delivery(UUID notification, UUID installation) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.notificationdelivery (id, notificationid, registrationid, bindingversion, channel, "
                + "status, attempts, claimversion, nextattemptat, expiresat) "
                + "values (?, ?, ?, 1, 'MOBILE_PUSH', 'PENDING', 0, 0, clock_timestamp(), clock_timestamp()+interval '5 minutes')",
                id, notification, installation);
        return id;
    }

    private UUID operation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dbo.historysyncoperation (id, organizationid, workspaceid, conversationid, channelaccountid, "
                + "initiatoremployeeid, idempotencykey, requesthash, requestlimit, status, attempts, claimversion, deadlineat, "
                + "updatedat, retainuntil) values (?, ?, ?, ?, ?, ?, 'request', 'hash', 50, 'PENDING', 0, 0, "
                + "clock_timestamp()+interval '2 minutes', clock_timestamp(), clock_timestamp()+interval '7 days')",
                id, ORGANIZATION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RECIPIENT);
        return id;
    }
}
