package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.connector.telegram.config.TelegramClientConfiguration;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.drinkless.tdlib.TdApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import kit.penny.clientbus.server.service.MessageProcessingService;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TelegramContextFactory {

    private static final String PROPERTY_PREFIX =
            "spring.telegram.client.";

    private static final String AUTHORIZATION_STATE_BEAN_NAME =
            "updateAuthorizationState";

    private static final String INBOUND_MESSAGE_BEAN_NAME =
            "telegramInboundMessageListener";

    private final TelegramProperties globalProperties;
    private final ChannelRepository channelRepository;
    private final ChannelAccountRepository channelAccountRepository;
    private final MessageProcessingService messageProcessingService;

    public TelegramContextFactory(
            TelegramProperties globalProperties,
            ChannelAccountRepository channelAccountRepository,
            ChannelRepository channelRepository,
            MessageProcessingService messageProcessingService
    ) {
        this.globalProperties = globalProperties;
        this.channelAccountRepository = channelAccountRepository;
        this.channelRepository = channelRepository;
        this.messageProcessingService = messageProcessingService;
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

        context.addBeanFactoryPostProcessor(
                updateListenerReplacer(
                        context,
                        channelAccountId
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

    private BeanDefinitionRegistryPostProcessor updateListenerReplacer(
            AnnotationConfigApplicationContext context,
            UUID channelAccountId
    ) {
        return new BeanDefinitionRegistryPostProcessor() {

            @Override
            public void postProcessBeanDefinitionRegistry(
                    BeanDefinitionRegistry registry
            ) {
                if (registry.containsBeanDefinition(
                        AUTHORIZATION_STATE_BEAN_NAME
                )) {
                    registry.removeBeanDefinition(
                            AUTHORIZATION_STATE_BEAN_NAME
                    );
                }

                RootBeanDefinition authorizationDefinition =
                        new RootBeanDefinition(
                                ITdlibUpdateListener.class
                        );

                authorizationDefinition.setInstanceSupplier(
                        () -> createAuthorizationStateListener(
                                context,
                                channelAccountId
                        )
                );

                registry.registerBeanDefinition(
                        AUTHORIZATION_STATE_BEAN_NAME,
                        authorizationDefinition
                );

                RootBeanDefinition inboundMessageDefinition =
                        new RootBeanDefinition(
                                ITdlibUpdateListener.class
                        );

                inboundMessageDefinition.setInstanceSupplier(
                        () -> createInboundMessageListener(
                                context,
                                channelAccountId
                        )
                );

                registry.registerBeanDefinition(
                        INBOUND_MESSAGE_BEAN_NAME,
                        inboundMessageDefinition
                );
            }

            @Override
            public void postProcessBeanFactory(
                    org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory
            ) {
                // Nothing to process.
            }
        };
    }

    private ITdlibUpdateListener<TdApi.UpdateNewMessage>
    createInboundMessageListener(
            AnnotationConfigApplicationContext context,
            UUID channelAccountId
    ) {
        ObjectProvider<TelegramClient> telegramClientProvider =
                context.getBeanProvider(TelegramClient.class);

        return new TelegramInboundMessageListener(
                channelAccountId,
                telegramClientProvider,
                messageProcessingService
        );
    }

    private ITdlibUpdateListener<TdApi.UpdateAuthorizationState> createAuthorizationStateListener(
            AnnotationConfigApplicationContext context,
            UUID channelAccountId
    ) {
        TelegramProperties properties =
                context.getBean(TelegramProperties.class);

        TelegramAuthorizationManager authorizationManager =
                context.getBean(TelegramAuthorizationManager.class);

        ObjectProvider<TelegramClient> telegramClientProvider =
                context.getBeanProvider(TelegramClient.class);

        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Telegram channel account not found: "
                                                + channelAccountId
                                )
                        );

        UUID channelId = account.getChannel().getId();

        return new TelegramAuthorizationStateListener(
                channelId,
                channelRepository,
                channelAccountRepository,
                properties,
                authorizationManager,
                telegramClientProvider
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
                accountDirectory.resolve("database").toString()
        );

        properties.put(
                PROPERTY_PREFIX + "files-directory",
                accountDirectory.resolve("files").toString()
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