package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.connector.api.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostConversationCheckpoints.ConversationCheckpoint;
import kit.penny.clientbus.connector.api.host.HostInboundPublisher;
import kit.penny.clientbus.connector.api.model.ConnectorInboundMessage;
import kit.penny.clientbus.connector.api.model.SyncConversationHistoryResult;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiRequests;
import kit.penny.clientbus.integration.dikidi.DikidiTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.5: чтение owner-чата — классификация без догадок, LIVE-публикация после
 * завершённого initial sync, порционный импорт с бюджетом и продолжением по
 * чекпойнту, направление по out (эхо — sender аккаунта), Employee не приписывается.
 */
class DikidiChatReaderTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String COMPANY = "100";

    /** Скриптовый сайт: страницы C05 ключуются парой (dialog, last-курсор). */
    private static final class Site implements DikidiTransport {
        final ArrayDeque<String> homePages = new ArrayDeque<>(List.of(
                "<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t1\"></html>"));
        final List<String> uris = java.util.Collections.synchronizedList(new ArrayList<>());
        String dialogs = "{\"data\":{\"list\":[],\"more\":false}}";
        final Map<String, String> dialogsByTime = new HashMap<>();
        String unread = "{\"data\":{}}";
        final Map<String, String> detail = new HashMap<>();
        final Map<String, String> pages = new HashMap<>();
        final Map<String, byte[]> media = new HashMap<>();
        /** C06: захваченный ответ платформы и перехваченные формы POST (защита от повторов). */
        final List<List<Map.Entry<String, String>>> sendForms =
                java.util.Collections.synchronizedList(new ArrayList<>());
        String sendMessageResponse;
        int sendStatus = 200;
        static final String SEND_OK = "{\"error\":{\"code\":0,\"message\":null},\"data\":{\"id\":93000010,"
                + "\"date\":1794759088,\"user_id\":91000001,\"user_name\":null,\"user_icon\":null,"
                + "\"status\":\"active\",\"text\":\"Пишу вам исходящее сообщение\",\"joining\":null,"
                + "\"read\":0,\"emoji\":0,\"out\":1,\"seen\":0}}";
        /** C07: подлинный ответ загрузки из захвата 15.11.2026 + перехват частей. */
        final List<Object[]> uploads = java.util.Collections.synchronizedList(new ArrayList<>());
        String uploadResponse;
        int uploadStatus = 200;
        /** C08: перехват формы отметки прочтения. */
        final List<List<Map.Entry<String, String>>> readForms =
                java.util.Collections.synchronizedList(new ArrayList<>());
        String readResponse;
        int readStatus = 200;
        static final String UPLOAD_OK = "{\"error\":{\"code\":0,\"message\":null},\"data\":[100000001,\"image\","
                + "\"https://f2.dikidi.ru/c01/v00002/bbbbbbbbbb.jpg\",\"joincode0001\",960,1280,\"вложение\",\"jpg\",297675]}";
        @Override public Response upload(URI uri, String fieldName, String fileName, String contentType,
                byte[] content, String projectId, kit.penny.clientbus.integration.dikidi.RequestProfile profile,
                Duration timeout, Observer observer) {
            uris.add(uri.toString());
            uploads.add(new Object[]{fieldName, fileName, contentType, content.length});
            return new Response(uploadStatus, uploadResponse == null ? UPLOAD_OK : uploadResponse);
        }
        @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                boolean ajax, String projectId, Duration timeout) {
            String address = uri.toString();
            uris.add(address);
            if (address.equals("https://chat.dikidi.ru/api/dialog/message/")) {
                sendForms.add(List.copyOf(form));
                return new Response(sendStatus, sendMessageResponse == null ? SEND_OK : sendMessageResponse);
            }
            if (address.equals("https://chat.dikidi.ru/api/dialog/read/")) {
                readForms.add(List.copyOf(form));
                return new Response(readStatus, readResponse == null
                        ? "{\"error\":{\"code\":0,\"message\":null},\"data\":{\"dialog\":\"7\",\"msg\":[]}}"
                        : readResponse);
            }
            if (address.startsWith("https://f2.dikidi.ru/") || address.startsWith("https://f2.dikidi.net/"))
                return new Response(200, "", null, media.get(address)); // CDN-вложения joining и иконки C02
            if (address.equals("https://dikidi.ru/ru/")) return new Response(200,
                    "<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t1\"></html>"); // каждая новая connection входит заново
            if (address.equals("https://auth.dikidi.ru/ajax/check/auth/")) return new Response(200, "{\"html\":\"<input type=\\\"hidden\\\" name=\\\"csrf\\\" value=\\\"c1\\\">\"}");
            if (address.equals("https://auth.dikidi.ru/ajax/user/auth/")) return new Response(200, "{\"callback\":\"sw.auth.complete\"}");
            if (address.startsWith("https://dikidi.ru/ru/owner/chat/")) return new Response(200, "<html>owner chat</html>");
            if (address.startsWith("https://chat.dikidi.ru/api/dialogs/list/")) {
                for (var part : URI.create(address).getQuery().split("&"))
                    if (part.startsWith("time="))
                        return new Response(200, dialogsByTime.getOrDefault(part.substring(5), dialogs));
                return new Response(200, dialogs);
            }
            if (address.startsWith("https://chat.dikidi.ru/api/dialogs/unreaded/")) return new Response(200, unread);
            if (address.startsWith("https://chat.dikidi.ru/api/dialog/get/")) {
                String id = URI.create(address).getQuery().split("=")[1].split("&")[0];
                return new Response(200, detail.getOrDefault(id, "{\"error\":{\"code\":1}}"));
            }
            if (address.startsWith("https://chat.dikidi.ru/api/dialog/messages/")) {
                var query = URI.create(address).getQuery();
                String id = null, last = null;
                for (var part : query.split("&")) {
                    if (part.startsWith("dialog=")) id = part.substring(7);
                    if (part.startsWith("last=")) last = part.substring(5);
                }
                return new Response(200, pages.getOrDefault(id + "|" + last, "{\"data\":{\"list\":[]}}"));
            }
            return new Response(404, "unexpected " + address);
        }
    }

    private static final class Checkpoints implements kit.penny.clientbus.connector.api.host.HostConversationCheckpoints {
        final Map<String, ConversationCheckpoint> rows = new HashMap<>();
        @Override public Optional<ConversationCheckpoint> load(UUID account, String dialog) {
            return Optional.ofNullable(rows.get(account + "/" + dialog));
        }
        @Override public void save(UUID account, String dialog, ConversationCheckpoint value) {
            rows.put(account + "/" + dialog, value);
        }
    }

    private static final class Harness implements AutoCloseable {
        final Site site = new Site();
        final Checkpoints checkpoints = new Checkpoints();
        final List<ConnectorInboundMessage> published = new ArrayList<>();
        final List<String> storedFiles = java.util.Collections.synchronizedList(new ArrayList<>());
        final DikidiClient client = new DikidiClient(
                new DikidiClient.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 262144), () -> site,
                new DikidiRequests(new DikidiRequests.Settings(1e9, 100000, 1024, Duration.ofSeconds(30),
                        Duration.ofMinutes(15), 1e9, 100000), DikidiRequests.memory()));
        final DikidiChannelConnector connector;
        Harness(int budget) {
            DikidiTechnicalCredentials credentials = accountId -> new DikidiCredential(UUID.randomUUID(), 1,
                    "owner", "secret".toCharArray());
            var accounts = new DikidiChatAccessTest.Accounts(new HostChannelAccountSnapshot(ACCOUNT,
                    UUID.randomUUID(), UUID.randomUUID(), kit.penny.clientbus.common.enums.ChannelType.DIKIDI,
                    kit.penny.clientbus.common.enums.ChannelConnectionStatus.CONNECTED, null, COMPANY));
            var reads = new DikidiChatReads();
            var storage = new kit.penny.clientbus.connector.api.host.HostAttachmentStorage() {
                @Override public kit.penny.clientbus.connector.api.host.HostStoredAttachment store(
                        java.io.InputStream content, String fileName, long size, String contentType) {
                    storedFiles.add(fileName);
                    return new kit.penny.clientbus.connector.api.host.HostStoredAttachment(
                            "stored/" + fileName, fileName, contentType, size);
                }
                @Override public java.io.InputStream load(String storageKey) { throw new UnsupportedOperationException(); }
                @Override public boolean exists(String storageKey) { return false; }
                @Override public void delete(String storageKey) { }
            };
            connector = new DikidiChannelConnector(client, credentials, accounts, checkpoints,
                    (HostInboundPublisher) published::add, reads,
                    new DikidiChatAccess(client, credentials, accounts, reads), budget, storage);
        }
        @Override public void close() { client.close(); }
    }

    private static String message(String id, String user, String text, int out, long date) {
        return "{\"id\":\"" + id + "\",\"user_id\":\"" + user + "\",\"user_name\":\"u" + user + "\",\"text\":\""
                + text + "\",\"out\":" + out + ",\"joining\":false,\"date\":" + date + "}";
    }
    private static String page(String... messages) {
        return "{\"data\":{\"list\":[" + String.join(",", messages) + "]}}";
    }

    /** Диалог 7 — клиентский (21 клиент, 22 салон); 8 — поддержка DIKIDI (три участника); 9 — без доказательств. */
    private static void fleet(Site site) {
        site.dialogs = "{\"data\":{\"list\":["
                + "{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000500,\"msg\":{\"text\":\"ок\",\"user_id\":\"22\",\"out\":1},\"badge\":0},"
                + "{\"id\":\"8\",\"name\":\"Поддержка DIKIDI\",\"date\":1700000400,\"msg\":\"?\",\"badge\":1},"
                + "{\"id\":\"9\",\"name\":\"?\",\"date\":1700000300}"
                + "],\"more\":false}}";
        site.unread = "{\"data\":{\"7\":2,\"8\":5}}";
        site.detail.put("7", "{\"data\":{\"dialog\":{},\"messages\":[],\"users\":[{\"id\":21,\"name\":\"Клиент Ким\"},{\"id\":22,\"name\":\"Салон\"}]}}");
        site.detail.put("8", "{\"data\":{\"dialog\":{},\"messages\":[],\"users\":[{\"id\":41,\"name\":\"бот\"},{\"id\":42,\"name\":\"агент\"},{\"id\":22,\"name\":\"Салон\"}]}}");
        site.detail.put("9", "{\"data\":{\"dialog\":{},\"messages\":[],\"users\":[31,32]}}");
        site.pages.put("7|null", page(message("101", "22", "ок", 1, 1700000500), message("100", "21", "привет", 0, 1700000400)));
        site.pages.put("8|null", page(message("200", "42", "help", 0, 1700000400)));
        site.pages.put("9|null", "{\"data\":{\"list\":[]}}");
    }

    @Test void recentChatsClassifiesWithoutGuessing() {
        try (var harness = new Harness(5)) {
            fleet(harness.site);
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(1, result.size(), "только диалог с разрешимым клиентом; поддержка и неизвестный — мимо");
            var kim = result.get(0);
            assertEquals("21", kim.clientExternalId());
            assertEquals("Клиент Ким", kim.clientDisplayName());
            assertEquals(2, kim.unreadCount(), "unread берётся из C03 (map), не из badge");
            assertEquals("7", kim.dialogExternalId());
            assertNull(kim.clientPhone(), "в chat-API телефона нет — не выдумываем");
            assertEquals("ок", kim.lastMessagePreview());
            assertTrue(harness.site.uris.get(3).startsWith("https://dikidi.ru/ru/owner/chat/"), "shell предпосылка");
            assertTrue(publishedNothing(harness), "на новом тике без чекпойнта LIVE не-publishится");
        }
    }

    private static boolean publishedNothing(Harness harness) { return harness.published.isEmpty(); }

    /** 16.11.2026: аватаром хосту отдаётся storageKey, а не сырой CDN-URL (иначе /avatar/content падает InvalidPathException). */
    @Test void dialogIconBecomesLocalStorageKeyAndFailureLeavesAvatarEmpty() {
        try (var harness = new Harness(5)) {
            fleet(harness.site);
            harness.site.dialogs = harness.site.dialogs.replace("\"name\":\"Ким\"",
                    "\"name\":\"Ким\",\"icon\":\"https://f2.dikidi.net/c99/v11111/abc123hash.jpg\"");
            harness.site.media.put("https://f2.dikidi.net/c99/v11111/abc123hash.jpg", new byte[]{1, 2, 3});
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals("stored/abc123hash.jpg", result.get(0).avatarUrl());
            harness.site.media.clear();
            var again = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertNull(again.get(0).avatarUrl(), "сбой иконки — карточка без аватара, не падение синка");
        }
    }

    @Test void liveOnlyAfterInitialSyncAndOnlyNewerThanCursor() {
        try (var harness = new Harness(5)) {
            fleet(harness.site);
            // initial sync завершён, курсор на 101, диалог двинулся (badge=1 в этом fleets нет → date тот же) — тишина.
            harness.checkpoints.rows.put(ACCOUNT + "/7", new ConversationCheckpoint("101", "100", true, false, 1700000500));
            harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertTrue(harness.published.isEmpty(), "нет движения — нет LIVE");
            // движение: свежая страница приносит 102/103
            harness.site.pages.put("7|null", page(message("103", "22", "жду", 1, 1700000600),
                    message("102", "21", "заказ?", 0, 1700000550)));
            harness.site.dialogs = siteDialogsWithBadge();
            harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(List.of("102", "103"), harness.published.stream().map(ConnectorInboundMessage::externalId).toList());
            assertEquals(EventOrigin.LIVE, harness.published.get(0).origin());
            assertEquals("21", harness.published.get(0).senderExternalId(), "входящее — автор клиента");
            assertEquals(COMPANY, harness.published.get(1).senderExternalId(),
                    "исходящее — аккаунт канала (направление определяет хост)");
            assertEquals("22", harness.published.get(1).authorExternalId(),
                    "фактический автор исходящего — сотрудник салона, не аккаунт");
            var saved = harness.checkpoints.rows.get(ACCOUNT + "/7");
            assertEquals("103", saved.newestMessageId());
            assertEquals(1700000600, saved.lastDialogEpoch());
            // повтор тика без движения не переопубликует
            harness.published.clear();
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000600,\"msg\":\"жду\",\"badge\":0}],\"more\":false}}";
            harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertTrue(harness.published.isEmpty(), "LIVE-пасс идемпотентен: курсор уже 103");
        }
    }

    private static String siteDialogsWithBadge() {
        return "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000600,\"msg\":\"жду\",\"badge\":1}],\"more\":false}}";
    }

    private static final String USERS_7 = "{\"data\":{\"dialog\":{},\"messages\":[],\"users\":"
            + "[{\"id\":21,\"name\":\"Клиент Ким\"},{\"id\":22,\"name\":\"Салон\"}]}}";

    @Test void livePassStepsBackToCursorAcrossPages() {
        try (var harness = new Harness(5)) {
            harness.site.detail.put("7", USERS_7);
            harness.site.pages.put("7|null", page(message("102", "21", "а", 0, 1700001020),
                    message("101", "22", "б", 1, 1700001010)));
            harness.site.pages.put("7|101", page(message("100", "21", "в", 0, 1700001000),
                    message("99", "21", "г", 0, 1700000990)));
            harness.checkpoints.rows.put(ACCOUNT + "/7", new ConversationCheckpoint("100", "50", true, false, 1700001000));
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700001020,\"msg\":\"а\",\"badge\":2}],\"more\":false}}";
            harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(List.of("101", "102"), harness.published.stream().map(ConnectorInboundMessage::externalId).toList(),
                    "первая страница не скрывает свежие сообщения со второй — шаг до курсора");
            assertEquals("102", harness.checkpoints.rows.get(ACCOUNT + "/7").newestMessageId());
        }
    }

    @Test void liveBacklogBeyondBudgetFailsHonestWithoutCursorLoss() {
        try (var harness = new Harness(1)) {
            harness.site.detail.put("7", USERS_7);
            harness.site.pages.put("7|null", page(message("102", "21", "а", 0, 1700001020),
                    message("101", "21", "б", 0, 1700001010)));
            harness.checkpoints.rows.put(ACCOUNT + "/7", new ConversationCheckpoint("100", "50", true, false, 1700001000));
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700001020,\"msg\":\"а\",\"badge\":2}],\"more\":false}}";
            harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertTrue(harness.published.isEmpty(), "хвост до курсора не доказан — публикации нет");
            assertEquals("100", harness.checkpoints.rows.get(ACCOUNT + "/7").newestMessageId(),
                    "курсор не перепрыгивает недоказанный диапазон");
        }
    }

    @Test void initialImportPagesBackwardAndProvesHistoryStart() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|null", page(
                    message("120", "21", "a", 0, 1700001200), message("119", "22", "b", 1, 1700001190),
                    message("111", "21", "c", 0, 1700001110)));
            harness.site.pages.put("7|111", page(message("110", "21", "d", 0, 1700001100),
                    message("101", "22", "e", 1, 1700001010), message("99", "99", "чужой", 0, 1700000990)));
            harness.site.pages.put("7|101", "{\"data\":{\"list\":[]}}");
            var result = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "21", null, 50, "7"));
            assertTrue(result.historyStartReached());
            assertEquals("HISTORY_START_REACHED", result.endReason());
            // чужой автор входящего (99 ≠ клиент 21) из карточки сообщений исключён; эхо out=1 осталось
            assertEquals(List.of("101", "110", "111", "119", "120"),
                    result.messages().stream().map(ConnectorInboundMessage::externalId).toList());
            assertTrue(result.messages().stream().allMatch(m -> m.origin() == EventOrigin.HISTORY_IMPORT));
            var saved = harness.checkpoints.rows.get(ACCOUNT + "/7");
            assertEquals("120", saved.newestMessageId());
            assertEquals("99", saved.oldestLoadedId(), "курсор — по сырой странице, включая отфильтрованные id");
            assertTrue(saved.initialSyncDone());
            assertEquals(COMPANY, result.messages().stream().filter(m -> m.externalId().equals("101"))
                    .findFirst().orElseThrow().senderExternalId());
        }
    }

    @Test void budgetPartialImportResumesFromCheckpointCursor() {
        try (var harness = new Harness(1)) {
            harness.site.pages.put("7|null", page(message("120", "21", "a", 0, 1700001200),
                    message("111", "21", "c", 0, 1700001110)));
            harness.site.pages.put("7|111", page(message("110", "21", "d", 0, 1700001100)));
            harness.site.pages.put("7|110", "{\"data\":{\"list\":[]}}");
            var first = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "21", null, 20, "7"));
            assertFalse(first.historyStartReached(), "частичная история не объявляется полной");
            assertEquals("BUDGET_PARTIAL", first.endReason());
            var partial = harness.checkpoints.rows.get(ACCOUNT + "/7");
            assertFalse(partial.initialSyncDone());
            assertEquals("111", partial.oldestLoadedId());
            // продолжение: без явного before — с внутреннего курсора, снова одним тиком бюджета
            var second = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "21", null, 20, "7"));
            assertFalse(second.historyStartReached());
            assertEquals(List.of("110"), second.messages().stream().map(ConnectorInboundMessage::externalId).toList());
            assertTrue(harness.site.uris.stream().anyMatch(u -> u.contains("dialog=7") && u.contains("last=111")),
                    "страница продолжения взята с чекпойнт-курсора");
            var third = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "21", null, 20, "7"));
            assertTrue(third.historyStartReached());
            assertTrue(harness.checkpoints.rows.get(ACCOUNT + "/7").initialSyncDone());
        }
    }

    @Test void durableChainUsesExplicitCursorAndSinglePage() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|111", page(message("110", "21", "d", 0, 1700001100)));
            var result = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "21", "111", 10, "7"));
            assertFalse(result.historyStartReached());
            assertEquals("PAGE_COMPLETE", result.endReason());
            assertEquals(1, result.messages().size());
            assertTrue(harness.site.uris.stream().anyMatch(u -> u.contains("limit=10") && u.contains("last=111")));
        }
    }

    @Test void unresolvableDialogNeverBecomesConversation() {
        try (var harness = new Harness(5)) {
            fleet(harness.site);
            // диалог 9: участники скалярами, сообщений нет — владельца не разрешить; карточки не будет
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertTrue(result.stream().noneMatch(c -> c.dialogExternalId().equals("9")));
            assertTrue(result.stream().noneMatch(c -> c.dialogExternalId().equals("8")),
                    "служебный диалог поддержки DIKIDI (3 участника) не становится клиентским");
        }
    }

    /**
     * Живой прогон 15.11.2026: C04 одного диалога вернул форму вне ожиданий — коннектор
     * ронял весь recent-chats-прогон (DLQ), и ни один хороший диалог не создавался.
     * Отказ одного диалога теперь изолирован: остальные синхронизируются.
     */
    @Test void brokenDialogIsIsolatedAndGoodDialogsStillSync() {
        try (var harness = new Harness(5)) {
            harness.site.dialogs = "{\"data\":{\"list\":["
                    + "{\"id\":\"6\",\"name\":\"сломанный\",\"date\":1700000600},"
                    + "{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000500,\"msg\":\"ок\"}],\"more\":false}}";
            // detail диалога 6 не задан — сайт отвечает {"error":{"code":1}} (реальная живая форма отказа)
            harness.site.detail.put("7", USERS_7);
            harness.site.pages.put("7|null", page(message("101", "22", "ок", 1, 1700000500)));
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(List.of("7"), result.stream().map(c -> c.dialogExternalId()).toList(),
                    "битый диалог пропущен с логом, хороший синхронизирован");
        }
    }

    /** Живой ответ C04 15.11.2026: users — обёртка {"list": {id: {...}}, "total": n}. */
    @Test void usersAsObjectMapIsParsed() {
        try (var harness = new Harness(5)) {
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Клиент Тест\","
                    + "\"date\":1793121632,\"msg\":\"ок\"}],\"more\":false}}";
            harness.site.detail.put("7", "{\"data\":{"
                    + "\"dialog\":{\"id\":92000001,\"name\":\"Клиент Тест\",\"role\":\"support\",\"date\":1793121632},"
                    + "\"messages\":{\"list\":[],\"total\":0,\"more\":false},"
                    + "\"users\":{\"list\":{"
                    + "\"91000001\":{\"id\":91000001,\"name\":\"Penny Kit\",\"role\":\"support\",\"online\":1},"
                    + "\"91000003\":{\"id\":91000003,\"name\":\"Клиент Тест\",\"role\":\"user\",\"online\":0}"
                    + "},\"total\":2}}}");
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":93000002,\"date\":1793121632,\"user_id\":91000003,\"user_name\":\"Клиент Тест\","
                    + "\"text\":\"Хорошо\",\"joining\":null,\"out\":0},"
                    + "{\"id\":93000001,\"date\":1793121454,\"user_id\":91000001,\"user_name\":\"Penny Kit\","
                    + "\"text\":\"Да, жду вас два раза\",\"joining\":null,\"out\":1}"
                    + "],\"total\":2,\"more\":false}}");
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(1, result.size(), "живая форма C04/C05 (числовые id, users.list-обёртка) читается");
            assertEquals("91000003", result.get(0).clientExternalId(), "клиент — не автор out=1");
            assertEquals("Клиент Тест", result.get(0).clientDisplayName());
        }
    }

    /**
     * Живой пример пользователя 15.11.2026: в клиентском диалоге отвечали ДВА сотрудника
     * салона (Penny Kit и Мастер Тест, оба out=1/role=support) — диалог с тремя
     * участниками остаётся клиентским, клиент — единственный автор входящих.
     */
    @Test void multipleSalonStaffDoNotBreakClientDialog() {
        try (var harness = new Harness(5)) {
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"7\",\"name\":\"Клиент Тест\","
                    + "\"date\":1793129339,\"msg\":\"Ответ салона. СуперАдмин решает\"}],\"more\":false}}";
            harness.site.detail.put("7", "{\"data\":{\"dialog\":{\"id\":92000002,\"name\":\"Клиент Тест\"},"
                    + "\"messages\":{\"list\":[],\"total\":4,\"more\":false},"
                    + "\"users\":{\"list\":{"
                    + "\"91000001\":{\"id\":91000001,\"name\":\"Penny Kit\",\"role\":\"support\"},"
                    + "\"91000002\":{\"id\":91000002,\"name\":\"Мастер Тест\",\"role\":\"support\"},"
                    + "\"91000003\":{\"id\":91000003,\"name\":\"Клиент Тест\",\"role\":\"user\"}"
                    + "},\"total\":3}}}");
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":93000006,\"date\":1793129339,\"user_id\":91000001,\"text\":\"Ответ салона\","
                    + "\"joining\":null,\"out\":1},"
                    + "{\"id\":93000005,\"date\":1793129307,\"user_id\":91000003,\"text\":\"Вопрос клиента\","
                    + "\"joining\":null,\"out\":0},"
                    + "{\"id\":93000004,\"date\":1793129260,\"user_id\":91000002,\"text\":\"Ответ второго сотрудника\","
                    + "\"joining\":null,\"out\":1},"
                    + "{\"id\":93000003,\"date\":1793129205,\"user_id\":91000003,\"text\":\"Приветствие клиента\","
                    + "\"joining\":null,\"out\":0}"
                    + "],\"total\":4,\"more\":false}}");
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertEquals(1, result.size(), "несколько сотрудников салона — не повод терять клиентский диалог");
            assertEquals("91000003", result.get(0).clientExternalId());
            assertEquals("Клиент Тест", result.get(0).clientDisplayName());
        }
    }

    /** Роли читаются: автор входящих со role=support — не клиент (диалог поддержки платформы). */
    @Test void supportRoleInboundAuthorIsNotClient() {
        try (var harness = new Harness(5)) {
            harness.site.dialogs = "{\"data\":{\"list\":[{\"id\":\"8\",\"name\":\"Поддержка\","
                    + "\"date\":1793129339}],\"more\":false}}";
            harness.site.detail.put("8", "{\"data\":{\"dialog\":{},\"messages\":{\"list\":[]},"
                    + "\"users\":{\"list\":{"
                    + "\"91000001\":{\"id\":91000001,\"name\":\"Салон\",\"role\":\"support\"},"
                    + "\"42\":{\"id\":42,\"name\":\"агент DIKIDI\",\"role\":\"support\"}"
                    + "},\"total\":2}}}");
            harness.site.pages.put("8|null", "{\"data\":{\"list\":["
                    + "{\"id\":300,\"date\":1793129260,\"user_id\":42,\"text\":\"help\",\"joining\":null,\"out\":0}"
                    + "]}}");
            var result = harness.connector.handle(new SyncRecentChatsCommand(ACCOUNT));
            assertTrue(result.isEmpty(), "входящий автор со role=support клиентом не считается");
        }
    }

    /** Подлинный joining 15.11.2026: «image» + JSON-путь payload → CDN-скачивание в хост-хранилище. */
    @Test void joiningImagesAreDownloadedIntoHostStorage() {
        try (var harness = new Harness(5)) {
            String joining = "[[\"image\",\"[2,100000002,\\\"c01\\\\/v00001\\\\/aaaaaaaaaa\\\",960,1280]\","
                    + "\"joincode0002\"]]";
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":93000009,\"date\":1794754950,\"user_id\":91000001,\"text\":\"\",\"joining\":" + joining + ",\"out\":1},"
                    + "{\"id\":93000008,\"date\":1794754903,\"user_id\":91000001,\"text\":\"Подпись к вложениям\",\"joining\":"
                    + joining + ",\"out\":1},"
                    + "{\"id\":93000007,\"date\":1794754800,\"user_id\":91000003,\"text\":\"Приветствие клиента\","
                    + "\"joining\":null,\"out\":0}"
                    + "]}}");
            harness.site.pages.put("7|93000007", "{\"data\":{\"list\":[]}}");
            harness.site.media.put("https://f2.dikidi.ru/c01/v00001/aaaaaaaaaa.jpg?size=b", new byte[]{1, 2, 3});
            var result = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "91000003", null, 50, "7"));
            assertEquals(List.of("93000007", "93000008", "93000009"),
                    result.messages().stream().map(ConnectorInboundMessage::externalId).toList(),
                    "сообщение с вложениями больше не отбрасывается как «врезка»");
            assertEquals(2, harness.storedFiles.size(), "каждое image-вложение скачивается и хранится один раз");
            var text = result.messages().get(1);
            // Дефект 15.11.2026: «Подпись к вложениям» с файлами уходил TEXT (правило text.isBlank()),
            // и диалог не показывал вложения. Живые Telegram-строки: картинка с подписью = IMAGE.
            assertEquals(kit.penny.clientbus.common.enums.MessageType.IMAGE, text.type(),
                    "подпись + файл — IMAGE (правило Telegram: подпись+фото = IMAGE)");
            assertEquals(List.of("stored/aaaaaaaaaa.jpg"),
                    text.attachments().stream().map(kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment::storageKey).toList());
            assertEquals(kit.penny.clientbus.common.enums.MessageType.IMAGE, result.messages().get(2).type(),
                    "сообщение из одного вложения — IMAGE");
            assertEquals("91000001", result.messages().get(1).authorExternalId(),
                    "автор вложенного сообщения — сотрудник платформы");
        }
    }

    /** Постоянная недоступность CDN: текст сохраняется без вложения, чисто вложение честно пропускается. */
    @Test void permanentCdnFailureKeepsTextMessages() {
        try (var harness = new Harness(5)) {
            String joining = "[[\"image\",\"[2,100000002,\\\"c01\\\\/v00001\\\\/aaaaaaaaaa\\\",960,1280]\","
                    + "\"joincode0002\"]]";
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":93000009,\"date\":1794754950,\"user_id\":91000001,\"text\":\"\",\"joining\":" + joining + ",\"out\":1},"
                    + "{\"id\":93000008,\"date\":1794754903,\"user_id\":91000001,\"text\":\"Подпись к вложениям\",\"joining\":"
                    + joining + ",\"out\":1}"
                    + "]}}");
            harness.site.pages.put("7|93000008", "{\"data\":{\"list\":[]}}");
            // media не смонтирован: CDN отвечает пустым телом — постоянный отказ.
            var result = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "91000003", null, 50, "7"));
            assertEquals(List.of("93000008"),
                    result.messages().stream().map(ConnectorInboundMessage::externalId).toList(),
                    "текст выживает без вложения, недоказанное вложение-без-текста не выдумывается");
            assertTrue(result.messages().get(0).attachments().isEmpty());
        }
    }

    /**
     * Живой дефект 15.11.2026: more=true у C02 — дальше шла только первая страница,
     * диалоги за ней (в том числе с вложениями) не импортировались вовсе. Курсор —
     * самый старый date; повторный id со второй страницы не сдвигает курсор бесконечно.
     */
    @Test void dialogsListIsPagedBackwardsByTimeCursor() {
        try (var harness = new Harness(5)) {
            harness.site.dialogsByTime.put("0", "{\"data\":{\"list\":["
                    + "{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000600,\"msg\":\"жду\",\"badge\":0}"
                    + "],\"more\":true}}");
            harness.site.dialogsByTime.put("1700000600", "{\"data\":{\"list\":["
                    + "{\"id\":\"7\",\"name\":\"Ким\",\"date\":1700000600,\"msg\":\"жду\",\"badge\":0},"
                    + "{\"id\":\"9\",\"name\":\"П\",\"date\":1700000500,\"msg\":\"ок\",\"badge\":1}"
                    + "],\"more\":false}}");
            harness.site.detail.put("7", "{\"data\":{\"users\":{\"list\":{\"21\":{\"id\":21},"
                    + "\"22\":{\"id\":22},\"33\":{\"id\":33}},\"total\":3}}}"); // без ролей, 3 участника → не клиент
            harness.site.detail.put("9", "{\"data\":{\"users\":{\"list\":{\"31\":{\"id\":31},"
                    + "\"22\":{\"id\":22}},\"total\":2}}}");
            harness.site.pages.put("9|null", page(message("201", "31", "здравствуйте", 0, 1700000500)));
            var result = harness.connector.handle(new kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand(ACCOUNT));
            assertEquals(List.of("9"), result.stream()
                    .map(kit.penny.clientbus.connector.api.model.ConnectorConversation::dialogExternalId).toList(),
                    "диалог со второй страницы списка доходит до карточки");
            assertTrue(harness.site.uris.stream().anyMatch(u -> u.contains("dialogs/list/") && u.contains("time=1700000600")),
                    "второй запрос списка идёт с курсором по самому старому date");
        }
    }

    private static kit.penny.clientbus.connector.api.command.SendMessageCommand send(String text) {
        return new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                UUID.randomUUID(), ACCOUNT, "91000003", kit.penny.clientbus.common.enums.MessageType.TEXT,
                text, List.of(), UUID.randomUUID(), "92000001");
    }

    /** C06 (захват 15.11.2026): POST + эхо сообщения с присвоенным id — внешний id наружу. */
    @Test void outboundSendReturnsPlatformId() {
        try (var harness = new Harness(5)) {
            var result = harness.connector.handle(send("Пишу вам исходящее сообщение"));
            assertEquals("93000010", result.externalId());
            assertEquals(1, harness.site.sendForms.size());
            assertTrue(harness.site.sendForms.get(0).stream()
                    .anyMatch(e -> e.getKey().equals("dialog") && e.getValue().equals("92000001")),
                    "диалог берётся из команды, а не резолвится вслепую");
        }
    }

    /** Явный отказ платформы (error.code≠0) — доказанное отсутствие эффекта, повтора нет. */
    @Test void sendRejectedByPlatformCodeFailsAsRejectedWithoutRetry() {
        try (var harness = new Harness(5)) {
            harness.site.sendMessageResponse = "{\"error\":{\"code\":1,\"message\":\"validation\"},\"data\":[]}";
            assertThrows(kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException.class,
                    () -> harness.connector.handle(send("текст")));
            assertEquals(1, harness.site.sendForms.size());
        }
    }

    /** 401 на POST: изменяющая операция не повторяется перелогиниванием — защита от дублей. */
    @Test void sendNeverRetriesAfterAuthFailure() {
        try (var harness = new Harness(5)) {
            harness.site.sendStatus = 401;
            var failure = assertThrows(kit.penny.clientbus.integration.dikidi.DikidiFailure.class,
                    () -> harness.connector.handle(send("текст")));
            assertEquals(kit.penny.clientbus.integration.dikidi.DikidiFailure.Code.AUTH_REQUIRED, failure.code());
            assertEquals(1, harness.site.sendForms.size(),
                    "executeOnce: повтор с восстановлением сессии запрещён — иначе дубликат сообщения");
        }
    }

    /**
     * C07+C06 по коду веб-клиента (backend.js + upload-виджет, 15.11.2026): файл
     * грузится на f2.dikidi.ru/upload/?v=2 полем «file», сообщение уходит form-ой
     * tabHash/dialog/text/files[] — files[] содержит ЧИСЛЫЙ fileId загрузки, joining
     * сервер собирает сам (формы joining в веб-клиенте нет вовсе).
     */
    @Test void outboundImageUploadsFileAndSendsWithFilesField() {
        try (var harness = new Harness(5)) {
            var command = new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                    UUID.randomUUID(), ACCOUNT, "91000003", kit.penny.clientbus.common.enums.MessageType.IMAGE,
                    "Прикладываю файл", List.of(new kit.penny.clientbus.connector.api.model.ConnectorAttachment(
                            kit.penny.clientbus.common.enums.MessageAttachmentType.IMAGE,
                            "вложение.jpg", "image/jpeg", 3L, new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}))),
                    UUID.randomUUID(), "92000001");
            var result = harness.connector.handle(command);
            assertEquals("93000010", result.externalId());
            assertEquals(1, harness.site.uploads.size());
            assertEquals("file", harness.site.uploads.get(0)[0], "multipart-поле доказано захватом");
            assertEquals("вложение.jpg", harness.site.uploads.get(0)[1]);
            var form = harness.site.sendForms.get(0);
            assertEquals("92000001", value(form, "dialog"));
            assertEquals("Прикладываю файл", value(form, "text"));
            assertEquals(List.of("100000001"), allValues(form, "files[]"),
                    "files[] — числовой fileId из ответа C07, не joining-структура");
            assertTrue(value(form, "tabHash").matches("[0-9a-z]{4,16}"), "tabHash — base36 как getHash() веб-клиента");
            assertTrue(form.stream().noneMatch(e -> e.getKey().equals("joining")),
                    "joining в форме не отправляется: его собирает сервер из files[]");
            assertTrue(form.stream().noneMatch(e -> e.getKey().equals("company")),
                    "company в POST не участвует: контекст проекта держит сессия");
            int uploadAt = harness.site.uris.indexOf("https://f2.dikidi.ru/upload/?v=2");
            int sendAt = harness.site.uris.indexOf("https://chat.dikidi.ru/api/dialog/message/");
            assertTrue(uploadAt >= 0 && sendAt > uploadAt, "загрузка предшествует сообщению");
        }
    }

    /**
     * C08 (backend.js 15.11.2026): POST /api/dialog/read/ {tabHash, dialog, msg[]}.
     * Отмечаются только ВХОДЯЩИЕ с id не выше lastReadExternalId (нумерация растёт).
     */
    @Test void markReadPostsInboundIdsUpToLastRead() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":300,\"date\":1794754960,\"user_id\":91000003,\"text\":\"третее\",\"joining\":null,\"out\":0},"
                    + "{\"id\":200,\"date\":1794754950,\"user_id\":91000003,\"text\":\"второе\",\"joining\":null,\"out\":0},"
                    + "{\"id\":150,\"date\":1794754940,\"user_id\":91000001,\"text\":\"ответ салона\",\"joining\":null,\"out\":1},"
                    + "{\"id\":100,\"date\":1794754930,\"user_id\":91000003,\"text\":\"первое\",\"joining\":null,\"out\":0}"
                    + "]}}");
            harness.site.pages.put("7|100", "{\"data\":{\"list\":[]}}");
            harness.connector.handle(new kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand(
                    ACCOUNT, "91000003", "200", "7"));
            assertEquals(1, harness.site.readForms.size());
            var form = harness.site.readForms.get(0);
            assertEquals("7", value(form, "dialog"));
            assertEquals(List.of("200", "100"), allValues(form, "msg[]"),
                    "только входящие и только не новее lastReadExternalId");
            assertTrue(value(form, "tabHash").matches("[0-9a-z]{4,16}"));
        }
    }

    /** Отметка прочтения — идемпотентная запись: отказ платформы не глотается (DLQ-путь). */
    @Test void markReadFailurePropagates() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":100,\"date\":1794754930,\"user_id\":91000003,\"text\":\"раз\",\"joining\":null,\"out\":0}"
                    + "]}}");
            harness.site.pages.put("7|100", "{\"data\":{\"list\":[]}}");
            harness.site.readResponse = "{\"error\":{\"code\":5,\"message\":\"dialog not found\"},\"data\":null}";
            assertThrows(kit.penny.clientbus.integration.dikidi.DikidiFailure.class,
                    () -> harness.connector.handle(new kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand(
                            ACCOUNT, "91000003", "200", "7")));
        }
    }

    /**
     * Отчёт о доставке (13.6): веб-клиент рисует «не прочитано» у исходящих по флагам
     * read (backend.js 1834), значит read>0 у out — «собеседник прочитал». Флаг едет
     * в хост как recipientRead: TRUE/FALSE у исходящих, null у входящих.
     */
    @Test void readFlagOnOutboundEchoBecomesRecipientRead() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":102,\"date\":1794754960,\"user_id\":91000001,\"text\":\"прочитано\",\"joining\":null,"
                    + "\"out\":1,\"read\":1},"
                    + "{\"id\":101,\"date\":1794754950,\"user_id\":91000001,\"text\":\"не прочитано\",\"joining\":null,"
                    + "\"out\":1,\"read\":0},"
                    + "{\"id\":100,\"date\":1794754940,\"user_id\":91000003,\"text\":\"входящее\",\"joining\":null,"
                    + "\"out\":0,\"read\":1}"
                    + "]}}");
            harness.site.pages.put("7|100", "{\"data\":{\"list\":[]}}");
            var result = harness.connector.handle(new SyncConversationHistoryCommand(ACCOUNT, "91000003", null, 50, "7"));
            assertEquals(java.util.Optional.of(Boolean.TRUE),
                    java.util.Optional.ofNullable(message(result, "102").recipientRead()));
            assertEquals(Boolean.FALSE, message(result, "101").recipientRead());
            assertNull(message(result, "100").recipientRead(),
                    "у входящего сообщения receipt-семантики нет");
        }
    }

    private static ConnectorInboundMessage message(SyncConversationHistoryResult result, String externalId) {
        return result.messages().stream().filter(m -> m.externalId().equals(externalId)).findFirst().orElseThrow();
    }

    private static String value(List<Map.Entry<String, String>> form, String key) {
        return form.stream().filter(e -> e.getKey().equals(key)).map(Map.Entry::getValue)
                .findFirst().orElseThrow(() -> new AssertionError("нет поля " + key + " в " + form));
    }

    private static List<String> allValues(List<Map.Entry<String, String>> form, String key) {
        return form.stream().filter(e -> e.getKey().equals(key)).map(Map.Entry::getValue).toList();
    }

    /**
     * Отказ загрузки (HTTP 5xx) — pre-dispatch сбой: C06 не вызывался, эффекта на
     * переписку нет, значит это отказ (клейм освобождается), а не вечное удержание
     * UNKNOWN (ресью N1 15.11, хвост «pre-dispatch hang»).
     */
    @Test void uploadFailureDoesNotSendHalfMessage() {
        try (var harness = new Harness(5)) {
            harness.site.uploadStatus = 503;
            var command = new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                    UUID.randomUUID(), ACCOUNT, "91000003", kit.penny.clientbus.common.enums.MessageType.IMAGE,
                    "", List.of(new kit.penny.clientbus.connector.api.model.ConnectorAttachment(
                            kit.penny.clientbus.common.enums.MessageAttachmentType.IMAGE,
                            "x.jpg", "image/jpeg", 3L, new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}))),
                    UUID.randomUUID(), "92000001");
            var rejected = assertThrows(kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException.class,
                    () -> harness.connector.handle(command));
            assertTrue(rejected.getCause() instanceof kit.penny.clientbus.integration.dikidi.DikidiFailure,
                    "причина — классифицированный сбой платформы до C06");
            assertEquals(1, harness.site.uploads.size());
            assertEquals(0, harness.site.sendForms.size(), "без доказанного файла сообщение не уходит");
        }
    }

    /** Диалог нерешаем (нет C04-подтверждения) — тоже pre-dispatch: отказ, не UNKNOWN. */
    @Test void unresolvableDialogIsRejectedNotHeld() {
        try (var harness = new Harness(5)) {
            var command = new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                    UUID.randomUUID(), ACCOUNT, "нет-такого", kit.penny.clientbus.common.enums.MessageType.TEXT,
                    "привет", List.of(), UUID.randomUUID(), null);
            assertThrows(kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException.class,
                    () -> harness.connector.handle(command));
            assertEquals(0, harness.site.sendForms.size());
        }
    }

    /** Неприменимый курсор прочтения — fail-closed: никакого POST, ложных отметок нет. */
    @Test void unreadableReadCursorMarksNothing() {
        try (var harness = new Harness(5)) {
            harness.connector.handle(new kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand(
                    ACCOUNT, "91000003", "не-число", "7"));
            assertTrue(harness.site.readForms.isEmpty(), "мусорный lastReadExternalId не отмечает ничего");
        }
    }

    /**
     * Ресью N1 15.11: свежие страницы ЦЕЛИКО новее курсора — отметка должна дойти
     * до глубинных страниц (иначе >20 непрочитанных зависают навсегда).
     */
    @Test void markReadPagesDownToReadCursor() {
        try (var harness = new Harness(5)) {
            harness.site.pages.put("7|null", "{\"data\":{\"list\":["
                    + "{\"id\":300,\"date\":1794754990,\"user_id\":91000001,\"text\":\"а\",\"joining\":null,\"out\":1},"
                    + "{\"id\":290,\"date\":1794754980,\"user_id\":91000001,\"text\":\"б\",\"joining\":null,\"out\":1},"
                    + "{\"id\":280,\"date\":1794754970,\"user_id\":91000001,\"text\":\"в\",\"joining\":null,\"out\":1}"
                    + "]}}");
            harness.site.pages.put("7|280", "{\"data\":{\"list\":["
                    + "{\"id\":150,\"date\":1794754960,\"user_id\":91000003,\"text\":\"прочитать\",\"joining\":null,\"out\":0},"
                    + "{\"id\":140,\"date\":1794754950,\"user_id\":91000001,\"text\":\"исходящее\",\"joining\":null,\"out\":1},"
                    + "{\"id\":120,\"date\":1794754940,\"user_id\":91000003,\"text\":\"тоже\",\"joining\":null,\"out\":0}"
                    + "]}}");
            harness.connector.handle(new kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand(
                    ACCOUNT, "91000003", "160", "7"));
            assertEquals(1, harness.site.readForms.size());
            assertEquals(List.of("150", "120"), allValues(harness.site.readForms.get(0), "msg[]"),
                    "вторая страница не потеряна, исходящее не отмечено");
        }
    }
}
