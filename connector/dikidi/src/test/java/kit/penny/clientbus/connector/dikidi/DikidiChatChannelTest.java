package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.connector.api.event.ChannelEvent;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.connector.api.listener.IChannelListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ревю ручной проверки 15.11: вход DIKIDI в CONNECTED обязан порождать ChannelEvent(CONNECTED) —
 * тот же контракт, что у TelegramAuthorizationStateListener; на него серверный
 * ChannelSynchronizationListener запускает первую выгрузку диалогов сразу, не дожидаясь
 * 15-минутного тика планировщика (иначе свежий канал «появился, но чаты не загрузились»).
 */
class DikidiChatChannelTest {

    private final UUID account = UUID.randomUUID();

    private static final class Status implements HostChannelStatus {
        ChannelConnectionStatus last;
        boolean allowTransition = true;
        @Override public void expectDisconnect(UUID a, ChannelConnectionStatus s) { last = s; }
        @Override public void beginConnecting(UUID a) { last = ChannelConnectionStatus.CONNECTING; }
        @Override public boolean transition(UUID a, ChannelConnectionStatus s, String r) {
            last = s; return allowTransition;
        }
    }

    private static final class Listener implements IChannelListener {
        final List<ChannelEvent> events = new ArrayList<>();
        @Override public void channelChanged(ChannelEvent event) { events.add(event); }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private DikidiChannelConnector stubConnector(DikidiChatAccess.Result result) {
        return new DikidiChannelConnector(null, null, null, null, null, null, null, 1) {
            @Override public DikidiChatAccess.Result verifyChatAccess(UUID channelAccountId) {
                return result;
            }
        };
    }

    @Test
    void connectFiresConnectedEventSoHostStartsFirstSync() {
        Status status = new Status();
        Listener listener = new Listener();
        DikidiChatChannel channel = new DikidiChatChannel(
                stubConnector(new DikidiChatAccess.Result(true, null)),
                provider((HostChannelAccounts) null),
                provider(status),
                provider((IChannelListener) listener));

        channel.connect(account);

        assertEquals(ChannelConnectionStatus.CONNECTED, status.last);
        assertEquals(List.of(new ChannelEvent(account, ChannelConnectionStatus.CONNECTED)),
                listener.events);
    }

    @Test
    void deniedProbeDoesNotFireConnectedEvent() {
        Status status = new Status();
        Listener listener = new Listener();
        DikidiChatChannel channel = new DikidiChatChannel(
                stubConnector(new DikidiChatAccess.Result(false, "AUTH_REQUIRED")),
                provider((HostChannelAccounts) null),
                provider(status),
                provider((IChannelListener) listener));

        DikidiChatAccess.Result result = channel.connect(account);

        assertEquals(ChannelConnectionStatus.ERROR, status.last);
        assertTrue(result.errorCode().startsWith("AUTH"));
        assertTrue(listener.events.isEmpty());
    }

    /**
     * Ресью N1 15.11: успешная проба НЕ порождает событие, если хост ОТКАЗАЛ переходу
     * (expectDisconnect после пользовательского «отключить»). Безотказный fire
     * воскресал бы recent-chats-синк и LIVE-уведомления для отключённого аккаунта.
     */
    @Test
    void refusedTransitionDoesNotFireConnectedEvent() {
        Status status = new Status();
        status.allowTransition = false;
        Listener listener = new Listener();
        DikidiChatChannel channel = new DikidiChatChannel(
                stubConnector(new DikidiChatAccess.Result(true, null)),
                provider((HostChannelAccounts) null),
                provider(status),
                provider((IChannelListener) listener));

        channel.connect(account);

        assertTrue(listener.events.isEmpty(),
                "отказанный переход не имеет права порождать первую выгрузку");
    }
}
