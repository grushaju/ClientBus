package kit.penny.clientbus.connector.telegram.config;

import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 13.3: entry point of the optional Telegram connector module.
 *
 * <p>Registration is automatic while the module jar is on the classpath
 * (META-INF/spring/...AutoConfiguration.imports) and disappears with it, so the
 * server starts and wires an empty connector registry when Telegram is absent.
 * {@code clientbus.telegram.enabled=false} turns the module off without removing
 * the jar (same runtime effect: no TDLib clients are ever constructed).
 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "clientbus.telegram",
        name = "enabled",
        matchIfMissing = true
)
@ComponentScan(
        basePackages = "kit.penny.clientbus.connector.telegram",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = TelegramClientConfiguration.class
        )
)
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConnectorAutoConfiguration {
}
