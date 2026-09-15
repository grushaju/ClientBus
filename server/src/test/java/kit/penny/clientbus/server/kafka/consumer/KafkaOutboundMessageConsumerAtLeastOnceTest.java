package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboundMessageConsumerAtLeastOnceTest {

    private static final UUID MESSAGE_ID =
            UUID.randomUUID();

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.randomUUID();

    private static final String RECIPIENT_EXTERNAL_ID =
            "telegram-chat-123";

    private static final String EXTERNAL_MESSAGE_ID =
            "telegram-message-456";

    @Mock
    private ChannelConnectorRegistry channelConnectorRegistry;

    @Mock
    private OutboundMessageKafkaCommandMapper commandMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private IChannelConnector channelConnector;

    private KafkaOutboundMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaOutboundMessageConsumer(
                channelConnectorRegistry,
                commandMapper,
                messageService
        );

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toRequest(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenReturn(
                new ChannelSendRequest(
                        MESSAGE_ID,
                        CHANNEL_ACCOUNT_ID,
                        RECIPIENT_EXTERNAL_ID,
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                )
        );

        when(
                channelConnector.send(
                        any(ChannelSendRequest.class)
                )
        ).thenReturn(
                new ConnectorSendResult(
                        EXTERNAL_MESSAGE_ID
                )
        );
    }

    @Test
    void sendSucceeds_butMarkSentFails_consumerPropagatesException() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).when(messageService).markSent(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                channelConnector,
                times(1)
        ).send(
                any(ChannelSendRequest.class)
        );

        verify(
                messageService,
                times(1)
        ).markSent(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );
    }

    @Test
    void sameKafkaCommandRetried_afterMarkSentFailure_connectorIsCalledAgain() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).doReturn(null)
                .when(messageService)
                .markSent(
                        MESSAGE_ID,
                        EXTERNAL_MESSAGE_ID
                );

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(
                channelConnector,
                times(2)
        ).send(
                any(ChannelSendRequest.class)
        );

        verify(
                messageService,
                times(2)
        ).markSent(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );

        var inOrder =
                inOrder(
                        channelConnector,
                        messageService
                );

        inOrder.verify(
                channelConnector
        ).send(
                any(ChannelSendRequest.class)
        );

        inOrder.verify(
                messageService
        ).markSent(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );

        inOrder.verify(
                channelConnector
        ).send(
                any(ChannelSendRequest.class)
        );

        inOrder.verify(
                messageService
        ).markSent(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );
    }

    private KafkaEvent<OutboundMessageKafkaCommand>
    createOutboundEvent() {

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        MESSAGE_ID,
                        CHANNEL_ACCOUNT_ID,
                        RECIPIENT_EXTERNAL_ID,
                        MessageType.TEXT,
                        "Hello Telegram",
                        List.of()
                );

        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.OUTBOUND_MESSAGE,
                1,
                Instant.now(),
                UUID.randomUUID(),
                command
        );
    }
}