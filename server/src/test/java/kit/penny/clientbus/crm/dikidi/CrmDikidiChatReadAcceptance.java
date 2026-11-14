package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.crm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 13.1 opt-in only: re-verifies the C01-C05 chat protocol surface with reads and no mutations.
 * Reuses the same owner login but every chat host check is local to this probe; responses are reported
 * as structure, counters and formats only — no names, texts, identifiers, cookies or raw bodies.
 * A bounded discovery phase records the request shape (shell visit, referer, token domain) that the
 * provider accepts today; later protocol checks are strict about that evidence.
 */
@EnabledIfSystemProperty(named = "clientbus.crm.acceptance.live", matches = "true")
class CrmDikidiChatReadAcceptance {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CALL_LIMIT = 40;

    private record Res(int status, String body) {}

    @Test void reverifyChatProtocolReads() throws Exception {
        try (var context = new SpringApplicationBuilder(CrmSettings.class, CrmSecretCipher.class)
                .web(WebApplicationType.NONE).logStartupInfo(false)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF").run()) {
            var env = context.getEnvironment();
            DriverManager.setLoginTimeout(5);
            try (var db = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"))) {
                db.setReadOnly(true);
                db.setAutoCommit(false);
                try (var statement = db.prepareStatement("""
                        select c.id,c.organizationid,c.credentialversion,c.login,c.secretkeyid,c.secretciphertext,p.externalid
                        from crm.connection c join crm.project p on p.connectionid=c.id
                        where p.enabled and c.provider='DIKIDI' and c.status<>'DISABLED'
                            and (?::uuid is null or p.id=?::uuid) order by p.id limit 2
                        """)) {
                    String project = System.getProperty("clientbus.crm.acceptance.projectId");
                    if (project != null) UUID.fromString(project);
                    statement.setString(1, project);
                    statement.setString(2, project);
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "No enabled DIKIDI project in the configured database.");
                        UUID connection = rows.getObject(1, UUID.class), organization = rows.getObject(2, UUID.class);
                        String login = rows.getString(4), key = rows.getString(5), company = rows.getString(7);
                        byte[] encrypted = rows.getBytes(6);
                        assertFalse(rows.next(), "Several projects are enabled; select clientbus.crm.acceptance.projectId explicitly.");
                        db.rollback();
                        char[] secret = context.getBean(CrmSecretCipher.class).decrypt(organization, connection, key, encrypted);
                        Arrays.fill(encrypted, (byte) 0);
                        try {
                            probe(new String(secret), login, company);
                        } finally {
                            Arrays.fill(secret, '\0');
                        }
                    }
                }
            }
        }
    }

    private void probe(String password, String login, String company) {
        var calls = new AtomicInteger();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            String publicPage = exchange(client, calls, "GET", "https://dikidi.ru/ru/", null, false, null, false).body();
            String csrf = DikidiHtml.parse(publicPage).hidden.get("telegram_csrf");
            assertNotNull(csrf, "Login page lost the csrf field.");
            var checked = JSON.readTree(exchange(client, calls, "POST", "https://auth.dikidi.ru/ajax/check/auth/",
                    List.of(Map.entry("number", login), Map.entry("telegram_csrf", csrf)), true, null, false).body());
            var fields = new LinkedHashMap<>(DikidiHtml.parse(checked.path("html").asString("")).hidden);
            if (!fields.containsKey("csrf") && !checked.path("csrf").asString("").isBlank())
                fields.put("csrf", checked.path("csrf").asString());
            fields.putIfAbsent("telegram_csrf", csrf);
            fields.put("number", login);
            fields.put("pdAgreement", "1");
            fields.put("password", password);
            var authed = JSON.readTree(exchange(client, calls, "POST", "https://auth.dikidi.ru/ajax/user/auth/",
                    new ArrayList<>(fields.entrySet()), true, null, false).body());
            fields.clear();
            assertTrue(authed.path("callback").toString().contains("sw.auth.complete"), "Owner login failed.");
            System.out.println("CHAT login=PASSED");

            var shell = exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/ajax/chat/page/?company=" + enc(company),
                    null, true, "https://dikidi.ru/ru/owner/chat/", false);
            System.out.printf("CHAT C01 envelope=status%d jsonShape=%s%n", shell.status(), shell.body().trim().startsWith("{"));

            // Discovery: the chat web client loads a full owner page first; record only structural facts.
            // -Dclientbus.crm.acceptance.chatSkipShell=true tests whether that visit is a session prerequisite.
            String pageBody = "";
            int pageStatus = -1;
            if (!Boolean.getBoolean("clientbus.crm.acceptance.chatSkipShell")) {
                var page = exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/chat/?company=" + enc(company),
                        null, false, "https://dikidi.ru/ru/", false);
                pageBody = page.body();
                pageStatus = page.status();
            }
            var iframe = Pattern.compile("iframe[^>]{0,400}?src=\"https?://([^/\"']+)").matcher(pageBody);
            System.out.printf("CHAT shell status=%d bodyLen=%d iframeHost=%s%n", pageStatus, pageBody.length(),
                    iframe.find() ? iframe.group(1) : "-");

            String listUrl = "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company=" + enc(company) + "&time=0&msg=true&query=";
            String[] referers = {
                    "https://dikidi.ru/ru/owner/chat/?company=" + enc(company),
                    "https://dikidi.ru/ru/owner/chat/",
                    "https://chat.dikidi.ru/",
            };
            Res list = null;
            String chatReferer = null;
            for (String referer : referers) {
                list = exchange(client, calls, "GET", listUrl, null, true, referer, true);
                System.out.printf("CHAT C02 probe referer=%s status=%d%n", referer, list.status());
                if (list.status() == 200) {
                    chatReferer = referer;
                    break;
                }
            }
            assertNotNull(chatReferer, "C02 was rejected by every accepted referer variant (statuses logged above).");
            var listTree = tree(list);
            var dialogs = listTree.path("data").path("list");
            assertTrue(dialogs.isArray(), "C02 data.list must be an array.");
            System.out.printf("CHAT C02 dialogs=%d favorites=%d more=%s%n", dialogs.size(),
                    listTree.path("data").path("favorites").isArray() ? listTree.path("data").path("favorites").size() : -1,
                    listTree.path("data").path("more").asString("?"));

            var seenUsers = new HashMap<String, Integer>();
            var inboundUsers = new HashMap<String, TreeSet<String>>();
            int clientDialogs = 0, supportLike = 0;
            for (var dialog : dialogs) {
                String id = dialog.path("id").asString("");
                assertTrue(id.matches("[1-9][0-9]{0,17}"), "C02 dialog id must be a plain numeric string.");
                var detail = tree(exchange(client, calls, "GET", "https://chat.dikidi.ru/api/dialog/get/?dialog="
                        + id + "&messages=1&users=1", null, true, chatReferer, false));
                var users = detail.path("data").path("users");
                var roles = new TreeSet<String>();
                int userCount = 0;
                boolean scalarUsers = true;
                for (var user : users) {
                    userCount++;
                    if (user.isObject()) {
                        scalarUsers = false;
                        roles.add(user.path("role").asString("-"));
                        seenUsers.merge(user.path("id").asString(""), 1, Integer::sum);
                    } else seenUsers.merge(user.asString("?"), 1, Integer::sum);
                }
                if (userCount > 2) supportLike++;
                else clientDialogs++;
                var message = detail.path("data").path("messages");
                var dialogInbound = inboundUsers.computeIfAbsent(id, k -> new TreeSet<>());
                if (message.isArray()) message.forEach(m -> dialogInbound.add(m.asString("")));
                System.out.printf("CHAT C04 dialogIdDigits=%d dialogKeys=%s users=%d usersScalar=%s roles=%s dataKeys=%s userKeys=%s msgShape=%s%n",
                        id.length(), keys(dialog), userCount, scalarUsers, roles,
                        keys(detail.path("data")),
                        users.isArray() && userCount > 0 ? keys(users.get(0)) : "-",
                        message.isArray() && message.size() > 0 ? message.get(0).size() > 5 : "empty");

                int pages = 0, messages = 0, nonEmpty = 0;
                String last = null;
                boolean lastMore = false;
                do {
                    var probe = exchange(client, calls, "GET", "https://chat.dikidi.ru/api/dialog/messages/?dialog="
                            + id + "&limit=20" + (last == null ? "" : "&last=" + last) + "&msg_full=true", null, true, chatReferer, false);
                    var batch = tree(probe).path("data").path("list").isArray()
                            ? tree(probe).path("data").path("list") : tree(probe).path("data").path("messages");
                    pages++;
                    if (batch.isArray() && batch.size() > 0) {
                        nonEmpty++;
                        messages += batch.size();
                        for (var m : batch) {
                            var uid = m.path("user_id").asString("");
                            if (!uid.isEmpty() && !"1".equals(m.path("out").asString(""))) dialogInbound.add(uid);
                        }
                        var oldest = batch.get(batch.size() - 1);
                        last = oldest.path("id").asString("");
                        lastMore = oldest.path("more").asBoolean(false);
                        if (nonEmpty == 1) {
                            String date = oldest.path("date").asString(oldest.path("created_at").asString(""));
                            System.out.printf("CHAT C05 dateFormat=%s epochLike=%s textPresent=%s outFlag=%s attachmentFields=%s msgKeys=%s%n",
                                    date.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?") ? "yyyy-MM-dd HH:mm:ss"
                                            : date.matches("[0-9:]{4,}") ? "time-only" : "other",
                                    date.matches("[0-9]{9,11}") ? "yes" : "no",
                                    !oldest.path("text").asString("").isEmpty(), oldest.path("out").asString("?"),
                                    oldest.has("file") || oldest.has("attachment") || oldest.has("images"), keys(oldest));
                        }
                    } else last = null;
                } while (last != null && pages < 3);
                System.out.printf("CHAT C05 dialog pages=%d nonEmpty=%d messages=%d more=%s%n", pages, nonEmpty, messages, lastMore);
            }

            var unreadData = tree(exchange(client, calls, "GET",
                    "https://chat.dikidi.ru/api/dialogs/unreaded/?interface=owner&company=" + enc(company),
                    null, true, chatReferer, false)).path("data");
            System.out.printf("CHAT C03 shape=%s entries=%d total=%s%n",
                    unreadData.isArray() ? "list" : unreadData.isObject() ? "map" : "other",
                    unreadData.size(), unreadData.path("total").asString("-"));

            long shared = seenUsers.values().stream().filter(count -> count > 1).count();
            var inboundToDialogs = new HashMap<String, Integer>();
            inboundUsers.values().forEach(set -> set.forEach(u -> inboundToDialogs.merge(u, 1, Integer::sum)));
            long inboundShared = inboundToDialogs.values().stream().filter(count -> count > 1).count();
            System.out.printf("CHAT scope dialogs=%d clientLike=%d supportLike=%d distinctUsers=%d usersInSeveralDialogs=%d inboundUserIds=%d inboundSharedBetweenDialogs=%d%n",
                    dialogs.size(), clientDialogs, supportLike, seenUsers.size(), shared, inboundToDialogs.size(), inboundShared);
        } finally {
            client.shutdownNow();
            System.out.printf("CHAT httpCalls=%d limit=%d mutatingCalls=2 loginFlowOnly=true%n", calls.get(), CALL_LIMIT);
        }
    }

    private static JsonNode tree(Res res) {
        assertEquals(200, res.status(), "Chat endpoint returned a non-200 status.");
        var result = JSON.readTree(res.body());
        var error = result.path("error");
        String code = error.isObject() ? error.path("code").asString("?") : error.asString("?");
        assertEquals("0", code, "Chat endpoint reported a non-zero error code (structure only).");
        return result;
    }

    private static Res exchange(HttpClient client, AtomicInteger calls, String method, String address,
            List<Map.Entry<String, String>> form, boolean ajax, String referer, boolean lenient) {
        var uri = URI.create(address);
        assertTrue("https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                        && Set.of("dikidi.ru", "auth.dikidi.ru", "chat.dikidi.ru").contains(uri.getHost())
                        && allowed(method + " " + uri.getPath()),
                "Only the allowlisted authentication and chat reads may run.");
        assertTrue(calls.incrementAndGet() <= CALL_LIMIT, "Chat probe request budget exceeded.");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 ClientBus")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("Accept", ajax ? "application/json, text/javascript, */*; q=0.01" : "text/html");
        if (referer != null) builder.header("Referer", referer);
        if (ajax) {
            builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://dikidi.ru");
            if (uri.getHost().startsWith("auth.")) builder.header("Referer", "https://dikidi.ru/ru/");
            var jar = cookies(client).getCookieStore();
            var hostToken = jar.get(URI.create("https://" + uri.getHost() + "/")).stream()
                    .filter(c -> "XSRF-TOKEN".equals(c.getName())).findFirst();
            (hostToken.isPresent() ? hostToken : jar.get(URI.create("https://dikidi.ru/")).stream()
                    .filter(c -> "XSRF-TOKEN".equals(c.getName())).findFirst())
                    .ifPresent(c -> builder.header("X-XSRF-TOKEN", URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)));
        }
        if (form == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .method(method, HttpRequest.BodyPublishers.ofString(form.stream()
                        .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).reduce((a, b) -> a + "&" + b).orElse("")));
        try {
            var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            var body = new String(result.body(), StandardCharsets.UTF_8);
            assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= 4194304, "Response exceeds the probe size bound.");
            if (result.statusCode() != 200 && !lenient)
                System.out.printf("CHAT DEBUG path=%s status=%d contentType=%s bodyLen=%d body=%s%n",
                        uri.getPath(), result.statusCode(),
                        result.headers().firstValue("Content-Type").orElse("-"), body.length(),
                        excerpt(body));
            return new Res(result.statusCode(), body);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail("Chat probe interrupted.");
            return null;
        } catch (Exception error) {
            fail("Chat probe transport failure on " + uri.getPath() + ": " + error.getClass().getSimpleName());
            return null;
        }
    }

    /** Structure-only excerpt: long token/cookie-like sequences are redacted. */
    private static String excerpt(String body) {
        var clean = body.replaceAll("[A-Za-z0-9+/=_%.-]{24,}", "<redacted>").replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(300, clean.length()));
    }

    private static CookieManager cookies(HttpClient client) {
        return (CookieManager) client.cookieHandler().orElseThrow();
    }

    private static boolean allowed(String call) {
        return switch (call) {
            case "GET /ru/", "POST /ajax/check/auth/", "POST /ajax/user/auth/",
                    "GET /ru/owner/chat/", "GET /ru/owner/ajax/chat/page/",
                    "GET /api/dialogs/list/", "GET /api/dialogs/unreaded/",
                    "GET /api/dialog/get/", "GET /api/dialog/messages/" -> true;
            default -> false;
        };
    }

    /** Sorted structural field names only, never values. */
    private static String keys(JsonNode node) {
        if (!node.isObject()) return "-";
        var names = new TreeSet<String>();
        for (var entry : node.properties()) names.add(entry.getKey());
        return names.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
