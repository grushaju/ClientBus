package kit.penny.clientbus.server.connector.telegram;

import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

public record TelegramClientContext(
        UUID channelAccountId,
        ConfigurableApplicationContext applicationContext,
        TelegramClient telegramClient,
        TelegramAuthorizationManager authorizationManager
) {
}