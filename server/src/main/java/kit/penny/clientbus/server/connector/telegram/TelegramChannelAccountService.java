package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TelegramChannelAccountService {

    private final ChannelAccountRepository channelAccountRepository;
    private final TelegramClientLifecycleService lifecycleService;

    public TelegramChannelAccountService(
            ChannelAccountRepository channelAccountRepository,
            TelegramClientLifecycleService lifecycleService
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.lifecycleService = lifecycleService;
    }

    public TelegramClientContext create(UUID channelAccountId) {
        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Channel account not found: " + channelAccountId
                        ));

        if (account.getPhone() == null || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Phone is not configured for Telegram channel account: "
                            + channelAccountId
            );
        }

        return lifecycleService.create(
                account.getId(),
                account.getPhone()
        );
    }

    public TelegramClientContext get(UUID channelAccountId) {
        return lifecycleService.get(channelAccountId);
    }

    public TelegramClientContext require(UUID channelAccountId) {
        return lifecycleService.require(channelAccountId);
    }

    public void stop(UUID channelAccountId) {
        lifecycleService.stop(channelAccountId);
    }

    public void restart(UUID channelAccountId) {
        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Channel account not found: " + channelAccountId
                        ));

        if (account.getPhone() == null || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Phone is not configured for Telegram channel account: "
                            + channelAccountId
            );
        }

        lifecycleService.restart(
                account.getId(),
                account.getPhone()
        );
    }
}