package kit.penny.clientbus.server.integration;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

// Close Kafka clients while the shared broker is still running, rather than
// racing cached Spring contexts against container cleanup at JVM shutdown.
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("clientbus")
                    .withUsername("clientbus")
                    .withPassword("clientbus")
                    .withExposedPorts(5432)
                    .withCreateContainerCmdModifier(cmd ->
                            cmd.withName("clientbus-postgres-test")
                                    .getHostConfig()
                                    .withPortBindings(
                                            com.github.dockerjava.api.model.PortBinding.parse(
                                                    "15432:5432"
                                            )
                                    )
                    );

    protected static final KafkaContainer kafka =
            new KafkaContainer("apache/kafka-native:3.8.0")
                    .withCreateContainerCmdModifier(cmd ->
                            cmd.withName("clientbus-kafka-test")
                                    // Docker must retain the advertised endpoint across stop/start.
                                    .getHostConfig().withPortBindings(com.github.dockerjava.api.model.PortBinding.parse(
                                            availableKafkaPort() + ":9092"))
                    )
                    .withNetwork(Network.SHARED)
                    .withListener("kafka:39092");

    private static int availableKafkaPort() {
        try (var socket=new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    static {
        postgres.start();
        kafka.start();
    }

    protected static String getKafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );

        registry.add(
                "spring.datasource.driver-class-name",
                postgres::getDriverClassName
        );

        registry.add(
                "spring.datasource.hikari.schema",
                () -> "dbo"
        );

        registry.add(
                "spring.kafka.bootstrap-servers",
                kafka::getBootstrapServers
        );
    }
}
