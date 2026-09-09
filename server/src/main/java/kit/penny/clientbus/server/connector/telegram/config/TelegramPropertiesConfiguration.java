package kit.penny.clientbus.server.connector.telegram.config;

import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramPropertiesConfiguration {
}