package kit.penny.clientbus.server.connector.telegram;

import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.UUID;

public class TelegramContextFactory {

    private final TelegramProperties globalProperties;

    public TelegramContextFactory(TelegramProperties globalProperties) {
        this.globalProperties = globalProperties;
    }

    public TelegramClientContext create(
            UUID channelAccountId,
            String phone
    ) {
        TelegramProperties accountProperties =
                createAccountProperties(channelAccountId, phone);

        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        context.registerBean(
                TelegramProperties.class,
                () -> accountProperties
        );

        context.register(TelegramClientConfiguration.class);
        context.refresh();

        return new TelegramClientContext(
                channelAccountId,
                context,
                context.getBean(TelegramClient.class),
                context.getBean(TelegramAuthorizationManager.class)
        );
    }

    private TelegramProperties createAccountProperties(
            UUID channelAccountId,
            String phone
    ) {
        Path accountDirectory = Path.of(
                globalProperties.databaseDirectory(),
                channelAccountId.toString()
        );

        return new TelegramProperties(
                globalProperties.useTestDc(),
                accountDirectory
                        .resolve("database")
                        .toString(),
                accountDirectory
                        .resolve("files")
                        .toString(),
                globalProperties.databaseEncryptionKey(),
                globalProperties.useFileDatabase(),
                globalProperties.useChatInfoDatabase(),
                globalProperties.useMessageDatabase(),
                globalProperties.useSecretChats(),
                globalProperties.apiId(),
                globalProperties.apiHash(),
                phone,
                globalProperties.systemLanguageCode(),
                globalProperties.deviceModel(),
                globalProperties.systemVersion(),
                globalProperties.applicationVersion(),
                globalProperties.logVerbosityLevel(),
                globalProperties.proxy()
        );
    }
}