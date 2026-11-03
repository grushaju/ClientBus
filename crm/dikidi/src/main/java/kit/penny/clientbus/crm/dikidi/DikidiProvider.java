package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** Safe owner reads only. No booking endpoints are present. */
public final class DikidiProvider implements CrmProvider, AutoCloseable {
    public record Settings(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        public Settings {
            if (connectTimeout == null || requestTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout.isNegative() || requestTimeout.isZero() || maxResponseBytes < 1024)
                throw new IllegalArgumentException("Invalid DIKIDI settings");
        }
    }
    private record Key(UUID connectionId, long version) {}
    private static final class Session {
        final ReentrantLock lock = new ReentrantLock();
        final DikidiTransport transport;
        volatile long lastUsed = System.nanoTime();
        boolean authenticated;
        long authenticatedAt;
        Session(DikidiTransport transport) { this.transport = transport; }
    }
    private final Settings settings;
    private final Supplier<DikidiTransport> factory;
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore requests = new Semaphore(4);
    private final JsonMapper json = JsonMapper.builder().build();
    public DikidiProvider(Settings settings) {
        this(settings, () -> new JdkDikidiTransport(settings.connectTimeout(), settings.maxResponseBytes()));
    }
    public DikidiProvider(Settings settings, Supplier<DikidiTransport> factory) { this.settings = settings; this.factory = factory; }
    @Override public String code() { return "DIKIDI"; }

    private Session session(CrmAccess access) {
        var key = new Key(access.connectionId(), access.credentialVersion());
        synchronized (sessions) {
            sessions.entrySet().removeIf(entry -> {
                var value = entry.getValue();
                if (System.nanoTime() - value.lastUsed < Duration.ofMinutes(20).toNanos() || !value.lock.tryLock()) return false;
                try { value.transport.close(); return true; } finally { value.lock.unlock(); }
            });
            var session = sessions.get(key);
            if (session != null) return session;
            if (sessions.size() >= 128) throw new CrmFailure(RATE_LIMITED);
            session = new Session(factory.get()); sessions.put(key, session); return session;
        }
    }
    @FunctionalInterface private interface Read<T> { T apply(Session session, long deadline); }
    private <T> T execute(CrmAccess access, String project, boolean verify, Read<T> read) {
        long started = System.nanoTime(), deadline = started + Duration.ofSeconds(75).toNanos();
        var session = session(access);
        try {
            if (!session.lock.tryLock(2, TimeUnit.SECONDS)) throw new CrmFailure(RATE_LIMITED);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CrmFailure(TEMPORARY_FAILURE); }
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (!session.authenticated || (verify && session.authenticatedAt < started))
                        login(session, access, deadline);
                    return read.apply(session, deadline);
                } catch (CrmFailure error) {
                    if (error.code() != AUTH_REQUIRED || attempt != 0 || verify) throw error;
                    session.authenticated = false;
                }
            }
            throw new CrmFailure(AUTH_REQUIRED);
        } finally { session.lastUsed = System.nanoTime(); session.lock.unlock(); }
    }
    @Override public void verifyConnection(CrmAccess access) {
        execute(access, null, true, (session, deadline) -> null);
    }
    @Override public CrmProjectInfo inspectProject(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, false, (session, deadline) -> {
            catalog(session, externalProjectId, deadline);
            var profile = tree(call(session, "GET", "https://dikidi.ru/ru/owner/ajax/profile/index/?company=" + externalProjectId,
                    null, true, externalProjectId, deadline));
            String name = DikidiHtml.parse(profile.path("html").asString("")).heading();
            var online = tree(call(session, "GET", "https://dikidi.ru/mobile/ajax/newrecord/company_services?company="
                    + externalProjectId + "&array=1&lang=ru", null, true, externalProjectId, deadline));
            String currency = online.path("data").path("currency").path("iso").asString("");
            return new CrmProjectInfo(externalProjectId, name.isBlank() ? externalProjectId : name,
                    currency.matches("[A-Z]{3}") ? currency : null, null);
        });
    }
    @Override public CrmReadResult<CrmMaster> readMasters(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, false, (session, deadline) -> {
            JsonNode result = catalog(session, externalProjectId, deadline);
            return new CrmReadResult<>(parseMasters(result), Instant.now(), CrmReadResult.Completeness.COMPLETE, externalProjectId + ":MASTERS");
        });
    }
    @Override public CrmCatalogSnapshot readCatalog(CrmAccess access, String externalProjectId) {
        projectId(externalProjectId);
        return execute(access, externalProjectId, false, (session, deadline) -> {
            JsonNode result = catalog(session, externalProjectId, deadline, true);
            List<CrmMaster> masters = parseMasters(result);
            Set<String> masterIds = new HashSet<>(); masters.forEach(master -> masterIds.add(master.externalId()));
            JsonNode section = result.path("masters").path("services"), ids = section.path("ids"), data = section.path("data");
            if (!ids.isArray() || !(data.isObject() || data.isArray() && data.isEmpty()) || ids.size() != data.size())
                throw new CrmFailure(INVALID_RESPONSE);
            Set<String> expected = new HashSet<>();
            for (var id : ids) if (!expected.add(identifier(id))) throw new CrmFailure(INVALID_RESPONSE);
            Map<String, CrmCatalogService> services = new TreeMap<>();
            List<CrmServiceOffer> offers = new ArrayList<>();
            List<String> outerIds = new ArrayList<>(); data.properties().forEach(item -> outerIds.add(item.getKey()));
            Collections.sort(outerIds);
            for (String masterId : outerIds) {
                if (!masterIds.contains(masterId) || !expected.remove(masterId)) throw new CrmFailure(CONTEXT_MISMATCH);
                JsonNode rows = data.path(masterId);
                if (!(rows.isObject() || rows.isArray() && rows.isEmpty())) throw new CrmFailure(INVALID_RESPONSE);
                for (var property : rows.properties()) {
                    JsonNode row = property.getValue();
                    String serviceId = identifier(row.path("id"));
                    if (!serviceId.equals(property.getKey()) || !masterId.equals(identifier(row.path("master_id"))))
                        throw new CrmFailure(CONTEXT_MISMATCH);
                    String title = catalogText(row.path("title"));
                    if (title == null || title.isBlank()) throw new CrmFailure(INVALID_RESPONSE);
                    services.putIfAbsent(serviceId, new CrmCatalogService(serviceId, title, null, Map.of()));
                    Map<String, Object> state = new LinkedHashMap<>(), attributes = new LinkedHashMap<>();
                    attributes.put("title", title);
                    catalogFlag(row, "active", state); catalogFlag(row, "online", state);
                    catalogFlag(row, "floating", attributes);
                    String description = catalogText(row.path("description"));
                    if (description != null) attributes.put("description", description);
                    Long breakSeconds = catalogSeconds(row.path("break_duration"));
                    if (breakSeconds != null) attributes.put("breakDurationSeconds", breakSeconds);
                    Set<String> presentFields = new HashSet<>();
                    if (row.has("cost")) presentFields.add("price");
                    if (row.has("time")) presentFields.add("durationSeconds");
                    offers.add(new CrmServiceOffer(masterId, serviceId, catalogMoney(row.path("cost")), null,
                            catalogSeconds(row.path("time")), state, attributes, presentFields));
                    if (offers.size() > 20000) throw new CrmFailure(INVALID_RESPONSE);
                }
            }
            if (!expected.isEmpty()) throw new CrmFailure(INVALID_RESPONSE);
            return new CrmCatalogSnapshot(masters, new ArrayList<>(services.values()), offers,
                    Instant.now(), CrmReadResult.Completeness.COMPLETE, externalProjectId + ":CATALOG");
        });
    }
    private static String catalogText(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > 65536) throw new CrmFailure(INVALID_RESPONSE);
        return value.asString();
    }
    private static void catalogFlag(JsonNode row, String key, Map<String, Object> target) {
        JsonNode value = row.path(key);
        if (value.isMissingNode() || value.isNull() || value.isString() && value.asString().isBlank()) return;
        String text = value.asString();
        if (Set.of("1", "true").contains(text)) target.put(key, true);
        else if (Set.of("0", "false").contains(text)) target.put(key, false);
        else throw new CrmFailure(INVALID_RESPONSE);
    }
    private static BigDecimal catalogDecimal(JsonNode value) {
        if (value.isMissingNode() || value.isNull() || value.isString() && value.asString().isBlank()) return null;
        if (!(value.isNumber() || value.isString())) throw new CrmFailure(INVALID_RESPONSE);
        try {
            String text = value.asString().trim();
            if (text.length() > 64) throw new NumberFormatException();
            BigDecimal result = new BigDecimal(text);
            if (result.signum() < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static BigDecimal catalogMoney(JsonNode value) {
        BigDecimal amount = catalogDecimal(value);
        if (amount == null) return null;
        try {
            BigDecimal normalized = amount.stripTrailingZeros();
            if (normalized.scale() > 4 || normalized.precision() - (long) normalized.scale() > 15) throw new ArithmeticException();
            return normalized.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private static Long catalogSeconds(JsonNode value) {
        BigDecimal minutes = catalogDecimal(value);
        if (minutes == null) return null;
        try {
            if (minutes.stripTrailingZeros().scale() > 2) throw new ArithmeticException();
            if (minutes.compareTo(BigDecimal.valueOf(Long.MAX_VALUE).divide(BigDecimal.valueOf(60), 16, RoundingMode.DOWN)) > 0)
                throw new ArithmeticException();
            return minutes.multiply(BigDecimal.valueOf(60)).longValueExact();
        }
        catch (ArithmeticException error) { throw new CrmFailure(INVALID_RESPONSE); }
    }
    private List<CrmMaster> parseMasters(JsonNode result) {
            JsonNode info = result.path("masters").path("info"), data = info.path("data"), ids = info.path("ids");
            if (!ids.isArray() || !(data.isObject() || data.isArray() && data.isEmpty()) || ids.size() != data.size() || data.size() > 5000)
                throw new CrmFailure(INVALID_RESPONSE);
            Set<String> expected = new HashSet<>();
            for (var id : ids) if (!expected.add(identifier(id))) throw new CrmFailure(INVALID_RESPONSE);
            List<CrmMaster> masters = new ArrayList<>();
            for (var property : data.properties()) {
                JsonNode value = property.getValue();
                String id = identifier(value.path("id"));
                if (!property.getKey().equals(id) || !expected.remove(id)) throw new CrmFailure(CONTEXT_MISMATCH);
                if (!"master".equals(value.path("item_type").asString("")) || !"1".equals(value.path("is_master").asString("")))
                    throw new CrmFailure(INVALID_RESPONSE);
                String title = value.path("title").asString("");
                if (title.isBlank()) title = (value.path("first_name").asString("") + " " + value.path("last_name").asString("")).trim();
                if (title.isBlank() || title.length() > 65536) throw new CrmFailure(INVALID_RESPONSE);
                masters.add(new CrmMaster(id, title, Map.of("itemType", "master")));
            }
            if (!expected.isEmpty()) throw new CrmFailure(INVALID_RESPONSE);
            return masters;
    }
    private JsonNode catalog(Session session, String project, long deadline) {
        return catalog(session, project, deadline, false);
    }
    private JsonNode catalog(Session session, String project, long deadline, boolean includeServices) {
        // The journal GET initializes project cookies before interpreting ACCESS_DENIED.
        call(session, "GET", "https://dikidi.ru/ru/owner/journal/?company=" + project, null, false, project, deadline);
        var form = new ArrayList<>(List.of(Map.entry("request[get][]", "company.accesses"),
                Map.entry("request[get][]", "masters.info"), Map.entry("request[options][company][accesses][]", project)));
        if (includeServices) form.add(Map.entry("request[get][]", "masters.services"));
        JsonNode result = tree(call(session, "POST", "https://dikidi.ru/ru/owner/ajax/journal/api/?company=" + project,
                form, true, project, deadline));
        JsonNode projectAccess = result.path("company").path("accesses").path(project);
        if (!projectAccess.isContainer() || projectAccess.isEmpty()
                || !result.path("masters").path("info").isObject()) throw new CrmFailure(CONTEXT_MISMATCH);
        return result;
    }
    private void login(Session session, CrmAccess access, long deadline) {
        session.authenticated = false;
        String publicPage = call(session, "GET", "https://dikidi.ru/ru/", null, false, null, deadline);
        String csrf = DikidiHtml.parse(publicPage).hidden.get("telegram_csrf");
        if (csrf == null || csrf.isBlank()) throw new CrmFailure(INVALID_RESPONSE);
        var checked = tree(call(session, "POST", "https://auth.dikidi.ru/ajax/check/auth/",
                List.of(Map.entry("number", access.login()), Map.entry("telegram_csrf", csrf)), true, null, deadline));
        var fields = new LinkedHashMap<>(DikidiHtml.parse(checked.path("html").asString("")).hidden);
        if (!fields.containsKey("csrf") && !checked.path("csrf").asString("").isBlank())
            fields.put("csrf", checked.path("csrf").asString());
        fields.putIfAbsent("telegram_csrf", csrf);
        fields.put("number", access.login()); fields.put("pdAgreement", "1");
        char[] secret = access.secret();
        try {
            fields.put("password", new String(secret));
            JsonNode authenticated = tree(call(session, "POST", "https://auth.dikidi.ru/ajax/user/auth/",
                    new ArrayList<>(fields.entrySet()), true, null, deadline));
            if (!authenticated.path("callback").toString().contains("sw.auth.complete")) throw new CrmFailure(AUTH_REQUIRED);
            session.authenticated = true; session.authenticatedAt = System.nanoTime();
        } finally { Arrays.fill(secret, '\0'); fields.clear(); }
    }
    private String call(Session session, String method, String address, List<Map.Entry<String, String>> form,
                        boolean ajax, String project, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new CrmFailure(TEMPORARY_FAILURE);
        boolean acquired = false;
        try {
            acquired = requests.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) throw new CrmFailure(RATE_LIMITED);
            var result = session.transport.exchange(method, URI.create(address), form, ajax, project,
                    Duration.ofNanos(Math.min(remaining, settings.requestTimeout().toNanos())));
            if (result.status() == 429) throw new CrmFailure(RATE_LIMITED);
            if (result.status() == 401 || result.status() == 400 && address.startsWith("https://auth.")) throw new CrmFailure(AUTH_REQUIRED);
            if (result.status() == 403) throw new CrmFailure(ACCESS_DENIED);
            if (result.status() >= 500) throw new CrmFailure(TEMPORARY_FAILURE);
            if (result.status() != 200 || result.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > settings.maxResponseBytes())
                throw new CrmFailure(INVALID_RESPONSE);
            return result.body();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CrmFailure(TEMPORARY_FAILURE); }
        finally { if (acquired) requests.release(); }
    }
    private JsonNode tree(String body) {
        try {
            JsonNode result = json.readTree(body), error = result.path("error");
            if (result.isNull() || !result.isContainer()) throw new CrmFailure(INVALID_RESPONSE);
            JsonNode code = error.isObject() ? error.path("code") : error;
            if (!code.isMissingNode() && !code.isNull() && !"0".equals(code.asString(""))) {
                String message = result.path("message").asString("") + error.path("message").asString("");
                if (message.toLowerCase(Locale.ROOT).matches("(?s).*(auth|авторизац|парол|вход).*")) throw new CrmFailure(AUTH_REQUIRED);
                throw new CrmFailure(ACCESS_DENIED);
            }
            return result;
        } catch (CrmFailure error) { throw error; }
        catch (RuntimeException error) {
            if (body.contains("name=\"password\"") || body.contains("name='password'")) throw new CrmFailure(AUTH_REQUIRED);
            throw new CrmFailure(INVALID_RESPONSE);
        }
    }
    private static void projectId(String id) { if (id == null || !id.matches("[0-9]{1,20}")) throw new CrmFailure(CONTEXT_MISMATCH); }
    private static String identifier(JsonNode node) {
        if (!node.isString() && !node.isIntegralNumber()) throw new CrmFailure(INVALID_RESPONSE);
        String value = node.asString("");
        if (value.isBlank() || value.length() > 128) throw new CrmFailure(INVALID_RESPONSE);
        return value;
    }
    @Override public void invalidate(UUID connectionId) {
        synchronized (sessions) {
            sessions.entrySet().removeIf(entry -> {
                if (!entry.getKey().connectionId().equals(connectionId)) return false;
                entry.getValue().transport.close(); return true;
            });
        }
    }
    @Override public void close() { synchronized (sessions) { sessions.values().forEach(s -> s.transport.close()); sessions.clear(); } }
}
