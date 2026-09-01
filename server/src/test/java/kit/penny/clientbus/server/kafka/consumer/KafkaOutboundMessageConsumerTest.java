package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformOutboundAttachment;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.kafka.producer.IPlatformEventPublisher;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KafkaOutboundMessageConsumerTest {

    private ChannelConnectorRegistry channelConnectorRegistry;
    private IAttachmentStorage attachmentStorage;
    private IPlatformEventPublisher platformEventPublisher;
    private IChannelConnector connector;

    private KafkaOutboundMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        channelConnectorRegistry =
                mock(ChannelConnectorRegistry.class);

        attachmentStorage =
                mock(IAttachmentStorage.class);

        platformEventPublisher =
                mock(IPlatformEventPublisher.class);

        connector =
                mock(IChannelConnector.class);

        consumer =
                new KafkaOutboundMessageConsumer(
                        channelConnectorRegistry,
                        attachmentStorage,
                        platformEventPublisher
                );
    }

    @Test
    void shouldSendMessageThroughConnector() {

        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-123",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        command,
                        correlationId
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(
                        new ConnectorSendResult(
                                "external-123"
                        )
                );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(channelConnectorRegistry)
                .getConnector(ChannelType.TELEGRAM);

        verify(connector)
                .send(any(ChannelSendRequest.class));

        verify(platformEventPublisher)
                .publish(
                        any(PlatformMessageEvent.class),
                        eq(correlationId)
                );
    }

    @Test
    void shouldMapOutboundCommandToChannelSendRequest() {

        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-456",
                        MessageType.TEXT,
                        "Hello world",
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        command,
                        correlationId
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.VK
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(
                        new ConnectorSendResult(
                                "vk-message-123"
                        )
                );

        consumer.consume(
                event,
                "clientbus.outbound.vk"
        );

        verify(connector).send(
                argThat(request ->
                        request.messageId().equals(messageId)
                                && request.channelAccountId()
                                .equals(channelAccountId)
                                && request.recipientExternalId()
                                .equals("recipient-456")
                                && request.type()
                                == MessageType.TEXT
                                && request.content()
                                .equals("Hello world")
                                && request.attachments().isEmpty()
                )
        );
    }

    @Test
    void shouldLoadAttachmentsFromStorage() {

        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        InputStream content =
                new ByteArrayInputStream(
                        "file-content".getBytes()
                );

        PlatformOutboundAttachment attachment =
                new PlatformOutboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage-key",
                        "image.jpg",
                        "image/jpeg",
                        12L
                );

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-123",
                        MessageType.IMAGE,
                        null,
                        List.of(attachment)
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        command,
                        correlationId
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(attachmentStorage.load("storage-key"))
                .thenReturn(content);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(
                        new ConnectorSendResult(
                                "external-123"
                        )
                );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(attachmentStorage)
                .load("storage-key");

        verify(connector).send(
                argThat(request ->
                        request.attachments().size() == 1
                                && request.attachments()
                                .getFirst()
                                .type()
                                == MessageAttachmentType.IMAGE
                                && request.attachments()
                                .getFirst()
                                .fileName()
                                .equals("image.jpg")
                                && request.attachments()
                                .getFirst()
                                .contentType()
                                .equals("image/jpeg")
                                && request.attachments()
                                .getFirst()
                                .size() == 12L
                                && request.attachments()
                                .getFirst()
                                .content() == content
                )
        );
    }

    @Test
    void shouldPublishSentEventWithConnectorExternalId() {

        UUID channelAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        UUID.randomUUID(),
                        channelAccountId,
                        "recipient-123",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        command,
                        correlationId
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(
                        new ConnectorSendResult(
                                "telegram-external-42"
                        )
                );

        consumer.consume(
                event,
                "clientbus.outbound.telegram"
        );

        verify(platformEventPublisher)
                .publish(
                        argThat(platformEvent ->
                                platformEvent.channelAccountId()
                                        .equals(channelAccountId)
                                        && platformEvent.externalId()
                                        .equals("telegram-external-42")
                                        && platformEvent.type()
                                        == PlatformMessageEventType.SENT
                        ),
                        eq(correlationId)
                );
    }

    @Test
    void shouldFailWhenTopicIsNotOutboundTopic() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand(),
                        UUID.randomUUID()
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
                channelConnectorRegistry,
                attachmentStorage,
                platformEventPublisher
        );
    }

    @Test
    void shouldPropagateConnectorException() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand(),
                        UUID.randomUUID()
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenThrow(
                        new RuntimeException(
                                "Platform unavailable"
                        )
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Platform unavailable");

        verifyNoInteractions(platformEventPublisher);
    }

    @Test
    void shouldFailWhenConnectorReturnsNull() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand(),
                        UUID.randomUUID()
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(null);

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Connector returned null send result"
                );

        verifyNoInteractions(platformEventPublisher);
    }

    @Test
    void shouldFailWhenConnectorReturnsBlankExternalId() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand(),
                        UUID.randomUUID()
                );

        when(channelConnectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(connector.send(any(ChannelSendRequest.class)))
                .thenReturn(
                        new ConnectorSendResult(" ")
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Connector returned blank externalId"
                );

        verifyNoInteractions(platformEventPublisher);
    }

    @Test
    void shouldRejectWrongKafkaEventType() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.INBOUND_MESSAGE,
                        1,
                        Instant.now(),
                        UUID.randomUUID(),
                        validCommand()
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verifyNoInteractions(
                channelConnectorRegistry,
                attachmentStorage,
                platformEventPublisher
        );
    }

    @Test
    void shouldRejectMissingCorrelationId() {

        KafkaEvent<OutboundMessageKafkaCommand> event =
                outboundEvent(
                        validCommand(),
                        null
                );

        assertThatThrownBy(() ->
                consumer.consume(
                        event,
                        "clientbus.outbound.telegram"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verifyNoInteractions(
                channelConnectorRegistry,
                attachmentStorage,
                platformEventPublisher
        );
    }

    private OutboundMessageKafkaCommand validCommand() {
        return new OutboundMessageKafkaCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "recipient-123",
                MessageType.TEXT,
                "Hello",
                List.of()
        );
    }

    private KafkaEvent<OutboundMessageKafkaCommand> outboundEvent(
            OutboundMessageKafkaCommand command,
            UUID correlationId
    ) {
        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.OUTBOUND_MESSAGE,
                1,
                Instant.now(),
                correlationId,
                command
        );
    }
}