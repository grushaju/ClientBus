package kit.penny.clientbus.connector.telegram.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 13.3: preserves the pre-13.3 runtime shape of the server.
 *
 * <p>Before the module split ServerApplication explicitly excluded the TDLib
 * starter's auto-configuration (clients are built per channel account by
 * TelegramContextFactory, not by the starter). The starter arrives with this
 * module now, so the exclusion travels with it as a default property.
 *
 * <p>The merged value is added FIRST: Boot binds {@code spring.autoconfigure.exclude}
 * from the highest-priority source that defines the key and does not merge the
 * list across sources, so an addLast default would be silently shadowed by any
 * deployment-time exclusion. Deployment values are read first and merged into
 * the same value, so nothing is lost in either direction (review 13.x, 14.11.2026).
 */
public class TelegramTdlibExclusionPostProcessor
        implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE =
            "connectorTelegramDefaults";

    private static final String EXCLUDE_PROPERTY =
            "spring.autoconfigure.exclude";

    private static final String TDLIB_AUTO_CONFIGURATION =
            "kit.penny.tdlib.TdlibAutoConfiguration";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        String existing =
                environment.getProperty(EXCLUDE_PROPERTY);

        if (existing != null
                && existing.contains(TDLIB_AUTO_CONFIGURATION)) {
            return;
        }

        String value = existing == null || existing.isBlank()
                ? TDLIB_AUTO_CONFIGURATION
                : TDLIB_AUTO_CONFIGURATION + "," + existing;

        Map<String, Object> defaults = new HashMap<>();
        defaults.put(EXCLUDE_PROPERTY, value);

        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }
}
