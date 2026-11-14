package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.IChannelConnectorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 13.3: the server must start and stay fully operational without the Telegram module.
 *
 * <p>Removing the connector/telegram dependency from the build is the real-world case;
 * {@code clientbus.telegram.enabled=false} is the equivalent runtime switch, so this test
 * proves the module registration is genuinely optional: no connector beans, an empty
 * connector registry for TELEGRAM, and the TDLib starter auto-configuration stays excluded
 * (no TelegramUserService, no native library load).
 */
@SpringBootTest
@TestPropertySource(properties = "clientbus.telegram.enabled=false")
class TelegramDisabledStartupIntegrationTest
        extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IChannelConnectorRegistry registry;

    @Autowired
    private Environment environment;

    @Test
    void contextStartsWithoutAnyTelegramBeans() {
        for (String beanName : List.of(
                "telegramChannelConnector",
                "telegramClientManager",
                "telegramContextFactory",
                "telegramClientStartupService",
                "telegramAuthorizationController")) {
            assertFalse(
                    context.containsBean(beanName),
                    "bean must not exist when the Telegram module is disabled: "
                            + beanName);
        }
    }

    @Test
    void registryReportsNoTelegramConnector() {
        assertTrue(registry.findConnector(ChannelType.TELEGRAM).isEmpty());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.getConnector(ChannelType.TELEGRAM));
        assertTrue(error.getMessage()
                .contains("No connector registered for channel type"));
    }

    @Test
    void tdlibStarterAutoConfigurationStaysExcluded() {
        String exclusions =
                environment.getProperty("spring.autoconfigure.exclude", "");
        assertTrue(
                exclusions.contains("kit.penny.tdlib.TdlibAutoConfiguration"),
                "EnvironmentPostProcessor default must keep TDLib starter off: "
                        + exclusions);
        assertFalse(context.containsBean("telegramUserService"));
    }
}
