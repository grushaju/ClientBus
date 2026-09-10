package kit.penny.clientbus.server.connector.telegram.account;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TelegramChannelAccountService {

    private final ChannelAccountRepository channelAccountRepository;
    private final ChannelRepository channelRepository;
    private final TelegramClientLifecycleService lifecycleService;

    public TelegramChannelAccountService(
            ChannelAccountRepository channelAccountRepository,
            ChannelRepository channelRepository,
            TelegramClientLifecycleService lifecycleService
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.lifecycleService = lifecycleService;
        this.channelRepository = channelRepository;
    }

    public TelegramClientContext create(UUID channelAccountId) {

        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Telegram channel account not found: "
                                                + channelAccountId
                                )
                        );

        if (account.getPhone() == null
                || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Telegram channel account has no phone: "
                            + channelAccountId
            );
        }

        ChannelEntity channel = account.getChannel();

        channel.setStatus(
                ChannelConnectionStatus.CONNECTING
        );

        channelRepository.save(channel);

        try {
            return lifecycleService.create(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {
            channel.setStatus(
                    ChannelConnectionStatus.ERROR
            );

            channelRepository.save(channel);

            throw e;
        }
    }

    public TelegramClientContext get(UUID channelAccountId) {
        return lifecycleService.get(channelAccountId);
    }

    public TelegramClientContext require(UUID channelAccountId) {
        return lifecycleService.require(channelAccountId);
    }

    public void stop(UUID channelAccountId) {
        lifecycleService.stop(channelAccountId);

        channelAccountRepository.findById(channelAccountId)
                .ifPresent(account -> {
                    ChannelEntity channel = account.getChannel();

                    channel.setStatus(
                            ChannelConnectionStatus.DISCONNECTED
                    );

                    channelRepository.save(channel);
                });
    }

    public void restart(UUID channelAccountId) {
        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Telegram channel account not found: "
                                                + channelAccountId
                                )
                        );

        if (account.getPhone() == null
                || account.getPhone().isBlank()) {
            throw new IllegalStateException(
                    "Telegram channel account has no phone: "
                            + channelAccountId
            );
        }

        ChannelEntity channel = account.getChannel();

        channel.setStatus(
                ChannelConnectionStatus.CONNECTING
        );

        channelRepository.save(channel);

        try {
            lifecycleService.restart(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {
            channel.setStatus(
                    ChannelConnectionStatus.ERROR
            );

            channelRepository.save(channel);

            throw e;
        }
    }
}