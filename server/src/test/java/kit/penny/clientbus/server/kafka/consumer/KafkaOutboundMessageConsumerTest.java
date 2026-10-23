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
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.mapper.OutboundMessageKafkaCommandMapper;
import kit.penny.clientbus.server.service.ChannelAvailabilityService;
import kit.penny.clientbus.server.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaOutboundMessageConsumerTest {

    private static final UUID ATTEMPT_ID = UUID.randomUUID();

    private ChannelConnectorRegistry channelConnectorRegistry;
    private OutboundMessageKafkaCommandMapper commandMapper;
    private MessageService messageService;
    private IChannelConnector connector;

    private ChannelAvailabilityService channelAvailabilityService;


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

        channelAvailabilityService =
                mock(ChannelAvailabilityService.class);

        consumer =
                new KafkaOutboundMessageConsumer(
                        channelConnectorRegistry,
                        commandMapper,
                        messageService,
                        channelAvailabilityService
                );
    }

    @Test
    void shouldRejectAConnectorResultFromAnotherAttemptWithoutReleasingAcceptedSend() {
        var kafkaCommand = validCommand();
        var command = new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(),
                kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);
        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID)).thenReturn(true);
        when(channelConnectorRegistry.getConnector(ChannelType.TELEGRAM)).thenReturn(connector);
        when(commandMapper.toCommand(kafkaCommand)).thenReturn(command);
        when(connector.handle(command)).thenReturn(new ConnectorSendResult("accepted", UUID.randomUUID()));

        assertThatThrownBy(() -> consumer.consume(outboundEvent(kafkaCommand), "clientbus.outbound.telegram"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Connector result send attempt mismatch");

        verify(messageService, never()).registerExternalAttempt(any(), any(), any(), anyString(), anyString());
        verify(messageService, never()).releaseOutboundDeliveryClaim(any(), any());
    }

    @Test
    void shouldClaimSendAndRegisterExternalId() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                        new ConnectorSendResult("telegram-message-123", ATTEMPT_ID)
                );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(messageService)
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

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
                .registerExternalAttempt(kafkaCommand.messageId(), ATTEMPT_ID, kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), "telegram-message-123");

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(any(), any());
    }

    @Test
    void shouldNotCallConnectorWhenDeliveryClaimWasNotAcquired() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
                .thenReturn(false);

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(messageService)
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

        verifyNoInteractions(
                channelConnectorRegistry,
                commandMapper,
                connector
        );

        verify(
                messageService,
                never()
        )
                .registerExternalAttempt(any(), any(), any(), anyString(), anyString());

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(any(), any());
    }

    @Test
    void shouldReleaseClaimAndSkipConnectorWhenChannelIsUnavailable() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(kafkaCommand);

        ChannelUnavailableException exception =
                new ChannelUnavailableException(
                        "Channel is not connected"
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
                .thenReturn(true);

        doThrow(exception)
                .when(channelAvailabilityService)
                .requireConnected(
                        kafkaCommand.channelAccountId()
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isSameAs(exception);

        verify(messageService)
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

        verify(channelAvailabilityService)
                .requireConnected(
                        kafkaCommand.channelAccountId()
                );

        verify(messageService)
                .releaseOutboundDeliveryClaim(kafkaCommand.messageId(), ATTEMPT_ID);

        verifyNoInteractions(
                channelConnectorRegistry,
                commandMapper,
                connector
        );

        verify(
                messageService,
                never()
        )
                .registerExternalAttempt(any(), any(), any(), anyString(), anyString());
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

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

        verify(messageService)
                .releaseOutboundDeliveryClaim(kafkaCommand.messageId(), ATTEMPT_ID);

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

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

        verify(messageService)
                .releaseOutboundDeliveryClaim(kafkaCommand.messageId(), ATTEMPT_ID);

        verifyNoInteractions(connector);
    }

    @Test
    void shouldReleaseClaimWhenConnectorThrows() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Platform unavailable"
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                .claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID);

        verify(connector)
                .handle(command);

        verify(messageService)
                .releaseOutboundDeliveryClaim(kafkaCommand.messageId(), ATTEMPT_ID);

        verify(
                messageService,
                never()
        )
                .registerExternalAttempt(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void shouldNotReleaseClaimWhenExternalIdRegistrationFails() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        RuntimeException exception =
                new RuntimeException(
                        "Database unavailable"
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                        new ConnectorSendResult("telegram-message-456", ATTEMPT_ID)
                );

        doThrow(exception)
                .when(messageService)
                .registerExternalAttempt(kafkaCommand.messageId(), ATTEMPT_ID, kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), "telegram-message-456");

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
                .registerExternalAttempt(kafkaCommand.messageId(), ATTEMPT_ID, kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), "telegram-message-456");

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
                .releaseOutboundDeliveryClaim(any(), any());
    }

    @Test
    void shouldFailWhenConnectorReturnsNull() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                .registerExternalAttempt(any(), any(), any(), anyString(), anyString());

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(any(), any());
    }

    @Test
    void shouldFailWhenConnectorReturnsBlankExternalId() {

        OutboundMessageKafkaCommand kafkaCommand =
                validCommand();

        SendMessageCommand command =
                new SendMessageCommand(kafkaCommand.messageId(), kafkaCommand.channelAccountId(), kafkaCommand.recipientExternalId(), kafkaCommand.type(), kafkaCommand.content(), List.of(), ATTEMPT_ID);

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        kafkaCommand
                );

        when(messageService.claimOutboundDelivery(kafkaCommand.messageId(), ATTEMPT_ID))
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
                        new ConnectorSendResult("   ", ATTEMPT_ID)
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
                .registerExternalAttempt(any(), any(), any(), anyString(), anyString());

        verify(
                messageService,
                never()
        )
                .releaseOutboundDeliveryClaim(any(), any());
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

        return new OutboundMessageKafkaCommand(UUID.randomUUID(), UUID.randomUUID(), "recipient-123", MessageType.TEXT, "Hello", List.of(), ATTEMPT_ID);
    }
}
