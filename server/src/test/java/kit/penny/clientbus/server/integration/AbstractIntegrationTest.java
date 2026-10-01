package kit.penny.clientbus.server.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

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
                    );

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