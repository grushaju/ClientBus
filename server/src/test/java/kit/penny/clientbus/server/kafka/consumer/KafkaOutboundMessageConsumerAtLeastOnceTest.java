package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    }

    @Test
    void sendSucceeds_registerExternalIdFails_consumerPropagatesException() {

        stubSuccessfulConnectorPath();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).when(messageService)
                .registerExternalId(
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
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verify(
                channelConnector
        ).handle(
                any(SendMessageCommand.class)
        );

        verify(
                messageService
        ).registerExternalId(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );

        /*
         * The external platform may already have accepted
         * the message, therefore the delivery claim must NOT
         * be released.
         */
        verify(
                messageService,
                never()
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );
    }

    @Test
    void sameKafkaCommandRetried_afterRegisterExternalIdFailure_connectorIsNotCalledAgain() {

        stubSuccessfulConnectorPath();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        /*
         * First delivery acquires the claim.
         * Second Kafka delivery cannot acquire it because
         * the first attempt remains PROCESSING + PENDING.
         */
        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(
                true,
                false
        );

        doThrow(
                new IllegalStateException(
                        "Simulated failure after external send"
                )
        ).when(messageService)
                .registerExternalId(
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
                messageService,
                times(2)
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verify(
                channelConnector,
                times(1)
        ).handle(
                any(SendMessageCommand.class)
        );

        verify(
                messageService,
                times(1)
        ).registerExternalId(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );

        verify(
                messageService,
                never()
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );
    }

    @Test
    void deliveryClaimNotAcquired_connectorIsNotCalled() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(false);

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verifyNoInteractions(
                channelConnectorRegistry,
                commandMapper,
                channelConnector
        );

        verify(
                messageService,
                never()
        ).registerExternalId(
                any(),
                anyString()
        );

        verify(
                messageService,
                never()
        ).releaseOutboundDeliveryClaim(
                any()
        );
    }

    @Test
    void connectorResolutionFails_claimIsReleased() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        RuntimeException exception =
                new RuntimeException(
                        "Connector unavailable"
                );

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenThrow(exception);

        assertThrows(
                RuntimeException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verify(
                messageService
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );

        verifyNoInteractions(
                commandMapper,
                channelConnector
        );
    }

    @Test
    void commandMappingFails_claimIsReleased() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        RuntimeException exception =
                new RuntimeException(
                        "Mapping failed"
                );

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toCommand(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenThrow(exception);

        assertThrows(
                RuntimeException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verify(
                messageService
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );

        verifyNoInteractions(
                channelConnector
        );
    }

    @Test
    void connectorThrows_claimIsReleased() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        RuntimeException exception =
                new RuntimeException(
                        "Platform unavailable"
                );

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toCommand(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenReturn(
                createSendMessageCommand()
        );

        when(
                channelConnector.handle(
                        any(SendMessageCommand.class)
                )
        ).thenThrow(exception);

        assertThrows(
                RuntimeException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        verify(
                channelConnector
        ).handle(
                any(SendMessageCommand.class)
        );

        verify(
                messageService
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );

        verify(
                messageService,
                never()
        ).registerExternalId(
                any(),
                anyString()
        );
    }

    @Test
    void connectorReturnsNullResult_claimIsNotReleased() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toCommand(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenReturn(
                createSendMessageCommand()
        );

        when(
                channelConnector.handle(
                        any(SendMessageCommand.class)
                )
        ).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                channelConnector
        ).handle(
                any(SendMessageCommand.class)
        );

        verify(
                messageService,
                never()
        ).registerExternalId(
                any(),
                anyString()
        );

        verify(
                messageService,
                never()
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );
    }

    @Test
    void connectorReturnsBlankExternalId_claimIsNotReleased() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toCommand(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenReturn(
                createSendMessageCommand()
        );

        when(
                channelConnector.handle(
                        any(SendMessageCommand.class)
                )
        ).thenReturn(
                new ConnectorSendResult("   ")
        );

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        );

        verify(
                channelConnector
        ).handle(
                any(SendMessageCommand.class)
        );

        verify(
                messageService,
                never()
        ).registerExternalId(
                any(),
                anyString()
        );

        verify(
                messageService,
                never()
        ).releaseOutboundDeliveryClaim(
                MESSAGE_ID
        );
    }

    @Test
    void successfulSend_callsClaimConnectorAndRegisterInOrder() {

        stubSuccessfulConnectorPath();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                createOutboundEvent();

        when(
                messageService.claimOutboundDelivery(
                        MESSAGE_ID
                )
        ).thenReturn(true);

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        InOrder inOrder =
                inOrder(
                        messageService,
                        channelConnector
                );

        inOrder.verify(
                messageService
        ).claimOutboundDelivery(
                MESSAGE_ID
        );

        inOrder.verify(
                channelConnector
        ).handle(
                any(SendMessageCommand.class)
        );

        inOrder.verify(
                messageService
        ).registerExternalId(
                MESSAGE_ID,
                EXTERNAL_MESSAGE_ID
        );
    }

    private void stubSuccessfulConnectorPath() {

        when(
                channelConnectorRegistry.getConnector(
                        ChannelType.TELEGRAM
                )
        ).thenReturn(channelConnector);

        when(
                commandMapper.toCommand(
                        any(OutboundMessageKafkaCommand.class)
                )
        ).thenReturn(
                createSendMessageCommand()
        );

        when(
                channelConnector.handle(
                        any(SendMessageCommand.class)
                )
        ).thenReturn(
                new ConnectorSendResult(
                        EXTERNAL_MESSAGE_ID
                )
        );
    }

    private SendMessageCommand createSendMessageCommand() {

        return new SendMessageCommand(
                MESSAGE_ID,
                CHANNEL_ACCOUNT_ID,
                RECIPIENT_EXTERNAL_ID,
                MessageType.TEXT,
                "Hello Telegram",
                List.of()
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