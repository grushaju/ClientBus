package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TelegramClientStartupService {

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

        for (ChannelAccountEntity account : accounts) {
            restore(account);
        }
    }

    private void restore(ChannelAccountEntity account) {

        try {
            channelAccountService.create(
                    account.getId()
            );
        } catch (Exception e) {
            // TODO: persist connection error in ChannelEntity.
        }
    }
}