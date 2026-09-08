package kit.penny.clientbus.server.connector.telegram;

import kit.penny.tdlib.TdlibAutoConfiguration;
import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(TdlibAutoConfiguration.class)
public class TelegramClientConfiguration {
}