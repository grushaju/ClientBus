package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.event.ChannelEvent;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import kit.penny.clientbus.connector.api.listener.AbstractEventDispatcher;
import kit.penny.clientbus.connector.api.listener.IChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;

/**
 * Жизненный цикл чатового канала DIKIDI (13.4; с 13.5 — по аккаунтам канала).
 * Вход в CONNECTED разрешён только после успешной проверки доступа C01→C02
 * (CRM-health таким доказательством не считается). Аккаунты создаёт хост при
 * назначении DIKIDI-проекта на workspace (статус CREATED) — периодический
 * recheck доводит CREATED/CONNECTING/DISCONNECTED/ERROR до CONNECTED или ERROR
 * с причиной DIKIDI_CHAT_ACCESS_*. DISABLED — воля пользователя или отвязка
 * проекта: recheck его не касается, connect() возвращает осознанно.
 * enable — beginConnecting + повторная проверка; disable — ожидаемое отключение
 * через HostChannelStatus.expectDisconnect; сессия клиента остаётся за общим
 * владельцем (CRM), закрытие адаптера на неё не влияет.
 */
public class DikidiChatChannel extends AbstractEventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DikidiChatChannel.class);
    private final DikidiChannelConnector connector;
    private final HostChannelAccounts accounts;
    private final HostChannelStatus status;
    public DikidiChatChannel(DikidiChannelConnector connector,
                             org.springframework.beans.factory.ObjectProvider<HostChannelAccounts> accounts,
                             org.springframework.beans.factory.ObjectProvider<HostChannelStatus> status,
                             org.springframework.beans.factory.ObjectProvider<IChannelListener> listeners) {
        super(listeners.stream().toList());
        this.connector = connector; this.accounts = accounts.getIfAvailable(); this.status = status.getIfAvailable();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void verifyBeforeConnected() { refresh(); }
    /** Периодическая доводка CREATED-каналов после провизионизации и отвалившихся аккаунтов. */
    @Scheduled(initialDelayString = "${clientbus.dikidi.chat.recheck-ms:60000}",
            fixedDelayString = "${clientbus.dikidi.chat.recheck-ms:60000}")
    public void scheduledRecheck() { refresh(); }
    /** Один прогон: проверка доступа каждого неподключённого аккаунта DIKIDI и его переход. */
    public void refresh() {
        if (accounts == null || status == null) return;
        for (var accountId : accounts.findIdsByChannelTypeAndChannelStatuses(ChannelType.DIKIDI, List.of(
                ChannelConnectionStatus.CREATED, ChannelConnectionStatus.CONNECTING,
                ChannelConnectionStatus.DISCONNECTED, ChannelConnectionStatus.ERROR)))
            verifyAndTransition(accountId);
    }
    /** Пользовательское «подключить»: намерение явно, поэтому CONNECTING снимает expectDisconnect. */
    public DikidiChatAccess.Result connect(UUID accountId) {
        if (status == null) return new DikidiChatAccess.Result(false, "HOST_STATUS_UNAVAILABLE");
        status.beginConnecting(accountId);
        return verifyAndTransition(accountId);
    }
    /** Пользовательское «отключить»: ожидаемое отключение — без инцидента и уведомлений. */
    public void disconnect(UUID accountId) {
        status.expectDisconnect(accountId, ChannelConnectionStatus.DISCONNECTED);
    }
    private DikidiChatAccess.Result verifyAndTransition(UUID accountId) {
        var result = connector.verifyChatAccess(accountId);
        if (result.available()) {
            // Событие — только на СОСТОЯВШИЙСЯ переход (ресью N1 15.11): после
            // expectDisconnect (пользовательское «отключить») HostChannelStatus переход
            // ОТКАЗЫВАЕТ, а безотказный fire воскресил бы периодический recent-chats и
            // LIVE-уведомления для отключённого аккаунта (DISCONNECTED попадает в
            // выборку recheck).
            if (status.transition(accountId, ChannelConnectionStatus.CONNECTED, null)) {
                log.info("DIKIDI chat access verified: account={} connected.", accountId);
                // Зеркало TelegramAuthorizationStateListener: вход в CONNECTED сам
                // запускает первую выгрузку диалогов (ChannelSynchronizationListener),
                // иначе канал ждёт периодического тика планировщика (до 15 минут).
                fireChannelChanged(new ChannelEvent(accountId, ChannelConnectionStatus.CONNECTED));
            }
        } else {
            if (status.transition(accountId, ChannelConnectionStatus.ERROR, "DIKIDI_CHAT_ACCESS_" + result.errorCode()))
                log.error("DIKIDI chat access denied ({}): account={} stays out of CONNECTED.",
                        result.errorCode(), accountId);
        }
        return result;
    }
}
