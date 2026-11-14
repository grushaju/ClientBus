package kit.penny.clientbus.connector.telegram.config;

import kit.penny.tdlib.TdlibAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(TdlibAutoConfiguration.class)
public class TelegramClientConfiguration {
}