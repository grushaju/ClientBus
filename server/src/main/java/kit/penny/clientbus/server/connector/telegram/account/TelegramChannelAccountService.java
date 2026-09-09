package kit.penny.clientbus.server.connector.telegram.account;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public TelegramClientContext create(UUID channelAccountId) {

        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Channel account not found: " + channelAccountId));

        if (account.getPhone() == null || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Phone is not configured for Telegram channel account: "
                            + channelAccountId
            );
        }

        account.getChannel()
                .setStatus(ChannelConnectionStatus.CONNECTING);

        channelAccountRepository.save(account);

        try {
            return lifecycleService.create(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {

            account.getChannel()
                    .setStatus(ChannelConnectionStatus.ERROR);

            channelAccountRepository.save(account);

            throw e;
        }
    }

    public TelegramClientContext get(UUID channelAccountId) {
        return lifecycleService.get(channelAccountId);
    }

    public TelegramClientContext require(UUID channelAccountId) {
        return lifecycleService.require(channelAccountId);
    }

    @Transactional
    public void stop(UUID channelAccountId) {

        lifecycleService.stop(channelAccountId);

        channelAccountRepository.findById(channelAccountId)
                .ifPresent(account -> {
                    account.getChannel()
                            .setStatus(ChannelConnectionStatus.DISCONNECTED);

                    channelAccountRepository.save(account);
                });
    }

    @Transactional
    public void restart(UUID channelAccountId) {

        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Channel account not found: " + channelAccountId));

        if (account.getPhone() == null || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Phone is not configured for Telegram channel account: "
                            + channelAccountId
            );
        }

        account.getChannel()
                .setStatus(ChannelConnectionStatus.CONNECTING);

        channelAccountRepository.save(account);

        try {
            lifecycleService.restart(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {

            account.getChannel()
                    .setStatus(ChannelConnectionStatus.ERROR);

            channelAccountRepository.save(account);

            throw e;
        }
    }
}