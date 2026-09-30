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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaOutboundMessageConsumerTest {

    private ChannelConnectorRegistry channelConnectorRegistry;
    private OutboundMessageKafkaCommandMapper commandMapper;
    private MessageService messageService;
    private IChannelConnector connector;

    private KafkaOutboundMessageConsumer consumer;

    @BeforeEach
    void setUp() {

        channelConnectorRegistry =
                mock(ChannelConnectorRegistry.class);

        commandMapper =
                mock(OutboundMessageKafkaCommandMapper.class);

        messageService =
                mock(MessageService.class);

        connector =
                mock(IChannelConnector.class);

        consumer =
                new KafkaOutboundMessageConsumer(
                        channelConnectorRegistry,
                        commandMapper,
                        messageService
                );
    }

    @Test
    void shouldClaimSendAndRegisterExternalId() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(
                        kafkaCommand.messageId(),
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.type(),
                        kafkaCommand.content(),
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenReturn(command);

        when(connector.handle(command))
                .thenReturn(
                        new ConnectorSendResult(
                                "telegram-message-123"
                        )
                );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(messageService)
                .claimOutboundDelivery(
                        kafkaCommand.messageId()
                );

        verify(channelConnectorRegistry)
                .getConnector(
                        ChannelType.TELEGRAM
                );

        verify(commandMapper)
                .toCommand(
                        kafkaCommand
                );

        verify(connector)
                .handle(command);

        verify(messageService)
                .registerExternalId(
                        kafkaCommand.messageId(),
                        "telegram-message-123"
                );

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(
                        any()
                );
    }

    @Test
    void shouldNotCallConnectorWhenDeliveryClaimWasNotAcquired() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(false);

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(messageService)
                .claimOutboundDelivery(
                        kafkaCommand.messageId()
                );

        verifyNoInteractions(
                channelConnectorRegistry,
                commandMapper,
                connector
        );

        verify(
                messageService,
                never()
        )
                .registerExternalId(
                        any(),
                        anyString()
                );

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(
                        any()
                );
    }

    @Test
    void shouldReleaseClaimWhenConnectorResolutionFails() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Connector unavailable"
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenThrow(exception);

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isSameAs(exception);

        verify(messageService)
                .claimOutboundDelivery(
                        kafkaCommand.messageId()
                );

        verify(messageService)
                .releaseOutboundDeliveryClaim(
                        kafkaCommand.messageId()
                );

        verifyNoInteractions(
                commandMapper,
                connector
        );
    }

    @Test
    void shouldReleaseClaimWhenCommandMappingFails() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Mapping failed"
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenThrow(exception);

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isSameAs(exception);

        verify(messageService)
                .claimOutboundDelivery(
                        kafkaCommand.messageId()
                );

        verify(messageService)
                .releaseOutboundDeliveryClaim(
                        kafkaCommand.messageId()
                );

        verifyNoInteractions(connector);
    }

    @Test
    void shouldReleaseClaimWhenConnectorThrows() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(
                        kafkaCommand.messageId(),
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.type(),
                        kafkaCommand.content(),
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Platform unavailable"
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenReturn(command);

        when(connector.handle(command))
                .thenThrow(exception);

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isSameAs(exception);

        verify(messageService)
                .claimOutboundDelivery(
                        kafkaCommand.messageId()
                );

        verify(connector)
                .handle(command);

        verify(messageService)
                .releaseOutboundDeliveryClaim(
                        kafkaCommand.messageId()
                );

        verify(
                messageService,
                never()
        )
                .registerExternalId(
                        any(),
                        anyString()
                );
    }

    @Test
    void shouldNotReleaseClaimWhenExternalIdRegistrationFails() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(
                        kafkaCommand.messageId(),
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.type(),
                        kafkaCommand.content(),
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Database unavailable"
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenReturn(command);

        when(connector.handle(command))
                .thenReturn(
                        new ConnectorSendResult(
                                "telegram-message-456"
                        )
                );

        doThrow(exception)
                .when(messageService)
                .registerExternalId(
                        kafkaCommand.messageId(),
                        "telegram-message-456"
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isSameAs(exception);

        verify(connector)
                .handle(command);

        verify(messageService)
                .registerExternalId(
                        kafkaCommand.messageId(),
                        "telegram-message-456"
                );

        /*
         * External platform may already have accepted the message.
         *
         * Releasing the claim here would allow Kafka redelivery
         * to send the same message again.
         */
        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(
                        any()
                );
    }

    @Test
    void shouldFailWhenConnectorReturnsNull() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(
                        kafkaCommand.messageId(),
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.type(),
                        kafkaCommand.content(),
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenReturn(command);

        when(connector.handle(command))
                .thenReturn(null);

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Connector returned null send result: messageId="
                                + kafkaCommand.messageId()
                );

        verify(connector)
                .handle(command);

        verify(
                messageService,
                never()
        )
                .registerExternalId(
                        any(),
                        anyString()
                );

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(
                        any()
                );
    }

    @Test
    void shouldFailWhenConnectorReturnsBlankExternalId() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(
                        kafkaCommand.messageId(),
                        kafkaCommand.channelAccountId(),
                        kafkaCommand.recipientExternalId(),
                        kafkaCommand.type(),
                        kafkaCommand.content(),
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(
                kafkaCommand.messageId()
        ))
                .thenReturn(true);

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        ))
                .thenReturn(connector);

        when(commandMapper.toCommand(
                kafkaCommand
        ))
                .thenReturn(command);

        when(connector.handle(command))
                .thenReturn(
                        new ConnectorSendResult("   ")
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Connector returned blank externalId: messageId="
                                + kafkaCommand.messageId()
                );

        verify(connector)
                .handle(command);

        verify(
                messageService,
                never()
        )
                .registerExternalId(
                        any(),
                        anyString()
                );

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(
                        any()
                );
    }

    @Test
    void shouldRejectInvalidEventTypeBeforeClaim() {

        OutboundMessageKafkaCommand command =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        command
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Unsupported Kafka event type: "
                                + KafkaEventType.PLATFORM_MESSAGE
                );

        verifyNoInteractions(
                messageService,
                channelConnectorRegistry,
                commandMapper,
                connector
        );
    }

    @Test
    void shouldRejectInvalidTopicBeforeClaim() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand()
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.inbound"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verifyNoInteractions(
                messageService,
                channelConnectorRegistry,
                commandMapper,
                connector
        );
    }

    private static KafkaEvent<OutboundMessageKafkaCommand> outboundEvent(
            OutboundMessageKafkaCommand command
    ) {

        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.OUTBOUND_MESSAGE,
                1,
                Instant.now(),
                UUID.randomUUID(),
                command
        );
    }

    private static OutboundMessageKafkaCommand validCommand() {

        return new OutboundMessageKafkaCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "recipient-123",
                MessageType.TEXT,
                "Hello",
                List.of()
        );
    }
}