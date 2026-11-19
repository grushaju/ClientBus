package kit.penny.clientbus.integration.dikidi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import static kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.*;

/**
 * Единственный владелец HTTP-сессий DIKIDI (13.4). Ключ сессии —
 * (connectionId, credentialVersion): CRM-адаптер и чатовый коннектор входят
 * один раз на версию учётных данных и используют один ограничитель; локальная
 * остановка одного потребителя не закрывает транспорт другого — close владеет
 * только сборка (Spring destroyMethod), адаптеры клиент не закрывают.
 *
 * Механика перенесена из DikidiProvider 13.x без изменений: tryLock 2с,
 * deadline операции 75с, вытеснение idle-сессий 20 мин, ёмкость 128,
 * fail-fast регулятор на каждом физическом запросе.
 */
public final class DikidiClient implements AutoCloseable {
    public record Settings(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        public Settings {
            if (connectTimeout == null || requestTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout.isNegative() || requestTimeout.isZero() || maxResponseBytes < 1024)
                throw new IllegalArgumentException("Invalid DIKIDI settings");
        }
    }
    /** Сессия входа под замком: каждый call учтён регулятором и маппится в техническую ошибку. */
    public interface Session {
        long deadline();
        /**
         * Помечает сессию неаутентифицированной (без закрытия транспорта), чтобы
         * следующий execute гарантированно вошёл заново. Используется, когда
         * вспомогательное чтение увидело AUTH_REQUIRED, а основной снимок валиден.
         */
        void invalidateAuthentication();
        /** CRM-профиль; бросает DikidiFailure на не-200. */
        String call(String method, String address, List<Map.Entry<String, String>> form, boolean ajax,
                    String company, DikidiTransport.Observer observer);
        /** Сырой ответ с профилем (чат-проверки смотрят статус/тело сами). */
        DikidiTransport.Response exchange(String method, String address, List<Map.Entry<String, String>> form,
                                          boolean ajax, String company, RequestProfile profile,
                                          DikidiTransport.Observer observer);
        /** multipart-загрузка файла (C07): тот же учёт регулятора/permit'ов, что у exchange. */
        DikidiTransport.Response upload(String address, String fieldName, String fileName, String contentType,
                                        byte[] content, String company, RequestProfile profile);
    }
    @FunctionalInterface public interface Work<T> { T apply(Session session); }

    private record Key(UUID connectionId, long version) {}
    private static final class Managed {
        final ReentrantLock lock = new ReentrantLock();
        final DikidiTransport transport;
        final UUID connectionId;
        final long credentialVersion;
        volatile boolean retired;
        volatile long lastUsed = System.nanoTime();
        boolean authenticated;
        Managed(DikidiTransport transport, UUID connectionId, long version) {
            this.transport = transport; this.connectionId = connectionId; this.credentialVersion = version;
        }
    }
    private final Settings settings;
    private final Supplier<DikidiTransport> factory;
    private final DikidiRequests traffic;
    private final Map<Key, Managed> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestVersions = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final JsonMapper json = JsonMapper.builder().build();
    private Semaphore requests = new Semaphore(4);
    private DikidiSessionGate gate = DikidiSessionGate.NOOP;
    /** Единственный активный runtime на соединение (lease/heartbeat у сборки). */
    public DikidiClient sessionGate(DikidiSessionGate value) { gate = java.util.Objects.requireNonNull(value); return this; }

    public DikidiClient(Settings settings) { this(settings, DikidiRequests.Settings.defaults(), DikidiRequests.memory()); }
    /** Тестовый шов без ограничений частот: фейковый транспорт поверх общего пула сессий. */
    public DikidiClient(Settings settings, Supplier<DikidiTransport> factory) {
        this(settings, factory, new DikidiRequests(new DikidiRequests.Settings(1e9, 100000, 1024, Duration.ofSeconds(30), Duration.ofMinutes(15)), DikidiRequests.memory()));
    }
    public DikidiClient(Settings settings, DikidiRequests.Settings requests, DikidiTrafficPort pauses) {
        this(settings, () -> new JdkDikidiTransport(settings.connectTimeout(), settings.maxResponseBytes()),
                new DikidiRequests(requests, pauses));
    }
    /** Тестовый/сборочный шов: фейковый транспорт поверх общего пула сессий. */
    public DikidiClient(Settings settings, Supplier<DikidiTransport> factory, DikidiRequests traffic) {
        this.settings = settings; this.factory = factory; this.traffic = traffic;
    }
    public DikidiClient parallelism(int value) {
        if (value < 1 || value > 64 || !sessions.isEmpty()) throw new IllegalArgumentException("Invalid DIKIDI parallelism");
        requests = new Semaphore(value); return this;
    }
    public Instant retryAt(UUID connectionId) { return traffic.retryAt(connectionId); }
    public DikidiRequests.Diagnostics diagnostics() { return traffic.diagnostics(); }
    public Set<UUID> activeConnections() {
        Set<UUID> ids = new LinkedHashSet<>();
        sessions.keySet().forEach(key -> ids.add(key.connectionId()));
        return ids;
    }

    /**
     * Guarantee-login + работа; повтор один раз только после AUTH_REQUIRED
     * (безопасно лишь для read-only работ — вызывающий обязан это гарантировать).
     */
    public <T> T execute(DikidiCredential access, Instant until, Work<T> work) {
        gate.requireUsable(access.connectionId(), access.credentialVersion());
        traffic.check(access.connectionId());
        long started = System.nanoTime(), deadline = started + Duration.ofSeconds(75).toNanos();
        if (until != null) {
            var remaining = Duration.between(Instant.now(), until);
            if (remaining.isNegative() || remaining.isZero()) throw new DikidiFailure(TEMPORARY_FAILURE);
            if (remaining.compareTo(Duration.ofSeconds(75)) < 0) deadline = Math.min(deadline, started + remaining.toNanos());
        }
        var session = session(access);
        try {
            if (!session.lock.tryLock(2, TimeUnit.SECONDS)) throw new DikidiFailure(CONNECTION_BUSY);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE); }
        try {
            // An initial login failure is not an expired-session read.
            if (!session.authenticated) login(session, access, deadline);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (!session.authenticated) login(session, access, deadline);
                    return work.apply(handle(session, deadline));
                } catch (DikidiFailure error) {
                    if (error.code() != AUTH_REQUIRED) throw error;
                    session.authenticated = false;
                    if (attempt != 0) throw error;
                }
            }
            throw new DikidiFailure(AUTH_REQUIRED);
        } finally { session.lastUsed = System.nanoTime(); session.lock.unlock(); }
    }
    /**
     * Одна попытка без auth-повтора: изменяющие операции никогда не повторяются
     * восстановлением сессии (восстановление сессии не имеет права перечитать и
     * переотправить намерение).
     */
    public <T> T executeOnce(DikidiCredential access, Instant until, Work<T> work) {
        gate.requireUsable(access.connectionId(), access.credentialVersion());
        traffic.check(access.connectionId());
        long started = System.nanoTime(), deadline = started + Duration.ofSeconds(75).toNanos();
        if (until != null) { // ревю 13.4: симметрично execute() — null означает «без caller deadline»
            var remaining = Duration.between(Instant.now(), until);
            if (remaining.isNegative() || remaining.isZero()) throw new DikidiFailure(TEMPORARY_FAILURE);
            if (remaining.compareTo(Duration.ofSeconds(75)) < 0) deadline = Math.min(deadline, started + remaining.toNanos());
        }
        var session = session(access);
        try { if (!session.lock.tryLock(2, TimeUnit.SECONDS)) throw new DikidiFailure(CONNECTION_BUSY); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE); }
        try {
            if (!session.authenticated) login(session, access, deadline);
            return work.apply(handle(session, deadline));
        } catch (DikidiFailure error) { if (error.code() == AUTH_REQUIRED) session.authenticated = false; throw error; }
        finally { session.lastUsed = System.nanoTime(); session.lock.unlock(); }
    }

    private Managed session(DikidiCredential access) {
        var key = new Key(access.connectionId(), access.credentialVersion());
        synchronized (sessions) {
            if (closed || access.credentialVersion() < latestVersions.getOrDefault(access.connectionId(), access.credentialVersion()))
                throw new DikidiFailure(TEMPORARY_FAILURE, null, true);
            latestVersions.merge(access.connectionId(), access.credentialVersion(), Math::max);
            sessions.entrySet().removeIf(entry -> {
                var value = entry.getValue();
                if (System.nanoTime() - value.lastUsed < Duration.ofMinutes(20).toNanos() || !value.lock.tryLock()) return false;
                try { value.retired = true; value.transport.close(); return true; } finally { value.lock.unlock(); }
            });
            var session = sessions.get(key);
            if (session != null) return session;
            if (sessions.size() >= 128) throw new DikidiFailure(CONNECTION_BUSY);
            session = new Managed(factory.get(), access.connectionId(), access.credentialVersion()); sessions.put(key, session); return session;
        }
    }

    private Session handle(Managed session, long deadline) {
        return new Session() {
            @Override public long deadline() { return deadline; }
            @Override public void invalidateAuthentication() { session.authenticated = false; }
            @Override public String call(String method, String address, List<Map.Entry<String, String>> form,
                                         boolean ajax, String company, DikidiTransport.Observer observer) {
                var result = send(session, method, address, form, ajax, company, RequestProfile.CRM, deadline, observer);
                mapStatus(result, address, settings.maxResponseBytes());
                return result.body();
            }
            @Override public DikidiTransport.Response exchange(String method, String address,
                    List<Map.Entry<String, String>> form, boolean ajax, String company,
                    RequestProfile profile, DikidiTransport.Observer observer) {
                return send(session, method, address, form, ajax, company, profile, deadline, observer);
            }
            @Override public DikidiTransport.Response upload(String address, String fieldName, String fileName,
                    String contentType, byte[] content, String company, RequestProfile profile) {
                requireActive(session);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE);
                boolean acquired = false;
                try {
                    acquired = requests.tryAcquire(1, TimeUnit.SECONDS);
                    if (!acquired) throw new DikidiFailure(CONNECTION_BUSY);
                    remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE, null, true);
                    return session.transport.upload(URI.create(address), fieldName, fileName, contentType, content,
                            company, profile, Duration.ofNanos(Math.min(remaining, settings.requestTimeout().toNanos())),
                            new DikidiTransport.Observer() {
                                public void beforeSend() { traffic.acquire(session.connectionId); }
                                public void dispatched() { traffic.dispatched(); }
                                public void response(DikidiTransport.Response response) { traffic.response(session.connectionId, response); }
                            });
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE); }
                finally { if (acquired) requests.release(); }
            }
        };
    }
    static void mapStatus(DikidiTransport.Response result, String address, int maxResponseBytes) {
        if (result.status() == 429) throw new DikidiFailure(RATE_LIMITED);
        if (result.status() == 401 || result.status() == 400 && address.startsWith("https://auth.")) throw new DikidiFailure(AUTH_REQUIRED);
        if (result.status() == 403) throw new DikidiFailure(ACCESS_DENIED);
        if (result.status() >= 500) throw new DikidiFailure(TEMPORARY_FAILURE);
        if (result.status() != 200 || result.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxResponseBytes)
            throw new DikidiFailure(INVALID_RESPONSE);
    }
    private DikidiTransport.Response send(Managed session, String method, String address,
            List<Map.Entry<String, String>> form, boolean ajax, String company,
            RequestProfile profile, long deadline, DikidiTransport.Observer caller) {
        requireActive(session);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE);
        boolean acquired = false;
        try {
            acquired = requests.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) throw new DikidiFailure(CONNECTION_BUSY);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new DikidiFailure(TEMPORARY_FAILURE, null, true);
            var result = session.transport.exchange(method, URI.create(address), form, ajax, company, profile,
                    Duration.ofNanos(Math.min(remaining, settings.requestTimeout().toNanos())),
                    new DikidiTransport.Observer() {
                        public void beforeSend() { traffic.acquire(session.connectionId); if (caller != null) caller.beforeSend(); }
                        public void dispatched() { traffic.dispatched(); if (caller != null) caller.dispatched(); }
                        public void response(DikidiTransport.Response response) { traffic.response(session.connectionId, response); if (caller != null) caller.response(response); }
                    });
            return result;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DikidiFailure(TEMPORARY_FAILURE); }
        finally { if (acquired) requests.release(); }
    }

    private void login(Managed session, DikidiCredential access, long deadline) {
        session.authenticated = false;
        var sessionHandle = handle(session, deadline);
        String publicPage = sessionHandle.call("GET", "https://dikidi.ru/ru/", null, false, null, null);
        String csrf = DikidiLoginPage.parse(publicPage).hidden().get("telegram_csrf");
        if (csrf == null || csrf.isBlank()) throw new DikidiFailure(INVALID_RESPONSE);
        var checked = tree(sessionHandle.call("POST", "https://auth.dikidi.ru/ajax/check/auth/",
                List.of(Map.entry("number", access.login()), Map.entry("telegram_csrf", csrf)), true, null, null));
        var fields = new LinkedHashMap<>(DikidiLoginPage.parse(checked.path("html").asString("")).hidden());
        if (!fields.containsKey("csrf") && !checked.path("csrf").asString("").isBlank())
            fields.put("csrf", checked.path("csrf").asString());
        fields.putIfAbsent("telegram_csrf", csrf);
        fields.put("number", access.login()); fields.put("pdAgreement", "1");
        char[] secret = access.secret();
        try {
            fields.put("password", new String(secret));
            JsonNode authenticated = tree(sessionHandle.call("POST", "https://auth.dikidi.ru/ajax/user/auth/",
                    new ArrayList<>(fields.entrySet()), true, null, null));
            if (!authenticated.path("callback").toString().contains("sw.auth.complete")) throw new DikidiFailure(AUTH_REQUIRED);
            session.authenticated = true;
        } finally { java.util.Arrays.fill(secret, '\0'); fields.clear(); }
    }
    private JsonNode tree(String body) {
        JsonNode node;
        try { node = json.readTree(body); }
        catch (RuntimeException error) { throw new DikidiFailure(INVALID_RESPONSE); }
        if (!node.isObject()) throw new DikidiFailure(INVALID_RESPONSE);
        return node;
    }

    /** Инвалидация соединения: рушит сессии всех версий (смена секрета, отзыв доступа). */
    public void invalidate(UUID connectionId) {
        synchronized (sessions) {
            sessions.entrySet().removeIf(entry -> {
                if (!entry.getKey().connectionId().equals(connectionId)) return false;
                entry.getValue().retired = true;
                entry.getValue().transport.close(); return true;
            });
        }
    }
    @Override public void close() {
        synchronized (sessions) {
            closed = true;
            sessions.values().forEach(s -> { s.retired = true; s.transport.close(); });
            sessions.clear(); latestVersions.clear();
        }
    }

    private void requireActive(Managed session) {
        if (closed || session.retired
                || session.credentialVersion < latestVersions.getOrDefault(session.connectionId, session.credentialVersion))
            throw new DikidiFailure(TEMPORARY_FAILURE, null, true);
        gate.requireUsable(session.connectionId, session.credentialVersion);
    }
}
