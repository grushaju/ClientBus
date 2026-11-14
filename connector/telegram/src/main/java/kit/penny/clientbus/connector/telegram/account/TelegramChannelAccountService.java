package kit.penny.clientbus.connector.telegram.account;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.IChannelAccountLifecycle;
import kit.penny.clientbus.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.connector.telegram.storage.TelegramDataStorage;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import org.springframework.stereotype.Service;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;

import java.util.UUID;

@Service
public class TelegramChannelAccountService
        implements IChannelAccountLifecycle {

    private final HostChannelAccounts hostChannelAccounts;
    private final HostChannelStatus hostChannelStatus;
    private final TelegramClientLifecycleService lifecycleService;
    private final TelegramDataStorage dataStorage;

    public TelegramChannelAccountService(
            HostChannelAccounts hostChannelAccounts, HostChannelStatus hostChannelStatus,
            TelegramClientLifecycleService lifecycleService,
            TelegramDataStorage dataStorage
    ) {
        this.hostChannelAccounts = hostChannelAccounts;
        this.lifecycleService = lifecycleService;
        this.hostChannelStatus = hostChannelStatus;
        this.dataStorage = dataStorage;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.TELEGRAM;
    }

    @Override
    public void disconnect(UUID channelAccountId) {

        hostChannelStatus.expectDisconnect(channelAccountId, ChannelConnectionStatus.DISCONNECTED);
        lifecycleService.disconnect(channelAccountId);
        dataStorage.delete(channelAccountId);
    }

    public TelegramClientContext create(UUID channelAccountId) {

        HostChannelAccountSnapshot account = hostChannelAccounts.require(channelAccountId);

        if (account.phone() == null
                || account.phone().isBlank()) {
            throw new IllegalStateException(
                    "Telegram channel account has no phone: "
                            + channelAccountId
            );
        }

        hostChannelStatus.beginConnecting(channelAccountId);

        try {
            return lifecycleService.create(
                    account.id(),
                    account.phone()
            );
        } catch (RuntimeException e) {
            hostChannelStatus.transition(channelAccountId, ChannelConnectionStatus.ERROR, "CONNECTOR_START_FAILED");

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

        hostChannelStatus.expectDisconnect(channelAccountId, ChannelConnectionStatus.DISABLED);
        lifecycleService.stop(channelAccountId);
    }

    public void enable(UUID channelAccountId) {
        create(channelAccountId);
    }


    public void restart(UUID channelAccountId) {
        HostChannelAccountSnapshot account = hostChannelAccounts.require(channelAccountId);

        if (account.phone() == null
                || account.phone().isBlank()) {
            throw new IllegalStateException(
                    "Telegram channel account has no phone: "
                            + channelAccountId
            );
        }

        hostChannelStatus.beginConnecting(channelAccountId);

        try {
            lifecycleService.restart(
                    account.id(),
                    account.phone()
            );
        } catch (RuntimeException e) {
            hostChannelStatus.transition(channelAccountId, ChannelConnectionStatus.ERROR, "CONNECTOR_START_FAILED");

            throw e;
        }
    }
}