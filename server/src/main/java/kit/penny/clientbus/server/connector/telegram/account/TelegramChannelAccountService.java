package kit.penny.clientbus.server.connector.telegram.account;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.IChannelAccountLifecycle;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.connector.telegram.storage.TelegramDataStorage;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;
import kit.penny.clientbus.server.service.ChannelStatusService;

import java.util.UUID;

@Service
public class TelegramChannelAccountService
        implements IChannelAccountLifecycle {

    private final ChannelAccountRepository channelAccountRepository;
    private final ChannelStatusService channelStatus;
    private final TelegramClientLifecycleService lifecycleService;
    private final TelegramDataStorage dataStorage;

    public TelegramChannelAccountService(
            ChannelAccountRepository channelAccountRepository,
            ChannelStatusService channelStatus,
            TelegramClientLifecycleService lifecycleService,
            TelegramDataStorage dataStorage
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.lifecycleService = lifecycleService;
        this.channelStatus = channelStatus;
        this.dataStorage = dataStorage;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.TELEGRAM;
    }

    @Override
    public void disconnect(UUID channelAccountId) {

        channelStatus.expectDisconnect(channelAccountId, ChannelConnectionStatus.DISCONNECTED);
        lifecycleService.disconnect(channelAccountId);
        dataStorage.delete(channelAccountId);
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

        channelStatus.beginConnecting(channelAccountId);

        try {
            return lifecycleService.create(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {
            channelStatus.transition(channelAccountId, ChannelConnectionStatus.ERROR, "CONNECTOR_START_FAILED");

            throw e;
        }
    }

    public TelegramClientContext get(UUID channelAccountId) {
        return lifecycleService.get(channelAccountId);
    }

    public TelegramClientContext require(UUID channelAccountId) {
        return lifecycleService.require(channelAccountId);
    }

    public void disable(UUID channelAccountId) {

        channelStatus.expectDisconnect(channelAccountId, ChannelConnectionStatus.DISABLED);
        lifecycleService.stop(channelAccountId);
    }

    public void enable(UUID channelAccountId) {
        create(channelAccountId);
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

        channelStatus.beginConnecting(channelAccountId);

        try {
            lifecycleService.restart(
                    account.getId(),
                    account.getPhone()
            );
        } catch (RuntimeException e) {
            channelStatus.transition(channelAccountId, ChannelConnectionStatus.ERROR, "CONNECTOR_START_FAILED");

            throw e;
        }
    }
}