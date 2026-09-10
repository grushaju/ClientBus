package kit.penny.clientbus.server.connector.telegram.config;

import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramContextFactory;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class TelegramConnectorConfiguration {

    @Bean
    public TelegramContextFactory telegramContextFactory(
            TelegramProperties properties,
            ChannelAccountRepository channelAccountRepository,
            ChannelRepository channelRepository
    ) {
        return new TelegramContextFactory(
                properties,
                channelAccountRepository,
                channelRepository
        );
    }

    @Bean
    public TelegramClientManager telegramClientManager(
            TelegramContextFactory contextFactory
    ) {
        return new TelegramClientManager(contextFactory);
    }
}