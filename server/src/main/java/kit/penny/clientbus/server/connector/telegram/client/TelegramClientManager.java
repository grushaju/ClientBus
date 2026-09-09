package kit.penny.clientbus.server.connector.telegram.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramClientManager {

    private final Map<UUID, TelegramClientContext> clients =
            new ConcurrentHashMap<>();

    private final TelegramContextFactory contextFactory;

    public TelegramClientManager(TelegramContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    public TelegramClientContext create(
            UUID channelAccountId,
            String phone
    ) {
        TelegramClientContext existing = clients.get(channelAccountId);

        if (existing != null) {
            throw new IllegalStateException(
                    "Telegram client already exists for channel account: "
                            + channelAccountId
            );
        }

        TelegramClientContext context =
                contextFactory.create(channelAccountId, phone);

        existing = clients.putIfAbsent(channelAccountId, context);

        if (existing != null) {
            context.applicationContext().close();

            throw new IllegalStateException(
                    "Telegram client already exists for channel account: "
                            + channelAccountId
            );
        }

        return context;
    }

    public TelegramClientContext get(UUID channelAccountId) {
        return clients.get(channelAccountId);
    }

    public TelegramClientContext require(UUID channelAccountId) {
        TelegramClientContext context = clients.get(channelAccountId);

        if (context == null) {
            throw new IllegalStateException(
                    "Telegram client not found for channel account: "
                            + channelAccountId
            );
        }

        return context;
    }

    public void stop(UUID channelAccountId) {
        TelegramClientContext context = clients.remove(channelAccountId);

        if (context == null) {
            return;
        }

        if (context.applicationContext().isActive()) {
            context.applicationContext().close();
        }
    }

    public void restart(
            UUID channelAccountId,
            String phone
    ) {
        stop(channelAccountId);
        create(channelAccountId, phone);
    }

    public void closeAll() {
        clients.values().forEach(context -> {
            if (context.applicationContext().isActive()) {
                context.applicationContext().close();
            }
        });

        clients.clear();
    }
}