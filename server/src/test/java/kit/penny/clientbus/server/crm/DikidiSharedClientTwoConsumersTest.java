package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.crm.api.CrmAccess;
import kit.penny.clientbus.crm.dikidi.DikidiProvider;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiRequests;
import kit.penny.clientbus.integration.dikidi.DikidiTransport;
import kit.penny.clientbus.integration.dikidi.RequestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.4 шаг 3: два потребителя одного DikidiClient. CRM-адаптер и чатовый
 * потребитель (профиль OWNER_CHAT) входят один раз на версию учётных данных,
 * делят один ограничитель, переживают инвалидацию согласованно, а локальная
 * остановка одного потребителя не закрывает транспорт другого. Сборка
 * повторяет CrmConfiguration: клиент — отдельный бин с единственным
 * close-владельцем.
 */
class DikidiSharedClientTwoConsumersTest {
    private static final class Site implements DikidiTransport {
        final ArrayDeque<String> homePages = new ArrayDeque<>(List.of(
                "<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t1\"></html>"));
        final AtomicInteger logins = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final java.util.List<String> uris = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                           boolean ajax, String projectId, Duration timeout) {
            String address = uri.toString();
            uris.add(address);
            if (address.equals("https://dikidi.ru/ru/") && homePages.size() > 0) return new Response(200, homePages.removeFirst());
            if (address.equals("https://auth.dikidi.ru/ajax/check/auth/")) return new Response(200, "{\"html\":\"<input type=\\\"hidden\\\" name=\\\"csrf\\\" value=\\\"c1\\\">\"}");
            if (address.equals("https://auth.dikidi.ru/ajax/user/auth/")) { logins.incrementAndGet(); return new Response(200, "{\"callback\":\"sw.auth.complete\"}"); }
            if (address.equals("https://dikidi.ru/ru/")) return new Response(200, "<html><a href=\"https://auth.dikidi.ru/user/logout/\">выйти</a></html>");
            if (address.startsWith("https://chat.dikidi.ru/api/dialogs/list/")) return new Response(200, "{\"data\":{\"list\":[]}}");
            return new Response(404, "unexpected " + address);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
    private static final class Harness implements AutoCloseable {
        final Site site = new Site();
        final DikidiClient client = new DikidiClient(
                new DikidiClient.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536),
                () -> site,
                new DikidiRequests(new DikidiRequests.Settings(1e9, 100000, 1024, Duration.ofSeconds(30), Duration.ofMinutes(15), 1e9, 100000),
                        DikidiRequests.memory()));
        final DikidiProvider provider = new DikidiProvider(
                new DikidiProvider.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536), client);
        final UUID connection = UUID.randomUUID();
        String chatProbe() {
            try (var credential = new DikidiCredential(connection, 1, "owner", "secret".toCharArray())) {
                return client.execute(credential, Instant.now().plusSeconds(30), session -> session.exchange(
                        "GET", "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company=100&time=0&msg=true",
                        null, true, "100", RequestProfile.OWNER_CHAT, null).body());
            }
        }
        @Override public void close() { client.close(); }
    }

    @Test void crmAndChatConsumersShareOneLoginAndOneRegulator() {
        try (var harness = new Harness()) {
            try (var access = new CrmAccess(harness.connection, 1, "owner", "secret".toCharArray())) {
                harness.provider.verifyConnection(access);   // вход + CRM-доказательство
                assertEquals("{\"data\":{\"list\":[]}}", harness.chatProbe());
            }
            assertEquals(1, harness.site.logins.get(), "один вход на версию учётных данных для обоих потребителей");
            // Ограничитель общий: login(3) + CRM-доказательство + чат = 5 физических запросов.
            assertEquals(5, harness.client.diagnostics().sent());
        }
    }
    @Test void concurrentConsumersCoalesceIntoOneLogin() throws Exception {
        try (var harness = new Harness()) {
            harness.site.homePages.add("<html><a href=\"https://auth.dikidi.ru/user/logout/\">выйти</a></html>");
            var pool = Executors.newFixedThreadPool(2);
            try (var access = new CrmAccess(harness.connection, 1, "owner", "secret".toCharArray())) {
                var crm = pool.submit(() -> { harness.provider.verifyConnection(access); return true; });
                var chat = pool.submit(harness::chatProbe);
                assertTrue(crm.get(20, TimeUnit.SECONDS));
                assertEquals("{\"data\":{\"list\":[]}}", chat.get(20, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            assertEquals(1, harness.site.logins.get(), "нет одновременных повторных входов CRM и чата");
        }
    }
    @Test void invalidationForcesBothConsumersToReLoginOnce() {
        try (var harness = new Harness()) {
            try (var access = new CrmAccess(harness.connection, 1, "owner", "secret".toCharArray())) {
                harness.provider.verifyConnection(access);
                harness.chatProbe();
                assertEquals(1, harness.site.logins.get());
                harness.site.homePages.add("<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t2\"></html>");
                harness.provider.invalidate(harness.connection);   // смена секрета/отзыв — рушит сессию обоим
                assertEquals(1, harness.site.closes.get());
                harness.chatProbe();                                // чат входит заново
                harness.provider.verifyConnection(access);          // и CRM — ровно один новый вход
                assertEquals(2, harness.site.logins.get());
            }
        }
    }
    @Test void adapterCloseDoesNotDisturbTheSharedClient() {
        var harness = new Harness();
        try (var access = new CrmAccess(harness.connection, 1, "owner", "secret".toCharArray())) {
            harness.provider.verifyConnection(access);
            harness.provider.close();          // адаптер не владеет клиентом (13.4: один close-владелец — сборка)
            assertEquals(0, harness.site.closes.get());
            harness.site.homePages.add("<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t2\"></html>");
            assertEquals("{\"data\":{\"list\":[]}}", harness.chatProbe()); // чат работает после остановки CRM-адаптера
        }
    }
}
