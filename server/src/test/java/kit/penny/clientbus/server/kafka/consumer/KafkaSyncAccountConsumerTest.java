
package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncAccountKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncAccountCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaSyncAccountConsumerTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ChannelConnectorRegistry connectorRegistry;

    @Mock
    private IChannelConnector connector;

    private KafkaSyncAccountConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaSyncAccountConsumer(connectorRegistry);
    }

    @Test
    void shouldRouteSyncAccountCommandToConnector() {
        when(connectorRegistry.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);

        consumer.consume(
                event(KafkaEventType.SYNC_ACCOUNT,
                        new SyncAccountKafkaCommand(ACCOUNT_ID)),
                KafkaTopicNames.accountCommand(ChannelType.TELEGRAM)
        );

        verify(connectorRegistry).getConnector(ChannelType.TELEGRAM);
        verify(connector).handle(new SyncAccountCommand(ACCOUNT_ID));
        verifyNoMoreInteractions(connectorRegistry, connector);
    }

    @Test
    void shouldRejectNullEvent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        null,
                        KafkaTopicNames.accountCommand(ChannelType.TELEGRAM)
                )
        );

        verifyNoInteractions(connectorRegistry, connector);
    }

    @Test
    void shouldRejectWrongEventType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event(
                                KafkaEventType.PLATFORM_MESSAGE,
                                new SyncAccountKafkaCommand(ACCOUNT_ID)
                        ),
                        KafkaTopicNames.accountCommand(ChannelType.TELEGRAM)
                )
        );

        verifyNoInteractions(connectorRegistry, connector);
    }

    @Test
    void shouldRejectNullPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event(KafkaEventType.SYNC_ACCOUNT, null),
                        KafkaTopicNames.accountCommand(ChannelType.TELEGRAM)
                )
        );

        verifyNoInteractions(connectorRegistry, connector);
    }

    @Test
    void shouldRejectNullChannelAccountId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        event(
                                KafkaEventType.SYNC_ACCOUNT,
                                new SyncAccountKafkaCommand(null)
                        ),
                        KafkaTopicNames.accountCommand(ChannelType.TELEGRAM)
                )
        );

        verifyNoInteractions(connectorRegistry, connector);
    }

    private static KafkaEvent<SyncAccountKafkaCommand> event(
            KafkaEventType eventType,
            SyncAccountKafkaCommand payload
    ) {
        return new KafkaEvent<>(
                UUID.randomUUID(),
                eventType,
                1,
                Instant.now(),
                null,
                payload
        );
    }
}
