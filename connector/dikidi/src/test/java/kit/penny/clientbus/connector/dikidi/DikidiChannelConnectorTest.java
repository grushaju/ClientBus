package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiRequests;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 13.5: lifecycle-переходы по аккаунтам канала; запись — только доказанным протоколом. */
class DikidiChannelConnectorTest {
    @Test void supportsOnlyDikidiAndBlocksCapabilitiesWithoutProtocolProof() {
        var connector = new DikidiChannelConnector(null, null, null, null, null, null, null, 5);
        assertTrue(connector.supports(ChannelType.DIKIDI));
        assertFalse(connector.supports(ChannelType.TELEGRAM));
        // Отправка текста реализована (C06, захват 15.11.2026); пустой текст и вложения —
        // отказ ДО обращения к платформе, классифицирован как доказанное отсутствие эффекта.
        assertThrows(kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException.class,
                () -> connector.handle(new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                        UUID.randomUUID(), UUID.randomUUID(), "13731015",
                        kit.penny.clientbus.common.enums.MessageType.TEXT, "   ", List.of())));
        assertThrows(kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException.class,
                () -> connector.handle(new kit.penny.clientbus.connector.api.command.SendMessageCommand(
                        UUID.randomUUID(), UUID.randomUUID(), "13731015",
                        kit.penny.clientbus.common.enums.MessageType.IMAGE, "подпись",
                        List.of(new kit.penny.clientbus.connector.api.model.ConnectorAttachment(
                                kit.penny.clientbus.common.enums.MessageAttachmentType.IMAGE,
                                "x.jpg", "image/jpeg", 3L, null)))));
        // Отметка прочтения реализована (C08, форма подтверждена публичным backend.js
        // 15.11.2026): это запись в платформу — без рабочих подключений она обязана
        // падать, а не молча проходить (молчаливый проход = ложная отметка).
        assertThrows(RuntimeException.class, () -> connector.handle(
                new kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand(
                        UUID.randomUUID(), "13731015", "302525636", "11633401")));
        // Чтение — реализовано в 13.5: коннектор действительно несёт обе read-capability.
        assertTrue(connector instanceof kit.penny.clientbus.connector.api.capability.DialogReader);
        assertTrue(connector instanceof kit.penny.clientbus.connector.api.capability.HistoryReader);
    }

    private record Transition(UUID account, ChannelConnectionStatus status, String reason) {}
    private static final class Status implements HostChannelStatus {
        final List<Transition> transitions = new ArrayList<>();
        final List<UUID> connecting = new ArrayList<>();
        final List<Object[]> expected = new ArrayList<>();
        public void expectDisconnect(UUID id, ChannelConnectionStatus status) { expected.add(new Object[]{id, status}); }
        public void beginConnecting(UUID id) { connecting.add(id); }
        public boolean transition(UUID id, ChannelConnectionStatus status, String reason) {
            transitions.add(new Transition(id, status, reason)); return true;
        }
    }
    private static final class Accounts extends DikidiChatAccessTest.Accounts {
        final List<UUID> dikidi = new ArrayList<>();
        List<ChannelConnectionStatus> lastStatuses = List.of();
        Accounts(HostChannelAccountSnapshot snapshot) { super(snapshot); }
        @Override public List<UUID> findIdsByChannelTypeAndChannelStatuses(ChannelType type, List<ChannelConnectionStatus> statuses) {
            lastStatuses = statuses;
            return type == ChannelType.DIKIDI ? dikidi : List.of();
        }
    }
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public java.util.stream.Stream<T> stream() { return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value); }
        };
    }
    private static final class Wiring implements AutoCloseable {
        final DikidiChatAccessTest.Site site = new DikidiChatAccessTest.Site();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final Accounts accounts = new Accounts(DikidiChatAccessTest.snapshot("100"));
        final Status status = new Status();
        final DikidiClient client = new DikidiClient(
                new DikidiClient.Settings(Duration.ofSeconds(2), Duration.ofSeconds(15), 65536), () -> site,
                new DikidiRequests(new DikidiRequests.Settings(1e9, 100000, 1024, Duration.ofSeconds(30),
                        Duration.ofMinutes(15), 1e9, 100000), DikidiRequests.memory()));
        final DikidiChannelConnector connector;
        final DikidiChatChannel channel;
        Wiring() {
            // два аккаунта = две connection = два входа: страница входа нужна на каждый
            site.homePages.add("<html><input type=\"hidden\" name=\"telegram_csrf\" value=\"t2\"></html>");
            DikidiTechnicalCredentials credentials = accountId -> new DikidiCredential(UUID.randomUUID(), 1,
                    "owner", "s".toCharArray());
            var reads = new DikidiChatReads();
            var access = new DikidiChatAccess(client, credentials, accounts, reads);
            connector = new DikidiChannelConnector(client, credentials, accounts, null, null, reads, access, 5);
            channel = new DikidiChatChannel(connector, provider((HostChannelAccounts) accounts), provider((HostChannelStatus) status),
                    provider((kit.penny.clientbus.connector.api.listener.IChannelListener) null));
        }
        @Override public void close() { client.close(); }
    }

    @Test void deniedAccessKeepsAccountOutOfConnectedWithReason() {
        try (var wiring = new Wiring()) {
            wiring.accounts.dikidi.add(wiring.first);
            wiring.site.shellStatus = 403;
            wiring.channel.refresh();
            assertEquals(List.of(new Transition(wiring.first, ChannelConnectionStatus.ERROR,
                    "DIKIDI_CHAT_ACCESS_CHAT_ACCESS_DENIED")), wiring.status.transitions);
        }
    }

    @Test void verifiedAccessConnectsEachPendingAccount() {
        try (var wiring = new Wiring()) {
            wiring.accounts.dikidi.addAll(List.of(wiring.first, wiring.second));
            wiring.channel.refresh();
            assertEquals(List.of(
                    new Transition(wiring.first, ChannelConnectionStatus.CONNECTED, null),
                    new Transition(wiring.second, ChannelConnectionStatus.CONNECTED, null)), wiring.status.transitions);
            // 13.5: CREATED (свежепровизионенный канал) тоже доводится периодическим recheck.
            assertTrue(wiring.accounts.lastStatuses.contains(ChannelConnectionStatus.CREATED));
        }
    }

    @Test void manualConnectAnnouncesIntentBeforeVerification() {
        try (var wiring = new Wiring()) {
            var result = wiring.channel.connect(wiring.first);
            assertTrue(result.available());
            assertEquals(List.of(wiring.first), wiring.status.connecting);
            assertEquals(List.of(new Transition(wiring.first, ChannelConnectionStatus.CONNECTED, null)),
                    wiring.status.transitions);
        }
    }

    @Test void manualDisconnectIsExpectedNotActionable() {
        try (var wiring = new Wiring()) {
            wiring.channel.disconnect(wiring.first);
            assertEquals(1, wiring.status.expected.size());
            assertEquals(wiring.first, wiring.status.expected.get(0)[0]);
            assertEquals(ChannelConnectionStatus.DISCONNECTED, wiring.status.expected.get(0)[1]);
        }
    }
}
