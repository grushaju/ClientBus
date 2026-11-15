package kit.penny.clientbus.connector.telegram.startup;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import kit.penny.clientbus.connector.api.host.HostChannelStatus;
import java.util.List;

@Service
public class TelegramClientStartupService {

    private static final Logger log =
            LoggerFactory.getLogger(TelegramClientStartupService.class);

    private final HostChannelAccounts hostChannelAccounts;
    private final TelegramChannelAccountService channelAccountService;
    private final HostChannelStatus hostChannelStatus;
    private final kit.penny.clientbus.connector.telegram.storage.TelegramDataStorage dataStorage;

    public TelegramClientStartupService(
            HostChannelAccounts hostChannelAccounts, TelegramChannelAccountService channelAccountService, HostChannelStatus hostChannelStatus,
            kit.penny.clientbus.connector.telegram.storage.TelegramDataStorage dataStorage
    ) {
        this.hostChannelAccounts = hostChannelAccounts;
        this.channelAccountService = channelAccountService;
        this.hostChannelStatus = hostChannelStatus;
        this.dataStorage = dataStorage;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void restoreTelegramClients() {

        List<java.util.UUID> accountIds = new java.util.ArrayList<>(
                hostChannelAccounts.findIdsByChannelTypeAndChannelStatuses(
                                ChannelType.TELEGRAM,
                                List.of(
                                        ChannelConnectionStatus.CONNECTED,
                                        ChannelConnectionStatus.CONNECTING
                                )
                        )
        );

        // Штатный останов TDLib даёт Closing/Closed, и листener штамповал DISCONNECTED, а
        // фильтром выше такие аккаунты не поднимались вообще: живая TDLib-сессия на диске
        // оставалась нетронутой, а канал «требовал перелогина» до ручного нажатия.
        // Теперь DISCONNECTED с сохранённой сессией тоже восстанавливается; TDLib сам
        // честно доложит состояние (Ready или ожидание кода). Отключённые аккаунты
        // приходят без сессии (disconnect удаляет данные), выключенные — в DISABLED.
        hostChannelAccounts.findIdsByChannelTypeAndChannelStatuses(
                        ChannelType.TELEGRAM,
                        List.of(ChannelConnectionStatus.DISCONNECTED)
                ).stream()
                .filter(dataStorage::hasPersistedSession)
                .filter(accountId -> !accountIds.contains(accountId))
                .forEach(accountIds::add);

        log.info( "Restoring {} Telegram client(s)",  accountIds.size() );

        for (java.util.UUID accountId : accountIds) {  restore(accountId); }
    }

    private void restore(java.util.UUID accountId) {

        try {
            channelAccountService.create(accountId);

            log.info(
                    "Telegram client restored: channelAccountId={}",  accountId
            );

        } catch (Exception e) {

            log.error(
                    "Failed to restore Telegram client: channelAccountId={}",  accountId,  e
            );

            hostChannelStatus.transition(accountId, ChannelConnectionStatus.ERROR, "CONNECTOR_RESTORE_FAILED");
        }
    }
}