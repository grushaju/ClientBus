package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiRequests;
import kit.penny.clientbus.integration.dikidi.DikidiTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 13.5: проверка доступа C01→C02 по аккаунту канала; binding-конфигурация удалена. */
class DikidiChatAccessTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();

    static final class Site implements DikidiTransport {
        final ArrayDeque<String> homePages = new ArrayDeque<>(List.of(
                "<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t1\"></html>"));
        final java.util.List<String> uris = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        int shellStatus = 200, listStatus = 200;
        String listBody = "{\"data\":{\"list\":[]}}";
        @Override public Response exchange(String method, URI uri, List<Map.Entry<String, String>> form,
                                           boolean ajax, String projectId, Duration timeout) {
            String address = uri.toString();
            uris.add(address);
            if (address.equals("https://dikidi.ru/ru/") && homePages.size() > 0) return new Response(200, homePages.removeFirst());
            if (address.equals("https://auth.dikidi.ru/ajax/check/auth/")) return new Response(200, "{\"html\":\"<input type=\\\"hidden\\\" name=\\\"csrf\\\" value=\\\"c1\\\">\"}");
            if (address.equals("https://auth.dikidi.ru/ajax/user/auth/")) return new Response(200, "{\"callback\":\"sw.auth.complete\"}");
            if (address.startsWith("https://dikidi.ru/ru/owner/chat/")) return new Response(shellStatus, "<html>owner chat</html>");
            if (address.startsWith("https://chat.dikidi.ru/api/dialogs/list/"))
                return new Response(listStatus, listStatus == 200 ? listBody : "{\"error\":{\"code\":400,\"message\":\"Недостаточно прав\"}}");
            return new Response(404, "unexpected " + address);
        }
    }

    static class Accounts implements HostChannelAccounts {
        final HostChannelAccountSnapshot snapshot;
        /** Зеркало аватара канала: коннектор обязан звать хост только с доказуемым владельцем. */
        final List<Object[]> avatars = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Accounts(HostChannelAccountSnapshot snapshot) { this.snapshot = snapshot; }
        @Override public HostChannelAccountSnapshot require(UUID channelAccountId) { return snapshot; }
        @Override public Optional<HostChannelAccountSnapshot> find(UUID channelAccountId) { return Optional.of(snapshot); }
        @Override public List<UUID> findIdsByChannelTypeAndChannelStatuses(ChannelType channelType, List<ChannelConnectionStatus> channelStatuses) { return List.of(); }
        @Override public void updateProfile(UUID channelAccountId, String externalId, String username, String phone, String displayName, String avatarUrl) { }
        @Override public void updateAvatar(UUID channelAccountId, String avatarStorageKey) {
            avatars.add(new Object[]{channelAccountId, avatarStorageKey});
        }
        @Override public void requireWorkspaceAccessForAccount(UUID channelAccountId) { }
        @Override public UUID findExistingClientAccountId(ChannelType channelType, String externalId) { return null; }
    }

    static HostChannelAccountSnapshot snapshot(String company) {
        return new HostChannelAccountSnapshot(ACCOUNT, UUID.randomUUID(), UUID.randomUUID(),
                ChannelType.DIKIDI, ChannelConnectionStatus.CREATED, "79990000000", company);
    }

    static final class Harness implements AutoCloseable {
        final Site site = new Site();
        final DikidiClient client = new DikidiClient(
                new DikidiClient.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536), () -> site,
                new DikidiRequests(new DikidiRequests.Settings(1e9, 100000, 1024, Duration.ofSeconds(30), Duration.ofMinutes(15), 1e9, 100000),
                        DikidiRequests.memory()));
        final DikidiChatAccess access;
        Harness(String company) {
            var accounts = new Accounts(snapshot(company));
            access = new DikidiChatAccess(client, accountId -> new DikidiCredential(CONNECTION, 1, "owner",
                    "secret".toCharArray()), accounts, new DikidiChatReads());
        }
        @Override public void close() { client.close(); }
    }

    @Test void shellVisitPrecedesDialogListProof() {
        try (var harness = new Harness("100")) {
            var result = harness.access.verify(ACCOUNT);
            assertTrue(result.available(), String.valueOf(result.errorCode()));
            // C01→C02: без предварительного визита страницы владелец-чата список отвечает 400 —
            // порядок шагов зафиксирован протоколом и проверяется здесь.
            assertEquals(List.of("https://dikidi.ru/ru/",
                    "https://auth.dikidi.ru/ajax/check/auth/", "https://auth.dikidi.ru/ajax/user/auth/",
                    "https://dikidi.ru/ru/owner/chat/?company=100",
                    "https://chat.dikidi.ru/api/dialogs/list/?interface=owner&company=100&time=0&msg=true"), harness.site.uris);
        }
    }

    @Test void deniedChatShellAndBrokenListShapeFailClosed() {
        try (var harness = new Harness("100")) {
            harness.site.shellStatus = 403;
            var denied = harness.access.verify(ACCOUNT);
            assertFalse(denied.available());
            assertEquals("CHAT_ACCESS_DENIED", denied.errorCode());
        }
        try (var harness = new Harness("100")) {
            harness.site.listStatus = 403;
            var denied = harness.access.verify(ACCOUNT);
            assertFalse(denied.available());
            assertEquals("CHAT_ACCESS_DENIED", denied.errorCode());
        }
        try (var harness = new Harness("100")) {
            harness.site.listBody = "{\"data\":{}}"; // без data.list — форма не подтверждена
            var denied = harness.access.verify(ACCOUNT);
            assertFalse(denied.available());
            assertEquals("CHAT_INVALID_RESPONSE", denied.errorCode());
        }
    }

    @Test void chatFourHundredHealsBySingleRelogin() {
        try (var harness = new Harness("100")) {
            // единственный доказанный смысл 400 на chat API — предпосылка/сессия:
            // execute один раз долечивает пере-входом; shell-визит обязателен в обеих попытках.
            harness.site.listStatus = 400;
            harness.site.homePages.add("<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t2\"></html>");
            var denied = harness.access.verify(ACCOUNT);
            assertFalse(denied.available());
            assertEquals("CHAT_AUTH_REQUIRED", denied.errorCode());
            assertTrue(harness.site.uris.stream().filter(u -> u.startsWith("https://dikidi.ru/ru/owner/chat/")).count() >= 2,
                    "повтор обязан заново посетить shell-страницу");
        }
    }

    @Test void loginFailureReportsDeniedInsteadOfCrashing() {
        try (var harness = new Harness("100")) {
            harness.site.homePages.clear(); // страница входа без csrf-формы — авторизация не проходит
            harness.site.uris.clear();
            var denied = harness.access.verify(ACCOUNT);
            assertFalse(denied.available());
            assertNotNull(denied.errorCode());
        }
    }

    @Test void accountWithoutCompanyIsRejectedWithoutRequests() {
        var harness = new Harness(null);
        var result = harness.access.verify(ACCOUNT);
        assertFalse(result.available());
        assertEquals("CHAT_NO_COMPANY", result.errorCode());
        assertTrue(harness.site.uris.isEmpty());
        harness.close();
    }
}
