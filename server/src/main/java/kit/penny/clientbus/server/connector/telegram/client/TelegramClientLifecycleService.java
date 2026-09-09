package kit.penny.clientbus.server.connector.telegram.client;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TelegramClientLifecycleService {

    private final TelegramClientManager clientManager;

    public TelegramClientLifecycleService(
            TelegramClientManager clientManager
    ) {
        this.clientManager = clientManager;
    }

    public TelegramClientContext create(
            UUID channelAccountId,
            String phone
    ) {
        return clientManager.create(
                channelAccountId,
                phone
        );
    }

    public TelegramClientContext get(
            UUID channelAccountId
    ) {
        return clientManager.get(channelAccountId);
    }

    public TelegramClientContext require(
            UUID channelAccountId
    ) {
        return clientManager.require(channelAccountId);
    }

    public void stop(
            UUID channelAccountId
    ) {
        clientManager.stop(channelAccountId);
    }

    public void restart(
            UUID channelAccountId,
            String phone
    ) {
        clientManager.restart(
                channelAccountId,
                phone
        );
    }
}