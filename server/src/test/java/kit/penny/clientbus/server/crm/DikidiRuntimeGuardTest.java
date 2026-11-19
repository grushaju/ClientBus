package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.4: guard единственного активного runtime проверяется на реальной PostgreSQL:
 * второй экземпляр с тем же connectionId деградирует явно, истёкший lease
 * перебирается новым владельцем, heartbeat владельца не даёт его отобрать.
 */
@Testcontainers
class DikidiRuntimeGuardTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    @BeforeAll static void schema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("create schema crm");
            connection.createStatement().execute(DikidiRuntimeGuard.TABLE_DDL);
            connection.createStatement().execute("create table crm.connection(id uuid primary key,credentialversion bigint,status text)");
        }
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
    }
    @Test void revokedVersionAndDisabledConnectionCannotStartExternalWork() {
        var id = UUID.randomUUID();
        jdbc.update("insert into crm.connection values(?,1,'CONNECTED')", id);
        var guard = new DikidiRuntimeGuard(jdbc, () -> { });
        guard.requireUsable(id, 1);
        jdbc.update("update crm.connection set credentialversion=2 where id=?", id);
        assertThrows(DikidiFailure.class, () -> guard.requireUsable(id, 1));
        guard.requireUsable(id, 2);
        jdbc.update("update crm.connection set status='DISABLED' where id=?", id);
        assertThrows(DikidiFailure.class, () -> guard.requireUsable(id, 2));
    }
    @Test void unavailableStorageNeverAllowsUnfencedExternalWork() {
        var guard = new DikidiRuntimeGuard(jdbc, () -> { throw new IllegalStateException("storage unavailable"); });
        var error = assertThrows(DikidiFailure.class, () -> guard.requireUsable(UUID.randomUUID()));
        assertTrue(error.notDispatched());
    }
    @Test void oneOwnerHoldsWhileSecondDegradesAndStaleLeaseIsReclaimable() {
        var connection = UUID.randomUUID();
        var first = new DikidiRuntimeGuard(jdbc, () -> { });
        var second = new DikidiRuntimeGuard(jdbc, () -> { });
        assertDoesNotThrow(() -> first.requireUsable(connection));
        assertDoesNotThrow(() -> first.requireUsable(connection));   // повторное заявление владельца бесплатно
        var failure = assertThrows(DikidiFailure.class, () -> second.requireUsable(connection));
        assertEquals(DikidiFailure.Code.TEMPORARY_FAILURE, failure.code());
        assertTrue(failure.notDispatched());                         // отказ до всякой отправки
        assertNotNull(failure.retryAt());
        assertEquals(first.ownerIdentity(), jdbc.queryForObject("select owner from crm.connectionruntime where connectionid=?",
                String.class, connection));
        // Живой heartbeat отобрать нельзя:
        first.heartbeat();
        assertThrows(DikidiFailure.class, () -> second.requireUsable(connection));
        // А истёкший (симуляция упавшего экземпляра) — можно:
        jdbc.update("update crm.connectionruntime set heartbeatat=now()-interval '120 seconds' where connectionid=?", connection);
        assertDoesNotThrow(() -> second.requireUsable(connection));
        assertEquals(second.ownerIdentity(), jdbc.queryForObject("select owner from crm.connectionruntime where connectionid=?",
                String.class, connection));
        assertThrows(DikidiFailure.class, () -> first.requireUsable(connection));
    }
    @Test void losingStandbyNeverWarmsForeignLeaseRow() {
        // Ревю 13.4: до правки каждый requireUsable проигравшего сдвигал heartbeatat
        // живой чужой строки, и после жёсткой гибели владельца перехват lease стал бы
        // недостижим (строку грели бы вечно). Теперь heartbeat стоит на месте.
        var connection = UUID.randomUUID();
        var first = new DikidiRuntimeGuard(jdbc, () -> { });
        var standby = new DikidiRuntimeGuard(jdbc, () -> { });
        first.requireUsable(connection);
        var before = jdbc.queryForObject(
                "select heartbeatat from crm.connectionruntime where connectionid=?",
                java.sql.Timestamp.class, connection);
        for (int retry = 0; retry < 3; retry++)   // цикл ретраев standby «в фоне»
            assertThrows(DikidiFailure.class, () -> standby.requireUsable(connection));
        assertEquals(first.ownerIdentity(), jdbc.queryForObject(
                "select owner from crm.connectionruntime where connectionid=?", String.class, connection));
        assertEquals(before, jdbc.queryForObject(
                "select heartbeatat from crm.connectionruntime where connectionid=?",
                java.sql.Timestamp.class, connection));
        standby.release();                         // чужую строку освобождать нечем
        assertEquals(before, jdbc.queryForObject(
                "select heartbeatat from crm.connectionruntime where connectionid=?",
                java.sql.Timestamp.class, connection));
    }
    @Test void releaseConnectionRemovesLeaseRowsOfDeletedConnection() {
        // Ревю 13.4: после удаления подключения его lease-строка — мусор при любом владельце.
        var connection = UUID.randomUUID();
        var guard = new DikidiRuntimeGuard(jdbc, () -> { });
        guard.requireUsable(connection);
        guard.releaseConnection(connection);
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.connectionruntime where connectionid=?",
                Integer.class, connection));
    }
    @Test void heartbeatOnlyTouchesOwnedRowsAndReleaseFreesTheConnection() {
        var first = new DikidiRuntimeGuard(jdbc, () -> { });
        var mine = UUID.randomUUID();
        var other = UUID.randomUUID();
        first.requireUsable(mine);
        // Чужая строка не греется этим экземпляром:
        jdbc.update("insert into crm.connectionruntime(connectionid,owner,heartbeatat) values(?, 'ghost#other', now()-interval '120 seconds')", other);
        first.heartbeat();
        assertEquals("ghost#other", jdbc.queryForObject("select owner from crm.connectionruntime where connectionid=?", String.class, other));
        first.release();
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.connectionruntime where connectionid=?", Integer.class, mine));
    }
    @Test void storageNotReadyRefusesExternalWorkWithoutClaims() {
        var connection = UUID.randomUUID();
        var guard = new DikidiRuntimeGuard(jdbc, () -> { throw new IllegalStateException("not ready"); });
        assertThrows(DikidiFailure.class, () -> guard.requireUsable(connection));
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.connectionruntime where connectionid=?",
                Integer.class, connection));
    }
}
