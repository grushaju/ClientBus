package kit.penny.clientbus.server.connector.telegram.startup;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import kit.penny.clientbus.server.service.ChannelStatusService;
import java.util.List;

@Service
public class TelegramClientStartupService {

    private static final Logger log =
            LoggerFactory.getLogger(TelegramClientStartupService.class);

    private final ChannelAccountRepository channelAccountRepository;
    private final TelegramChannelAccountService channelAccountService;
    private final ChannelStatusService channelStatus;

    public TelegramClientStartupService(
            ChannelAccountRepository channelAccountRepository,
            TelegramChannelAccountService channelAccountService,
            ChannelStatusService channelStatus
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.channelAccountService = channelAccountService;
        this.channelStatus = channelStatus;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void restoreTelegramClients() {

        List<ChannelAccountEntity> accounts =
                channelAccountRepository
                        .findAllByChannelTypeAndChannelStatusIn(
                                ChannelType.TELEGRAM,
                                List.of(
                                        ChannelConnectionStatus.CONNECTED,
                                        ChannelConnectionStatus.CONNECTING
                                )
                        );

        log.info(
                "Restoring {} Telegram client(s)",
                accounts.size()
        );

        for (ChannelAccountEntity account : accounts) {
            restore(account);
        }
    }

    private void restore(ChannelAccountEntity account) {

        try {
            channelAccountService.create(account.getId());

            log.info(
                    "Telegram client restored: channelAccountId={}",
                    account.getId()
            );

        } catch (Exception e) {

            log.error(
                    "Failed to restore Telegram client: channelAccountId={}",
                    account.getId(),
                    e
            );

            channelStatus.transition(account.getId(), ChannelConnectionStatus.ERROR, "CONNECTOR_RESTORE_FAILED");
        }
    }
}