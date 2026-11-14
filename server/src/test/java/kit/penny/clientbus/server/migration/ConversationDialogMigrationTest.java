package kit.penny.clientbus.server.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 13.2b: the dialog-reference migration must run on a COPY of a production-shaped
 * database without touching conversation UUIDs or their message references, must lift
 * the one-dialog-per-counterpart limit only through the dialog reference, and must keep
 * legacy rows (dialogexternalid IS NULL) unique per counterpart.
 */
@Testcontainers
class ConversationDialogMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("clientbus")
                    .withUsername("clientbus")
                    .withPassword("clientbus");

    private static final UUID CONVERSATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID MESSAGE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID CLIENT_ACCOUNT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void seedLegacySchema() throws Exception {

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();

        try (Connection connection =
                     DriverManager.getConnection(jdbcUrl, username, password)) {

            // V0 only: the legacy business tables below imitate what
            // Hibernate ddl-auto had produced before 13.2b, including the old 1:1
            // unique index name conversation_channel_client_uix.
            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .target("0")
                    .load()
                    .migrate();

            execute(connection, """
                    create table dbo.conversation (
                        id uuid primary key,
                        channelaccountid uuid not null,
                        clientaccountid uuid not null,
                        createdat timestamptz not null default now(),
                        constraint conversation_channel_client_uix
                            unique (channelaccountid, clientaccountid)
                    )
                    """);

            execute(connection, """
                    create table dbo.message (
                        id uuid primary key,
                        conversationid uuid not null references dbo.conversation (id),
                        externalid varchar(256)
                    )
                    """);

            execute(connection, """
                    create table dbo.outboundsendcallback (
                        id uuid primary key,
                        channelaccountid uuid not null,
                        recipientexternalid varchar(256) not null,
                        pendingexternalid varchar(256) not null,
                        createdat timestamptz not null default now(),
                        retainuntil timestamptz,
                        constraint outboundcallbackkeyuq
                            unique (channelaccountid, recipientexternalid, pendingexternalid)
                    )
                    """);

            execute(connection,
                    "insert into dbo.conversation (id, channelaccountid, clientaccountid) values ("
                            + quote(CONVERSATION_ID) + ", "
                            + quote(CHANNEL_ACCOUNT_ID) + ", "
                            + quote(CLIENT_ACCOUNT_ID) + ")");

            execute(connection,
                    "insert into dbo.message (id, conversationid, externalid) values ("
                            + quote(MESSAGE_ID) + ", "
                            + quote(CONVERSATION_ID) + ", 'legacy-external-1')");

            execute(connection,
                    "insert into dbo.outboundsendcallback "
                            + "(id, channelaccountid, recipientexternalid, pendingexternalid, retainuntil) "
                            + "values (gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID)
                            + ", 'user-1', 'pending-1', now() + interval '7 days')");
        }
    }

    @Test
    void v2PreservesUuidsLiftsOneDialogLimitAndKeepsLegacyUniqueness() throws Exception {

        try (Connection connection =
                     DriverManager.getConnection(jdbcUrl, username, password)) {

            int applied = Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                    .migrationsExecuted;

            assertEquals(1, applied, "V2 is the only pending migration after the legacy seed");

            assertTrue(
                    hasColumn(connection, "select dialogexternalid from dbo.conversation"),
                    "dialogexternalid column must exist");

            // The conversation UUID and its message reference survive untouched.
            assertEquals(1, count(connection,
                    "select count(*) from dbo.message m "
                            + "join dbo.conversation c on c.id = m.conversationid "
                            + "where m.id = " + quote(MESSAGE_ID)
                            + " and c.id = " + quote(CONVERSATION_ID)));

            // The old hard 1:1 constraint is gone.
            assertEquals(0, count(connection,
                    "select count(*) from pg_constraint "
                            + "where conname = 'conversation_channel_client_uix'"));

            // A legacy row can heal its dialog reference in place (UUID unchanged).
            assertTrue(executes(connection,
                    "update dbo.conversation set dialogexternalid = '42' where id = "
                            + quote(CONVERSATION_ID)));

            // Multiplicity: a second dialog for the SAME counterpart is now allowed.
            assertTrue(executes(connection,
                    "insert into dbo.conversation "
                            + "(id, channelaccountid, clientaccountid, dialogexternalid) values ("
                            + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID) + ", "
                            + quote(CLIENT_ACCOUNT_ID) + ", '43')"));

            // The dialog reference stays unique per channel account.
            assertThrows(
                    SQLException.class,
                    () -> executes(connection,
                            "insert into dbo.conversation "
                                    + "(id, channelaccountid, clientaccountid, dialogexternalid) values ("
                                    + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID) + ", "
                                    + quote(CLIENT_ACCOUNT_ID) + ", '42')"));

            // Legacy rows without a dialog reference remain one per counterpart.
            // (The healed row above left the no-dialog set, so test a fresh legacy pair.)
            String legacyClient = "'" + UUID.randomUUID() + "'";

            assertTrue(executes(connection,
                    "insert into dbo.conversation (id, channelaccountid, clientaccountid) values ("
                            + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID)
                            + ", " + legacyClient + ")"));

            assertThrows(
                    SQLException.class,
                    () -> executes(connection,
                            "insert into dbo.conversation (id, channelaccountid, clientaccountid) values ("
                                    + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID)
                                    + ", " + legacyClient + ")"));

            // The callback journal keeps its row and enforces the new slot key.
            assertEquals(1, count(connection,
                    "select count(*) from dbo.outboundsendcallback "
                            + "where recipientexternalid = 'user-1' "
                            + "and pendingexternalid = 'pending-1'"));

            assertTrue(executes(connection,
                    "insert into dbo.outboundsendcallback "
                            + "(id, channelaccountid, recipientexternalid, pendingexternalid) values ("
                            + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID)
                            + ", 'user-2', 'pending-1')"));

            assertThrows(
                    SQLException.class,
                    () -> executes(connection,
                            "insert into dbo.outboundsendcallback "
                                    + "(id, channelaccountid, recipientexternalid, pendingexternalid) values ("
                                    + "gen_random_uuid(), " + quote(CHANNEL_ACCOUNT_ID)
                                    + ", 'user-1', 'pending-1')"));

            assertFalse(
                    hasColumn(connection, "select dialogexternalid from dbo.message"),
                    "message table must not be touched by the migration");
        }
    }

    private static String quote(UUID value) {
        return "'" + value + "'";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean executes(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        }
    }

    private static boolean hasColumn(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (SQLException missing) {
            return false;
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
