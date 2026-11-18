package kit.penny.clientbus.crm.dikidi;

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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 13.9 opt-in only: разведка ключа, которым карточка клиента DIKIDI связана с его чатом.
 * Отвечает на четыре вопроса и только наблюдениями о форме ответа:
 *   1) есть ли в карточке клиента (R09) или в списке клиентов (R08) поле с id пользователя чата;
 *   2) отдаёт ли объект участника чата (C04) телефон клиента или id карточки CRM;
 *   3) равен ли служебный id визита (R05) id пользователя чата и чей это id — клиента или сотрудника;
 *   4) отдаёт ли агрегат известных клиентов (R07) что-нибудь сверх карточки.
 * Кандидаты id берутся из локальной БД только для сравнения в памяти. В вывод попадают имена
 * полей, форма значений, заполненность и булевы совпадения; сами значения, имена людей, телефоны,
 * куки и тела ответов не печатаются никогда. Проверка только чтением: ни одного изменяющего вызова.
 */
@EnabledIfSystemProperty(named = "clientbus.crm.acceptance.live", matches = "true")
class CrmDikidiClientIdentityAcceptance {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CALL_LIMIT = 60;
    private static final Pattern INPUT = Pattern.compile(
            "<(?:input|select|textarea)[^>]{0,600}?name\\s*=\\s*[\"']?([A-Za-z0-9_.\\[\\]-]{1,64})[\"']?[^>]{0,600}?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile("value\\s*=\\s*[\"']([^\"']{0,80})[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION = Pattern.compile("<option[^>]{0,300}?selected[^>]{0,300}?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("[\"']([A-Za-z_0-9.\\[\\]-]{1,40})[\"']\\s*[=:]\\s*[\"']?([0-9]{4,18})[\"']?");
    private static final Pattern DATA_ATTRIBUTE = Pattern.compile("\\s(data-[a-z-]{1,30})\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?:^|[^0-9])(?:\\+?7|8)[\\s(-]?\\(?9\\)?[\\s-]?[0-9]{3}[\\s-]?[0-9]{2}[\\s-]?[0-9]{2}(?:$|[^0-9])");

    private record Res(int status, String body) {}

    /** Итоги по формам карточки: только имена полей, формы значений и counters. */
    private static final class Form {
        final Set<String> names = new TreeSet<>();
        final Map<String, Integer> filled = new TreeMap<>();
        final Map<String, Set<String>> shapes = new TreeMap<>();
        final Set<String> hits = new TreeSet<>();
        int cards;
        void add(String name, String value, Ids ids) {
            names.add(name);
            var shape = shape(value);
            shapes.computeIfAbsent(name, key -> new TreeSet<>()).add(shape);
            if (!shape.equals("empty") && !shape.equals("absent")) filled.merge(name, 1, Integer::sum);
            var chat = ids.chatTag(value);
            if (chat != null) hits.add(name + "=" + chat);
            var client = ids.clientTag(value);
            if (client != null) hits.add(name + "=" + client);
        }
    }

    /** Кандидаты идентификаторов: сравнение в памяти, наружу — только метка совпадения. */
    private static final class Ids {
        final Set<String> clients = new LinkedHashSet<>();
        final Set<String> chats = new LinkedHashSet<>();
        final Set<String> visitedClients = new LinkedHashSet<>();
        final Set<String> cardPool = new HashSet<>();
        final Set<String> accessAccounts = new HashSet<>();
        final Map<String, String> roleOf = new HashMap<>();
        String chatTag(String value) {
            var clean = clean(value);
            if (clean == null || !chats.contains(clean)) return null;
            var role = roleOf.get(clean);
            return "chatId:" + (role == null ? "рольнеизвестна" : role)
                    + (accessAccounts.contains(clean) ? "(доступкабинета)" : "");
        }
        String clientTag(String value) {
            var clean = clean(value);
            return clean != null && cardPool.contains(clean) ? "crmClientId" : null;
        }
        private static String clean(String value) {
            if (value == null) return null;
            var trimmed = value.strip();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    @Test void probeClientIdentityKey() throws Exception {
        try (var context = new SpringApplicationBuilder(CrmSettings.class, CrmSecretCipher.class)
                .web(WebApplicationType.NONE).logStartupInfo(false)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF").run()) {
            var env = context.getEnvironment();
            DriverManager.setLoginTimeout(5);
            try (var db = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"))) {
                db.setReadOnly(true);
                db.setAutoCommit(false);
                String login = null, company = null, key = null;
                byte[] encrypted = null;
                UUID connection = null, organization = null, projectId = null;
                var ids = new Ids();
                try (var statement = db.prepareStatement("""
                        select c.id,c.organizationid,c.credentialversion,c.login,c.secretkeyid,c.secretciphertext,p.externalid,p.id
                        from crm.connection c join crm.project p on p.connectionid=c.id
                        where p.enabled and c.provider='DIKIDI' and c.status<>'DISABLED'
                            and (?::uuid is null or p.id=?::uuid) order by p.id limit 8
                        """)) {
                    String project = System.getProperty("clientbus.crm.acceptance.projectId");
                    if (project != null) UUID.fromString(project);
                    statement.setString(1, project);
                    statement.setString(2, project);
                    statement.setQueryTimeout(5);
                    var rows = statement.executeQuery();
                    assertTrue(rows.next(), "No enabled DIKIDI project in the configured database.");
                    connection = rows.getObject(1, UUID.class); organization = rows.getObject(2, UUID.class);
                    login = rows.getString(4); key = rows.getString(5); company = rows.getString(7);
                    encrypted = rows.getBytes(6); projectId = rows.getObject(8, UUID.class);
                    if (project == null) {
                        var others = new StringBuilder(String.valueOf(projectId));
                        while (rows.next()) others.append(',').append(rows.getObject(8, UUID.class));
                        if (others.toString().contains(",")) System.out.printf(
                                "IDENT projects=%s (первый выбран; перезапуск: -Dclientbus.crm.acceptance.projectId=<id>)%n", others);
                    }
                    rows.close();
                    readCandidates(db, projectId, company, ids);
                    db.rollback();
                }
                assertNotNull(projectId, "No enabled project was selected.");
                assertNotNull(company, "The selected project has no external company id.");
                char[] secret = context.getBean(CrmSecretCipher.class).decrypt(organization, connection, key, encrypted);
                Arrays.fill(encrypted, (byte) 0);
                try {
                    probe(new String(secret), login, company, ids);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }
        }
    }

    /** Локальные кандидаты и текущее состояние двух связей; наружу — только счётчики. */
    private static void readCandidates(java.sql.Connection db, java.util.UUID project, String company, Ids ids) throws SQLException {
        try (var totals = db.prepareStatement("""
                select count(*), count(*) filter (where clientid is not null)
                from crm.client where projectid=? and identitykind='PROJECT'
                """); var cards = db.prepareStatement("""
                select externalid from crm.client where projectid=? and identitykind='PROJECT'
                order by lastobservedat desc nulls last, externalid limit 5000
                """)) {
            for (var statement : List.of(totals, cards)) { statement.setQueryTimeout(5); statement.setObject(1, project); }
            var total = 0; var linked = 0;
            try (var rows = totals.executeQuery()) { if (rows.next()) { total = rows.getInt(1); linked = rows.getInt(2); } }
            try (var rows = cards.executeQuery()) {
                while (rows.next()) {
                    var external = rows.getString(1);
                    ids.cardPool.add(external);
                    if (ids.clients.size() < 12) ids.clients.add(external);
                }
            }
            System.out.printf("IDENT db clients=%d linkedToClient=%d cardPool=%d cardsToProbe=%d%n",
                    total, linked, ids.cardPool.size(), ids.clients.size());
        }
        try (var accounts = db.prepareStatement("""
                select distinct ca.externalid, ca.clientid is not null, ca.phone is not null
                from dbo.clientaccount ca
                join dbo.conversation cv on cv.clientaccountid=ca.clientaccountid
                join dbo.channelaccount ch on ch.channelaccountid=cv.channelaccountid
                where ca.channeltype='DIKIDI' and ch.externalid=?
                order by ca.externalid limit 400
                """)) {
            accounts.setQueryTimeout(5);
            accounts.setString(1, company);
            var total = 0; var linked = 0; var withPhone = 0;
            try (var rows = accounts.executeQuery()) {
                while (rows.next()) {
                    total++;
                    if (rows.getBoolean(2)) linked++;
                    if (rows.getBoolean(3)) withPhone++;
                    if (ids.chats.size() < 60) ids.chats.add(rows.getString(1));
                }
            }
            System.out.printf("IDENT db chatAccounts=%d linkedToClient=%d withPhone=%d pool=%d%n", total, linked, withPhone, ids.chats.size());
        }
        try (var pairs = db.prepareStatement("""
                select count(*) from crm.client c
                join dbo.clientaccount ca on ca.clientid=c.clientid and ca.channeltype='DIKIDI'
                where c.projectid=? and c.clientid is not null
                """)) {
            pairs.setQueryTimeout(5);
            pairs.setObject(1, project);
            try (var rows = pairs.executeQuery()) {
                System.out.printf("IDENT db pairsBySharedClient=%s (карточка и учётка с одним клиентом сегодня)%n",
                        rows.next() ? rows.getInt(1) : "?");
            }
        }
    }

    private void probe(String password, String login, String company, Ids ids) throws Exception {
        var calls = new AtomicInteger();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            var publicPage = exchange(client, calls, "GET", "https://dikidi.ru/ru/", null, false, null, false).body();
            var csrf = DikidiHtml.parse(publicPage).hidden.get("telegram_csrf");
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
            System.out.println("IDENT login=PASSED");

            chatSurface(client, calls, company, ids);      // сначала роли: без них сравнение визитов двусмысленно
            visitJournal(client, calls, company, ids);
            clientCards(client, calls, company, ids);
            clientData(client, calls, company, ids);
            clientList(client, calls, company, ids);
        } finally {
            client.shutdownNow();
            System.out.printf("IDENT httpCalls=%d limit=%d mutatingCalls=0 readsOnly=true%n", calls.get(), CALL_LIMIT);
        }
    }

    /** C02/C04: роли участников и всё, что чат знает о телефоне и о карточке CRM. */
    private void chatSurface(HttpClient client, AtomicInteger calls, String company, Ids ids) throws Exception {
        exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/ajax/chat/page/?company=" + enc(company), null, true,
                "https://dikidi.ru/ru/owner/chat/", false);
        exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/chat/?company=" + enc(company), null, false,
                "https://dikidi.ru/ru/", false);
        var referer = "https://dikidi.ru/ru/owner/chat/?company=" + enc(company);
        var list = tree(exchange(client, calls, "GET", "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company="
                + enc(company) + "&time=0&msg=true&query=", null, true, referer, true)).path("data").path("list");
        assertTrue(list.isArray(), "C02 data.list must be an array.");
        var listHits = new TreeSet<String>();
        textHits(list.toString(), ids, listHits);
        System.out.printf("IDENT C02 dialogs=%d listKeys=%s bridgeFields=%s%n", list.size(),
                list.isArray() && !list.isEmpty() ? keys(list.get(0)) : "-", listHits.isEmpty() ? "нет" : listHits);

        var userKeys = new TreeSet<String>();
        var messageKeys = new TreeSet<String>();
        var userPhone = new TreeSet<String>();
        var shapesOf = new TreeMap<String, Set<String>>();
        var crmHits = new TreeSet<String>();
        var dialogBridge = new TreeSet<String>();
        var roles = new TreeSet<String>();
        var classified = new HashSet<String>();
        var dialogs = 0;
        for (var dialog : list) {
            if (dialogs++ >= 4) break;
            var id = dialog.path("id").asString("");
            if (!id.matches("[1-9][0-9]{0,17}")) continue;
            var data = tree(exchange(client, calls, "GET", "https://chat.dikidi.ru/api/dialog/get/?dialog="
                    + id + "&users=1&messages=1", null, true, referer, false)).path("data");
            for (var user : elements(data.path("users").path("list"))) {
                if (!user.isObject()) continue;
                var role = user.path("role").asString("");
                // Объекты сообщений тоже несут user_name; участником считаем запись с ролью.
                if (role.isBlank() && (user.path("text").isValueNode() || !user.path("user_name").isValueNode())) continue;
                for (var entry : user.properties()) {
                    userKeys.add(entry.getKey());
                    var value = entry.getValue().isValueNode() ? entry.getValue().asString("") : "-";
                    shapesOf.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).add(shape(value));
                    if (PHONE.matcher(value).find() && value.matches("[0-9+()\\- ]{10,20}")) userPhone.add(entry.getKey());
                    var tag = ids.chatTag(value);
                    if (tag != null && !role.isBlank() && (entry.getKey().equals("id") || entry.getKey().equals("user_id"))) {
                        ids.roleOf.put(value.strip(), role);
                        classified.add(value);
                    }
                }
                roles.add(role.isBlank() ? "(пусто)" : role);
            }
            for (var message : elements(data.path("messages").path("list"))) {
                if (!message.isObject()) continue;
                for (var entry : message.properties()) messageKeys.add(entry.getKey());
            }
            var dialogOnly = new TreeSet<String>();
            textHits(data.path("dialog").toString(), ids, dialogOnly);
            textHits(data.path("users").toString(), ids, crmHits);
            for (var hit : new ArrayList<>(dialogOnly))
                if (hit.endsWith("=crmClientId")) {
                    crmHits.add(hit);
                    dialogOnly.remove(hit);
                }
            dialogBridge.addAll(dialogOnly);
        }
        System.out.printf("IDENT C04 dialogs=%d roles=%s classifiedOfPool=%d/%d%n", dialogs, roles, classified.size(), ids.chats.size());
        System.out.printf("IDENT C04 participantKeys=%s participantShapes=%s%n", userKeys, shapesOf);
        System.out.printf("IDENT C04 messageKeys=%s%n", messageKeys);
        System.out.printf("IDENT C04 phoneFields=%s crmClientIdFields=%s dialogFields=%s%n",
                userPhone.isEmpty() ? "нет" : userPhone, crmHits.isEmpty() ? "нет" : crmHits,
                dialogBridge.isEmpty() ? "нет" : dialogBridge);
    }

    /** R05 визитов: какие служебные id совпадают с id чата и чей это id — клиента или сотрудника. */
    private void visitJournal(HttpClient client, AtomicInteger calls, String company, Ids ids) throws Exception {
        exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/journal/?company=" + enc(company), null, false,
                "https://dikidi.ru/ru/", false);
        var to = LocalDate.now();
        var form = new ArrayList<>(List.of(
                Map.entry("request[get][]", "company.accesses"), Map.entry("request[get][]", "masters.info"),
                Map.entry("request[get][]", "masters.records"),
                Map.entry("request[options][company][accesses][]", company),
                Map.entry("request[options][masters][records][0][date_from]", to.minusDays(120).toString()),
                Map.entry("request[options][masters][records][0][date_to]", to.toString())));
        var body = exchange(client, calls, "POST", "https://dikidi.ru/ru/owner/ajax/journal/api/?company=" + enc(company),
                form, true, "https://dikidi.ru/ru/owner/journal/", false).body();
        // Держатели доступа кабинета: без этой выборки совпадение id в визите не отличить от id сотрудника.
        for (var entry : JSON.readTree(body).path("company").path("accesses").path(company).properties())
            ids.accessAccounts.add(entry.getKey());
        var keys = new TreeSet<String>();
        var hits = new TreeSet<String>();
        scan(JSON.readTree(body), keys, hits, ids);
        textHits(body, ids, hits);
        var objects = new ArrayList<JsonNode>();
        collectCardObjects(JSON.readTree(body), objects);
        var ownKeys = new TreeSet<String>();
        var pairFields = new TreeSet<String>();
        var withPhone = 0; var systemCard = 0; var pairs = 0; var knownCards = 0;
        for (var visit : objects) {
            ownKeys.addAll(keysSet(visit));
            var cardId = text(visit.path("client_id"));
            if (cardId != null) {
                ids.visitedClients.add(cardId);
                if (ids.cardPool.contains(cardId)) knownCards++;
            }
            if (text(visit.path("client_phone")) != null) withPhone++;
            if ("1".equals(text(visit.path("client_is_system")))) systemCard++;
            for (var field : List.of("added_user_id", "updated_user_id", "deleted_user_id", "came_id", "client_came_id", "user_id")) {
                var value = text(visit.path(field));
                if (value != null && ids.chats.contains(value)) {
                    pairFields.add(field);
                    pairs++;
                }
            }
        }
        System.out.printf("IDENT R05 companyAccessAccounts=%d учёткиЧатаСдоступомКабинета=%d%n",
                ids.accessAccounts.size(), ids.chats.stream().filter(ids.accessAccounts::contains).count());
        System.out.printf("IDENT R05 objectsWithCardId=%d cardIdИзвестенВCrmClient=%d withPhone=%d systemCards=%d%n",
                objects.size(), knownCards, withPhone, systemCard);
        System.out.printf("IDENT R05 парыКарточкаУчётка=%d поля=%s%n", pairs, pairFields.isEmpty() ? "нет" : pairFields);
        System.out.printf("IDENT R05 keysOfCardObjects=%s%n", ownKeys);
        System.out.printf("IDENT R05 bridgeFields=%s%n", hits.isEmpty() ? "нет" : hits);
        System.out.printf("IDENT R05 cardIdsВВизитах=%d из них зеркалится в crm.client=%d%n", ids.visitedClients.size(),
                ids.visitedClients.stream().filter(ids.cardPool::contains).count());
    }

    /** R09 карточка клиента: поля формы, заполненность и любые совпадения с id чата. */
    private void clientCards(HttpClient client, AtomicInteger calls, String company, Ids ids) throws Exception {
        var form = new Form();
        var envelope = new TreeSet<String>();
        for (var clientId : ids.clients) {
            var body = exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/ajax/clients/settings/"
                    + enc(clientId) + "/?company=" + enc(company), null, true,
                    "https://dikidi.ru/ru/owner/clients/", false).body();
            var json = JSON.readTree(body);
            var html = body;
            if (json.isObject()) {
                envelope.addAll(keysSet(json));
                html = json.path("html").isValueNode() ? json.path("html").asString("") : body;
                if (json.path("dialog").isObject() && json.path("dialog").path("html").isValueNode())
                    html = json.path("dialog").path("html").asString("");
            }
            form.cards++;
            readForm(html, ids, form);
            textHits(body, ids, form.hits);
        }
        System.out.printf("IDENT R09 envelope=%s cards=%d%n", envelope, form.cards);
        System.out.printf("IDENT R09 fields=%s%n", form.names);
        System.out.printf("IDENT R09 filled=%s%n", form.filled);
        System.out.printf("IDENT R09 shapes=%s%n", form.shapes);
        System.out.printf("IDENT R09 bridgeFields=%s%n", form.hits.isEmpty() ? "нет" : form.hits);
    }

    /** R07 агрегат известных клиентов: поля и совпадения. */
    private void clientData(HttpClient client, AtomicInteger calls, String company, Ids ids) throws Exception {
        var form = new ArrayList<Map.Entry<String, String>>();
        form.add(Map.entry("company", company));
        var used = 0;
        for (var id : ids.clients) {
            if (used++ >= 3) break;
            form.add(Map.entry("clients_id[]", id));
        }
        var body = exchange(client, calls, "POST", "https://dikidi.ru/en/owner/ajax/clients/dataOnLoad/", form, true,
                "https://dikidi.ru/ru/owner/clients/", false).body();
        var tree = JSON.readTree(body);
        var hits = new TreeSet<String>();
        textHits(body, ids, hits);
        var first = tree.isObject() && !tree.properties().isEmpty() ? tree.properties().iterator().next().getValue() : tree;
        var inner = first != null && first.isObject() && !first.properties().isEmpty()
                ? first.properties().iterator().next().getValue() : first;
        System.out.printf("IDENT R07 envelope=%s level2=%s level3=%s%n", keys(tree), keys(first), keys(inner));
        System.out.printf("IDENT R07 shapes=%s bridgeFields=%s%n", shapes(inner), hits.isEmpty() ? "нет" : hits);
    }

    /** R08 список клиентов: атрибуты строк таблицы и совпадения с id чата. */
    private void clientList(HttpClient client, AtomicInteger calls, String company, Ids ids) throws Exception {
        exchange(client, calls, "GET", "https://dikidi.ru/ru/owner/clients/?company=" + enc(company), null, false,
                "https://dikidi.ru/ru/", false);
        var body = exchange(client, calls, "POST", "https://dikidi.ru/ru/owner/ajax/clients/filter/?company=" + enc(company),
                List.of(Map.entry("company", company), Map.entry("limit", "50"), Map.entry("offset", "0"),
                        Map.entry("filter", "username"), Map.entry("order", "asc"), Map.entry("more", "1"), Map.entry("query", "")),
                true, "https://dikidi.ru/ru/owner/clients/", false).body();
        var json = JSON.readTree(body);
        var html = json.path("html").isValueNode() ? json.path("html").asString("") : body;
        if (json.path("script").isArray()) for (var script : json.path("script"))
            if (script.path("html").isString()) html = html + script.path("html").asString("");
        var attributes = new TreeSet<String>();
        var matcher = DATA_ATTRIBUTE.matcher(html);
        while (matcher.find()) attributes.add(matcher.group(1).toLowerCase(Locale.ROOT));
        var hits = new TreeSet<String>();
        textHits(html, ids, hits);
        System.out.printf("IDENT R08 envelope=%s rows=%d dataAttributes=%s telLinks=%s%n", keys(json),
                html.split("<tr", -1).length - 1, attributes, html.toLowerCase(Locale.ROOT).contains("tel:"));
        System.out.printf("IDENT R08 bridgeFields=%s%n", hits.isEmpty() ? "нет" : hits);
    }

    private static void readForm(String html, Ids ids, Form form) {
        var matcher = INPUT.matcher(html);
        while (matcher.find()) {
            var tag = matcher.group();
            var value = VALUE.matcher(tag);
            var raw = value.find() ? value.group(1) : "";
            if (raw.isEmpty() && tag.toLowerCase(Locale.ROOT).contains("checked")) raw = "1";
            if (raw.isEmpty() && tag.toLowerCase(Locale.ROOT).contains("selected")) raw = "on";
            form.add(matcher.group(1), raw, ids);
        }
        var selected = OPTION.matcher(html);
        var optionValue = Pattern.compile(VALUE.pattern(), VALUE.flags());
        while (selected.find()) {
            var value = optionValue.matcher(selected.group());
            form.add("option:selected", value.find() ? value.group(1) : "on", ids);
        }
    }

    /** Рекурсивный сбор объектов, у которых есть собственное поле client_id. */
    private static void collectCardObjects(JsonNode node, List<JsonNode> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            if (node.path("client_id").isValueNode()) out.add(node);
            for (var entry : node.properties()) collectCardObjects(entry.getValue(), out);
        } else if (node.isArray()) node.forEach(child -> collectCardObjects(child, out));
    }

    /** Имена полей объектов с client_id и поля, равные известным id. */
    private static void scan(JsonNode node, Set<String> names, Set<String> hits, Ids ids) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            var collected = new TreeSet<String>();
            for (var entry : node.properties()) {
                collected.add(entry.getKey());
                if (!entry.getValue().isValueNode()) continue;
                var value = entry.getValue().asString("");
                var chat = ids.chatTag(value);
                if (chat != null) hits.add(entry.getKey() + "=" + chat);
                var client = ids.clientTag(value);
                if (client != null) hits.add(entry.getKey() + "=" + client);
            }
            if (collected.contains("client_id") || collected.contains("id") && collected.size() >= 8) names.addAll(collected);
            for (var entry : node.properties()) scan(entry.getValue(), names, hits, ids);
        } else if (node.isArray()) node.forEach(child -> scan(child, names, hits, ids));
    }

    /** Любые пары «ключ: число» в тексте ответа, чьё значение равно известному id. */
    private static void textHits(String body, Ids ids, Set<String> out) {
        if (body == null || body.isBlank()) return;
        var matcher = PAIR.matcher(body.length() > 2_000_000 ? body.substring(0, 2_000_000) : body);
        while (matcher.find()) {
            var value = matcher.group(2);
            var chat = ids.chatTag(value);
            if (chat != null) out.add(matcher.group(1) + "=" + chat);
            var client = ids.clientTag(value);
            if (client != null) out.add(matcher.group(1) + "=" + client);
        }
    }

    /** Скаляр как текст: число — десятичной записью, контейнер — null. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) return null;
        var value = node.isNumber() ? String.valueOf(node.asLong()) : node.asString("");
        return value.isBlank() ? null : value.strip();
    }

    /** Форма значения — никогда само значение. */
    private static String shape(String value) {
        if (value == null) return "absent";
        var clean = value.strip();
        if (clean.isEmpty()) return "empty";
        if (PHONE.matcher(clean).find() && clean.matches("[0-9+()\\- ]{10,20}")) return "phone";
        if (clean.matches("[789][0-9]{10}")) return "phone";
        if (clean.matches("[1-9][0-9]{0,17}")) return "id(digits=" + clean.length() + ")";
        if (clean.matches("\\d{4}-\\d{2}-\\d{2}.*")) return "date";
        if (clean.matches("0|[01]")) return "flag";
        return "text(len=" + clean.length() + ")";
    }

    private static String shapes(JsonNode node) {
        if (node == null || !node.isObject()) return "-";
        var result = new TreeMap<String, String>();
        for (var entry : node.properties()) result.put(entry.getKey(), shape(entry.getValue().asString("")));
        return result.toString();
    }

    private static List<JsonNode> elements(JsonNode node) {
        var result = new ArrayList<JsonNode>();
        if (node == null || node.isNull()) return result;
        if (node.isArray()) node.forEach(result::add);
        else if (node.isObject()) for (var entry : node.properties()) result.add(entry.getValue());
        return result;
    }

    private static Set<String> keysSet(JsonNode node) {
        var names = new TreeSet<String>();
        if (node != null && node.isObject()) for (var entry : node.properties()) names.add(entry.getKey());
        return names;
    }

    private static String keys(JsonNode node) {
        var names = keysSet(node);
        return names.isEmpty() ? "-" : names.toString();
    }

    private static JsonNode tree(Res res) {
        assertEquals(200, res.status(), "Endpoint returned a non-200 status.");
        var result = JSON.readTree(res.body());
        var error = result.path("error");
        String code = error.isObject() ? error.path("code").asString("?") : error.asString("?");
        assertEquals("0", code, "Endpoint reported a non-zero error code (structure only).");
        return result;
    }

    private static Res exchange(HttpClient client, AtomicInteger calls, String method, String address,
            List<Map.Entry<String, String>> form, boolean ajax, String referer, boolean lenient) {
        var uri = URI.create(address);
        assertTrue("https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                        && Set.of("dikidi.ru", "auth.dikidi.ru", "chat.dikidi.ru").contains(uri.getHost())
                        && allowed(method + " " + uri.getPath()),
                "Only the allowlisted authentication and reads may run.");
        if (uri.getPath().equals("/ru/owner/ajax/journal/api/"))
            assertTrue(form != null && form.stream().allMatch(field ->
                            field.getKey().equals("request[get][]")
                                    && Set.of("company.accesses", "masters.info", "masters.records").contains(field.getValue())
                                    || Set.of("request[options][company][accesses][]",
                                        "request[options][masters][records][0][date_from]",
                                        "request[options][masters][records][0][date_to]").contains(field.getKey())),
                    "Journal payload must contain read sections and scope options only.");
        if (uri.getPath().equals("/ru/owner/ajax/clients/filter/"))
            assertTrue(form != null && form.size() == 7 && form.stream().allMatch(field ->
                            field.getKey().equals("company") && field.getValue().matches("[1-9][0-9]{0,17}")
                                    || field.getKey().equals("limit") && field.getValue().equals("50")
                                    || field.getKey().equals("offset") && field.getValue().equals("0")
                                    || Set.of("filter", "order", "query").contains(field.getKey())
                                    || field.getKey().equals("more") && field.getValue().equals("1")),
                    "Client list payload is read only.");
        assertTrue(calls.incrementAndGet() <= CALL_LIMIT, "Probe request budget exceeded.");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 ClientBus")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("Accept", ajax ? "application/json, text/javascript, */*; q=0.01" : "text/html");
        if (referer != null) builder.header("Referer", referer);
        if (ajax) {
            builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://dikidi.ru");
            if (uri.getHost().startsWith("auth.")) builder.header("Referer", "https://dikidi.ru/ru/");
            var jar = ((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore();
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
                System.out.printf("IDENT DEBUG path=%s status=%d contentType=%s bodyLen=%d excerpt=%s%n",
                        uri.getPath(), result.statusCode(), result.headers().firstValue("Content-Type").orElse("-"),
                        body.length(), excerpt(body));
            return new Res(result.statusCode(), body);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail("Probe interrupted.");
            return null;
        } catch (Exception error) {
            fail("Probe transport failure on " + uri.getPath() + ": " + error.getClass().getSimpleName());
            return null;
        }
    }

    /** Фрагмент без секретов: длинные последовательности заменяются. */
    private static String excerpt(String body) {
        var clean = body.replaceAll("[A-Za-z0-9+/=_%.-]{24,}", "<redacted>").replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(300, clean.length()));
    }

    private static boolean allowed(String call) {
        return switch (call) {
            case "GET /ru/", "POST /ajax/check/auth/", "POST /ajax/user/auth/",
                    "GET /ru/owner/journal/", "POST /ru/owner/ajax/journal/api/",
                    "GET /ru/owner/clients/", "POST /ru/owner/ajax/clients/filter/",
                    "POST /en/owner/ajax/clients/dataOnLoad/",
                    "GET /ru/owner/ajax/chat/page/", "GET /ru/owner/chat/",
                    "GET /api/dialogs/list/", "GET /api/dialog/get/" -> true;
            default -> call.matches("GET /ru/owner/ajax/clients/settings/[1-9][0-9]{0,17}/");
        };
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
