package kit.penny.clientbus.integration.dikidi;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.4: общий DikidiClient для CRM- и чат-потребителя. Один вход на версию
 * учётных данных, один ограничитель, инвалидация рушит сессию обоим, локальная
 * остановка одного потребителя не трогает другого (close только у сборки).
 */
class DikidiClientTest {
    private static final class Fake implements DikidiTransport {
        final ArrayDeque<DikidiTransport.Response> responses = new ArrayDeque<>();
        final java.util.List<String> addresses = new java.util.ArrayList<>();
        final java.util.List<RequestProfile> profiles = new java.util.ArrayList<>();
        final AtomicInteger logins = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        boolean closed;
        Fake queue(int status, String body) { responses.add(new DikidiTransport.Response(status, body)); return this; }
        Fake login() {
            queue(200, "<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t1\"></html>");
            queue(200, "{\"html\":\"<input type=\\\"hidden\\\" name=\\\"csrf\\\" value=\\\"c1\\\">\"}");
            queue(200, "{\"callback\":\"sw.auth.complete\"}");
            return this;
        }
        @Override public DikidiTransport.Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                                           boolean ajax, String projectId, Duration timeout) {
            return exchange(method, uri, form, ajax, projectId, RequestProfile.CRM, timeout, new Observer() {});
        }
        @Override public DikidiTransport.Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                boolean ajax, String projectId, RequestProfile profile, Duration timeout, Observer observer) {
            observer.beforeSend();
            addresses.add(method + " " + uri);
            profiles.add(profile);
            if (uri.toString().equals("https://auth.dikidi.ru/ajax/user/auth/")) logins.incrementAndGet();
            observer.dispatched();
            if (closed) throw new IllegalStateException("transport used after close");
            var response = responses.removeFirst();
            observer.response(response);
            return response;
        }
        @Override public void close() { closes.incrementAndGet(); closed = true; }
    }
    private static DikidiClient.Settings settings() {
        return new DikidiClient.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536);
    }
    private static DikidiCredential credential(java.util.UUID id, long version) {
        return new DikidiCredential(id, version, "owner", "secret".toCharArray());
    }

    @Test void crmAndChatConsumersShareOneLoginAndOneSession() {
        var fake = new Fake().login();
        fake.queue(200, "{\"ok\":true}");   // CRM journal
        fake.queue(200, "{\"data\":{\"list\":[]}}"); // chat list
        var id = java.util.UUID.randomUUID();
        try (var client = new DikidiClient(settings(), () -> fake)) {
            var until = Instant.now().plusSeconds(30);
            assertEquals("{\"ok\":true}", client.execute(credential(id, 1), until,
                    session -> session.call("POST", "https://dikidi.ru/ru/owner/ajax/journal/api/?company=100",
                            List.of(Map.entry("request[get][]", "company.accesses")), true, "100", null)));
            var chat = client.execute(credential(id, 1), until, session -> session.exchange("GET",
                    "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company=100", null, true, "100",
                    RequestProfile.OWNER_CHAT, null));
            assertEquals(200, chat.status());
            assertEquals(1, fake.logins.get());               // один вход на версию учётных данных
            assertEquals(java.util.Set.of(id), client.activeConnections());
            assertEquals(List.of(RequestProfile.CRM, RequestProfile.OWNER_CHAT),
                    fake.profiles.subList(fake.profiles.size() - 2, fake.profiles.size()));
        }
        assertEquals(1, fake.closes.get());                    // закрыл только владелец сборки
    }
    @Test void credentialVersionChangeGetsFreshSessionAndStaleHandleCannotReuseOldOne() {
        var fake = new Fake().login();
        fake.queue(200, "crm-v1");
        var id = java.util.UUID.randomUUID();
        try (var client = new DikidiClient(settings(), () -> fake)) {
            var until = Instant.now().plusSeconds(30);
            client.execute(credential(id, 1), until, session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null));
            fake.login();
            fake.queue(200, "crm-v2");
            // Смена версии учётных данных — другая сессия; старая не обслуживает новых handles.
            client.execute(credential(id, 2), until, session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null));
            assertEquals(2, fake.logins.get());
            assertEquals(1, client.activeConnections().size());
        }
    }
    @Test void invalidateDestroysSharedSessionForBothConsumers() {
        var fake = new Fake().login();
        fake.queue(200, "first");
        var replacement = new Fake().login().queue(200, "second");
        var transports = new java.util.concurrent.atomic.AtomicInteger();
        var id = java.util.UUID.randomUUID();
        var client = new DikidiClient(settings(), () -> transports.getAndIncrement() == 0 ? fake : replacement);
        var until = Instant.now().plusSeconds(30);
        client.execute(credential(id, 1), until, session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null));
        client.invalidate(id);
        assertEquals(1, fake.closes.get());
        assertTrue(client.activeConnections().isEmpty());
        // Оба потребителя после инвалидации входят заново и в новый транспорт — общего handle не осталось.
        assertEquals("second", client.execute(credential(id, 1), until,
                session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null)));
        assertEquals(1, replacement.logins.get());
        assertEquals(2, fake.logins.get() + replacement.logins.get());
        client.close();
        assertEquals(1, replacement.closes.get());
    }
    @Test void executeOnceNeverReplaysAfterSessionLoss() {
        var fake = new Fake().login();
        fake.queue(401, "expired");   // изменяющий запрос попал в потерю сессии
        var id = java.util.UUID.randomUUID();
        try (var client = new DikidiClient(settings(), () -> fake)) {
            var until = Instant.now().plusSeconds(30);
            var error = assertThrows(DikidiFailure.class, () -> client.executeOnce(credential(id, 1), until,
                    session -> session.call("POST", "https://dikidi.ru/ru/owner/ajax/journal/record_save/",
                            List.of(Map.entry("intent", "create")), true, "100", null)));
            assertEquals(DikidiFailure.Code.AUTH_REQUIRED, error.code());
            assertEquals(1, fake.logins.get());  // без повторного входа
            assertEquals(4, fake.addresses.size()); // login(3) + ровно одна попытка записи
        }
    }
    @Test void readRetriesOnceThroughRecoveryButOnlyForReads() {
        var fake = new Fake().login();
        fake.queue(401, "expired");   // чтение на истёкшей сессии
        fake.login();                 // восстановление
        fake.queue(200, "fresh");
        var id = java.util.UUID.randomUUID();
        try (var client = new DikidiClient(settings(), () -> fake)) {
            var until = Instant.now().plusSeconds(60);
            assertEquals("fresh", client.execute(credential(id, 1), until,
                    session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null)));
            assertEquals(2, fake.logins.get());
        }
    }
    @Test void sessionGateDegradationStopsEveryCallBeforeAnyRequest() {
        var fake = new Fake().login();
        var id = java.util.UUID.randomUUID();
        try (var client = new DikidiClient(settings(), () -> fake)
                .sessionGate(connectionId -> {
                    throw new DikidiFailure(DikidiFailure.Code.TEMPORARY_FAILURE,
                            java.time.Instant.now().plusSeconds(30), true);
                })) {
            var until = java.time.Instant.now().plusSeconds(30);
            assertEquals(DikidiFailure.Code.TEMPORARY_FAILURE, assertThrows(DikidiFailure.class, () -> client.execute(
                    credential(id, 1), until, session -> session.call("GET", "https://dikidi.ru/ru/", null, false, null, null))).code());
            // Не отправлено ни одного запроса, включая шаги входа: guard дёшево отсекает до транспорта.
            assertTrue(fake.addresses.isEmpty());
            assertEquals(0, fake.logins.get());
        }
    }
    @Test void callerObserverSeesDispatchEvidence() {
        var fake = new Fake().login();
        fake.queue(200, "ok");
        var id = java.util.UUID.randomUUID();
        var dispatched = new AtomicInteger();
        try (var client = new DikidiClient(settings(), () -> fake)) {
            client.execute(credential(id, 1), Instant.now().plusSeconds(30), session -> session.call(
                    "GET", "https://dikidi.ru/ru/", null, false, null,
                    new DikidiTransport.Observer() {
                        @Override public void dispatched() { dispatched.incrementAndGet(); }
                    }));
            assertEquals(1, dispatched.get()); // шаги входа caller-observer не видят, бизнес-запрос — ровно один
        }
    }
}
