package kit.penny.clientbus.server.connector.telegram.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramClientManager {

    private static final Logger log =
            LoggerFactory.getLogger(TelegramClientManager.class);

    private final Map<UUID, TelegramClientContext> clients =
            new ConcurrentHashMap<>();

    private final TelegramContextFactory contextFactory;

    public TelegramClientManager(
            TelegramContextFactory contextFactory
    ) {
        this.contextFactory = contextFactory;
    }

    public synchronized TelegramClientContext create(
            UUID channelAccountId,
            String phone
    ) {
        TelegramClientContext existing =
                clients.get(channelAccountId);

        if (existing != null) {
            throw new IllegalStateException(
                    "Telegram client already exists for channel account: "
                            + channelAccountId
            );
        }

        TelegramClientContext context =
                contextFactory.create(
                        channelAccountId,
                        phone
                );

        try {
            clients.put(channelAccountId, context);
            return context;

        } catch (RuntimeException e) {
            closeContext(
                    channelAccountId,
                    context
            );

            throw e;
        }
    }

    public TelegramClientContext get(
            UUID channelAccountId
    ) {
        return clients.get(channelAccountId);
    }

    public TelegramClientContext require(
            UUID channelAccountId
    ) {
        TelegramClientContext context =
                clients.get(channelAccountId);

        if (context == null) {
            throw new IllegalStateException(
                    "Telegram client not found for channel account: "
                            + channelAccountId
            );
        }

        return context;
    }

    public void stop(
            UUID channelAccountId
    ) {
        TelegramClientContext context =
                clients.remove(channelAccountId);

        if (context == null) {
            return;
        }

        closeContext(
                channelAccountId,
                context
        );
    }

    public void restart(
            UUID channelAccountId,
            String phone
    ) {
        stop(channelAccountId);
        create(channelAccountId, phone);
    }

    public void closeAll() {
        try {
            clients.forEach(
                    this::closeContext
            );
        } finally {
            clients.clear();
        }
    }

    public void disconnect(
            UUID channelAccountId
    ) {
        TelegramClientContext context =
                clients.remove(channelAccountId);

        if (context == null) {
            return;
        }

        RuntimeException logoutFailure = null;

        try {
            context.telegramClient().logout();

        } catch (RuntimeException e) {
            logoutFailure = e;

            log.error(
                    "Failed to logout Telegram client: " +
                            "channelAccountId={}",
                    channelAccountId,
                    e
            );

        } finally {
            closeContext(
                    channelAccountId,
                    context
            );
        }

        if (logoutFailure != null) {
            throw logoutFailure;
        }
    }

    private void closeContext(
            UUID channelAccountId,
            TelegramClientContext context
    ) {
        if (context == null
                || context.applicationContext() == null
                || !context.applicationContext().isActive()) {
            return;
        }

        try {
            context.applicationContext().close();

        } catch (RuntimeException e) {
            log.error(
                    "Failed to close Telegram client context: " +
                            "channelAccountId={}",
                    channelAccountId,
                    e
            );
        }
    }
}