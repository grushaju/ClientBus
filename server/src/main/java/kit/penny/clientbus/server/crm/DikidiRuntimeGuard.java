package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import kit.penny.clientbus.integration.dikidi.DikidiSessionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 13.4: дешёвый guard единственного активного runtime технического подключения.
 * Lease-строка crm.connectionruntime(connectionid, owner, heartbeatat): владеет
 * тот, кто последним обновил heartbeat в пределах TTL. Второй экземпляр с тем же
 * connectionId получает явную деградацию (TEMPORARY_FAILURE с retryAt) и
 * ERROR-алерт вместо молчаливой двойной работы с общей сессией DIKIDI.
 * Полноценный claim/lease на многоэкземплярную топологию — 13.8.
 */
@Component
public class DikidiRuntimeGuard implements DikidiSessionGate {
    private static final Logger log = LoggerFactory.getLogger(DikidiRuntimeGuard.class);
    public static final String TABLE_DDL = """
            create table if not exists crm.connectionruntime(
                connectionid uuid,
                owner varchar(160) not null,
                heartbeatat timestamp with time zone not null,
                constraint connectionruntimepk primary key (connectionid))
            """;
    private static final String TTL = "interval '90 seconds'";
    private final JdbcTemplate jdbc;
    private final Runnable readyGate;
    private final String owner;
    private final java.util.Set<UUID> degraded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Autowired
    public DikidiRuntimeGuard(JdbcTemplate jdbc, CrmStorage storage) { this(jdbc, storage::requireReady); }
    public DikidiRuntimeGuard(JdbcTemplate jdbc, Runnable readyGate) {
        this.jdbc = jdbc; this.readyGate = readyGate;
        this.owner = java.lang.management.ManagementFactory.getRuntimeMXBean().getName() + "#" + UUID.randomUUID();
    }
    String ownerIdentity() { return owner; }
    @Override public void requireUsable(java.util.UUID connectionId) {
        if (!isReady()) throw new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE, null, true);
        try {
            // Ревю 13.4: heartbeat сдвигает только владеющая (или перехватываемая по TTL)
            // строка. Иначе непрерывный ретрай проигравшего standby вечно грел бы строку
            // мёртвого владельца и перехват протухшего lease был бы недостижим.
            jdbc.update("insert into crm.connectionruntime(connectionid,owner,heartbeatat) values(?,?,now())"
                            + " on conflict (connectionid) do update set"
                            + " owner=case when crm.connectionruntime.owner=excluded.owner"
                            + " or crm.connectionruntime.heartbeatat < now() - " + TTL + " then excluded.owner"
                            + " else crm.connectionruntime.owner end,"
                            + " heartbeatat=case when crm.connectionruntime.owner=excluded.owner"
                            + " or crm.connectionruntime.heartbeatat < now() - " + TTL + " then now()"
                            + " else crm.connectionruntime.heartbeatat end",
                    connectionId, owner);
            String holder = jdbc.queryForObject("select owner from crm.connectionruntime where connectionid=?", String.class, connectionId);
            if (owner.equals(holder)) {
                if (degraded.remove(connectionId)) log.info("DIKIDI runtime lease restored for connection {}", connectionId);
                return;
            }
            if (degraded.add(connectionId))
                log.error("ALERT DIKIDI_RUNTIME_LEASE_TAKEN: соединение {} уже обслуживается экземпляром {}; "
                        + "этот экземпляр переходит в деградацию, чтобы не дублировать внешние обращения. "
                        + "Остановите второй экземпляр или отключите соединение.", connectionId, holder);
            throw new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE,
                    Instant.now().plus(30, ChronoUnit.SECONDS), true);
        } catch (DikidiFailure failure) {
            throw failure;
        } catch (RuntimeException error) {
            log.warn("DIKIDI runtime lease check unavailable ({}); external request refused.", error.toString());
            throw new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE, null, true);
        }
    }

    @Override public void requireUsable(UUID connectionId, long credentialVersion) {
        requireUsable(connectionId);
        try {
            Boolean current = jdbc.queryForObject("select exists(select 1 from crm.connection "
                    + "where id=? and credentialversion=? and status<>'DISABLED')", Boolean.class,
                    connectionId, credentialVersion);
            if (!Boolean.TRUE.equals(current)) throw new DikidiFailure(DikidiFailure.Code.ACCESS_DENIED, null, true);
        } catch (DikidiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE, null, true); }
    }
    /** Поддержание heartbeat только для строк, которыми реально владеет этот runtime. */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void heartbeat() {
        if (!isReady()) return;
        try {
            int held = jdbc.update("update crm.connectionruntime set heartbeatat=now() where owner=?", owner);
            if (held == 0 && !degraded.isEmpty()) log.debug("DIKIDI runtime lease heartbeat found no owned rows.");
        } catch (RuntimeException error) {
            log.warn("DIKIDI runtime lease heartbeat failed: {}", error.toString());
        }
    }
    @PreDestroy public void release() {
        try { jdbc.update("delete from crm.connectionruntime where owner=?", owner); }
        catch (RuntimeException ignored) { /* строки переживут TTL при жёсткой остановке */ }
    }
    /** Ревю 13.4: после удаления самого подключения его lease-строки — мусор при любом владельце. */
    public void releaseConnection(java.util.UUID connectionId) {
        try { jdbc.update("delete from crm.connectionruntime where connectionid=?", connectionId); }
        catch (RuntimeException ignored) { /* та же отказоустивчивость, что у release() */ }
    }
    /** Живая lease-строка = technical connection активно используется этим или иным runtime. */
    public boolean activelyLeased(java.util.UUID connectionId) {
        if (!isReady()) return false;
        try {
            Integer held = jdbc.queryForObject("""
                    select count(*) from crm.connectionruntime
                    where connectionid=? and heartbeatat > now() - interval '90 seconds'
                    """, Integer.class, connectionId);
            return held != null && held > 0;
        } catch (RuntimeException error) {
            return false; // та же отказоустивчивость, что у requireUsable
        }
    }
    private boolean isReady() {
        try { readyGate.run(); return true; } catch (RuntimeException ready) { return false; }
    }
}
