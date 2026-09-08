package kit.penny.clientbus.server.connector.telegram;

import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TelegramContextFactory {

    private static final String PROPERTY_PREFIX =
            "spring.telegram.client.";

    private final TelegramProperties globalProperties;

    public TelegramContextFactory(TelegramProperties globalProperties) {
        this.globalProperties = globalProperties;
    }

    public TelegramClientContext create(
            UUID channelAccountId,
            String phone
    ) {
        Map<String, Object> properties =
                createAccountProperties(channelAccountId, phone);

        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "telegramAccountProperties",
                                properties
                        )
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

    private Map<String, Object> createAccountProperties(
            UUID channelAccountId,
            String phone
    ) {
        Path accountDirectory = Path.of(
                globalProperties.databaseDirectory(),
                channelAccountId.toString()
        );

        Map<String, Object> properties = new HashMap<>();

        properties.put(
                PROPERTY_PREFIX + "use-test-dc",
                globalProperties.useTestDc()
        );

        properties.put(
                PROPERTY_PREFIX + "database-directory",
                accountDirectory
                        .resolve("database")
                        .toString()
        );

        properties.put(
                PROPERTY_PREFIX + "files-directory",
                accountDirectory
                        .resolve("files")
                        .toString()
        );

        properties.put(
                PROPERTY_PREFIX + "database-encryption-key",
                globalProperties.databaseEncryptionKey()
        );

        properties.put(
                PROPERTY_PREFIX + "use-file-database",
                globalProperties.useFileDatabase()
        );

        properties.put(
                PROPERTY_PREFIX + "use-chat-info-database",
                globalProperties.useChatInfoDatabase()
        );

        properties.put(
                PROPERTY_PREFIX + "use-message-database",
                globalProperties.useMessageDatabase()
        );

        properties.put(
                PROPERTY_PREFIX + "use-secret-chats",
                globalProperties.useSecretChats()
        );

        properties.put(
                PROPERTY_PREFIX + "api-id",
                globalProperties.apiId()
        );

        properties.put(
                PROPERTY_PREFIX + "api-hash",
                globalProperties.apiHash()
        );

        properties.put(
                PROPERTY_PREFIX + "phone",
                phone
        );

        properties.put(
                PROPERTY_PREFIX + "system-language-code",
                globalProperties.systemLanguageCode()
        );

        properties.put(
                PROPERTY_PREFIX + "device-model",
                globalProperties.deviceModel()
        );

        properties.put(
                PROPERTY_PREFIX + "system-version",
                globalProperties.systemVersion()
        );

        properties.put(
                PROPERTY_PREFIX + "application-version",
                globalProperties.applicationVersion()
        );

        properties.put(
                PROPERTY_PREFIX + "log-verbosity-level",
                globalProperties.logVerbosityLevel()
        );

        addProxyProperties(
                properties,
                globalProperties.proxy()
        );

        return properties;
    }

    private void addProxyProperties(
            Map<String, Object> properties,
            TelegramProperties.Proxy proxy
    ) {
        if (proxy == null) {
            return;
        }

        properties.put(
                PROPERTY_PREFIX + "proxy.server",
                proxy.server()
        );

        properties.put(
                PROPERTY_PREFIX + "proxy.port",
                proxy.port()
        );

        if (proxy.http() != null) {
            properties.put(
                    PROPERTY_PREFIX + "proxy.http.username",
                    proxy.http().username()
            );

            properties.put(
                    PROPERTY_PREFIX + "proxy.http.password",
                    proxy.http().password()
            );

            properties.put(
                    PROPERTY_PREFIX + "proxy.http.http-only",
                    proxy.http().httpOnly()
            );
        }

        if (proxy.socks5() != null) {
            properties.put(
                    PROPERTY_PREFIX + "proxy.socks5.username",
                    proxy.socks5().username()
            );

            properties.put(
                    PROPERTY_PREFIX + "proxy.socks5.password",
                    proxy.socks5().password()
            );
        }

        if (proxy.mtproto() != null) {
            properties.put(
                    PROPERTY_PREFIX + "proxy.mtproto.secret",
                    proxy.mtproto().secret()
            );
        }
    }
}