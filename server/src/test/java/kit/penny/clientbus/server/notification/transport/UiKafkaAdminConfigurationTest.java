package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class UiKafkaAdminConfigurationTest {
    static Stream<Duration> brokerTimeouts() {
        return Stream.of(Duration.ofNanos(1), Duration.ofMillis(1), Duration.ofMillis(500),
                Duration.ofMillis(999), Duration.ofMillis(1000), Duration.ofMillis(2000));
    }

    @ParameterizedTest(name = "cleanup Admin accepts broker-timeout={0}")
    @MethodSource("brokerTimeouts")
    void everyAcceptedBrokerTimeoutCanConstructTheCleanupAdmin(Duration brokerTimeout) throws Exception {
        var config = new UiGatewayProperties("admin-config-test", 256, 4, 8, 256,
                Duration.ofSeconds(5), brokerTimeout, Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        var limits = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("clientbus.notifications", NotificationProperties.class);
        var kafka = new KafkaProperties();
        // No broker is needed: creating Admin validates its actual timeout configuration.
        kafka.setBootstrapServers(List.of("127.0.0.1:1"));
        var subscriber = new UiKafkaSubscriber(mock(UiSseGateway.class), config, limits,
                JsonMapper.builder().build(), kafka);
        var propertiesField = UiKafkaSubscriber.class.getDeclaredField("adminProperties");
        propertiesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var adminProperties = (Map<String, Object>) propertiesField.get(subscriber);

        var admin = assertDoesNotThrow(() -> Admin.create(adminProperties),
                "An accepted broker-timeout must not silently disable consumer group cleanup");
        admin.close(Duration.ofSeconds(1));
    }
}
