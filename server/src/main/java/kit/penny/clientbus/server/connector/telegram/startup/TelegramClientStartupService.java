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

import java.util.List;

@Service
public class TelegramClientStartupService {

    private static final Logger log =
            LoggerFactory.getLogger(TelegramClientStartupService.class);

    private final ChannelAccountRepository channelAccountRepository;
    private final TelegramChannelAccountService channelAccountService;

    public TelegramClientStartupService(
            ChannelAccountRepository channelAccountRepository,
            TelegramChannelAccountService channelAccountService
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.channelAccountService = channelAccountService;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void restoreTelegramClients() {

        List<ChannelAccountEntity> accounts =
                channelAccountRepository
                        .findAllByChannelTypeAndChannelStatus(
                                ChannelType.TELEGRAM,
                                ChannelConnectionStatus.CONNECTED
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

            account.getChannel()
                    .setStatus(ChannelConnectionStatus.ERROR);

            channelAccountRepository.save(account);
        }
    }
}