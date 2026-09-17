package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

        consumer =
                new KafkaOutboundMessageConsumer(
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
    void sendSucceeds_butRegisterPendingExternalIdFails_consumerPropagatesException() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        MessageEntity message =
                queuedPendingMessageWithoutExternalId();

        when(
                messageService.getMessageEntityForProcessing(
                        MESSAGE_ID
                )
        ).thenReturn(message);

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).when(messageService).registerPendingExternalId(
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
        ).registerPendingExternalId(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );
    }

    @Test
    void sameKafkaCommandRetried_afterRegisterPendingExternalIdFailure_connectorIsCalledAgain() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        MessageEntity message =
                queuedPendingMessageWithoutExternalId();

        when(
                messageService.getMessageEntityForProcessing(
                        MESSAGE_ID
                )
        ).thenReturn(
                message,
                message
        );

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).doReturn(null)
                .when(messageService)
                .registerPendingExternalId(
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
        ).registerPendingExternalId(
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
        ).registerPendingExternalId(
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
        ).registerPendingExternalId(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );
    }

    private MessageEntity queuedPendingMessageWithoutExternalId() {

        MessageEntity message =
                new MessageEntity();

        message.setId(
                MESSAGE_ID
        );

        message.setDirection(
                kit.penny.clientbus.common.enums.MessageDirection.OUTBOUND
        );

        message.setSenderType(
                MessageSenderType.EMPLOYEE
        );

        message.setType(
                MessageType.TEXT
        );

        message.setContent(
                "Hello Telegram"
        );

        message.setExternalId(
                null
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message.setProcessedAt(
                null
        );

        message.setSentAt(
                null
        );

        message.setDeliveredAt(
                null
        );

        message.setReadAt(
                null
        );

        return message;
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
