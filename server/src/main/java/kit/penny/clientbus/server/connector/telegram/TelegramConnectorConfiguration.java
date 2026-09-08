package kit.penny.clientbus.server.connector.telegram;

import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelegramConnectorConfiguration {

    @Bean
    public TelegramContextFactory telegramContextFactory(
            TelegramProperties properties
    ) {
        return new TelegramContextFactory(properties);
    }

    @Bean
    public TelegramClientManager telegramClientManager(
            TelegramContextFactory telegramContextFactory
    ) {
        return new TelegramClientManager(telegramContextFactory);
    }
}
