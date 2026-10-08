package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.SyncConversationHistoryResult;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.ConversationService;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.kafka.SyncConversationHistoryKafkaCommand;
import kit.penny.clientbus.server.service.IMessageProcessingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class KafkaSyncConversationHistoryConsumerTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final String CONVERSATION_EXTERNAL_ID =
            "200";

    private static final String BEFORE_EXTERNAL_ID =
            "105";

    private static final int LIMIT = 50;

    private static final String TELEGRAM_TOPIC =
            KafkaTopicNames.conversationHistoryCommand(
                    ChannelType.TELEGRAM
            );

    @Mock
    private ChannelConnectorRegistry connectorRegistry;

    @Mock
    private IChannelConnector connector;

    @Mock
    private IPlatformMessagePublisher publisher;

    @Mock
    private ConversationService conversationService;

    @Mock
    private IMessageProcessingService messageProcessingService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private MessageService messageService;

    private KafkaSyncConversationHistoryConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaSyncConversationHistoryConsumer(
                connectorRegistry,
                publisher,
                conversationService,
                messageProcessingService,
                messageService,
                kafkaTemplate
        );

    }

    @Test
    void shouldForwardHistoryCommandAndPublishMessages() {
        PlatformMessageRequest first =
                message("101");

        PlatformMessageRequest second =
                message("102");

        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(first, second),
                        false
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        consumer.consume(
                event(),
                TELEGRAM_TOPIC
        );

        verify(connector).handle(
                new SyncConversationHistoryCommand(
                        ACCOUNT_ID,
                        CONVERSATION_EXTERNAL_ID,
                        BEFORE_EXTERNAL_ID,
                        LIMIT
                )
        );

        verify(publisher).publish(first);
        verify(publisher).publish(second);

        verifyNoMoreInteractions(publisher);
        verifyNoInteractions(conversationService);
    }

    @Test
    void shouldSkipNullMessagesReturnedByConnector() {
        PlatformMessageRequest message =
                message("101");

        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        Arrays.asList(message, null),
                        false
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        consumer.consume(
                event(),
                TELEGRAM_TOPIC
        );

        verify(publisher).publish(message);
        verifyNoMoreInteractions(publisher);
        verifyNoInteractions(conversationService);
    }

    @Test
    void shouldNotPublishWhenHistoryContainsNoMessages() {
        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(),
                        false
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        consumer.consume(
                event(),
                TELEGRAM_TOPIC
        );

        verify(connector).handle(
                any(SyncConversationHistoryCommand.class)
        );

        verifyNoInteractions(publisher);
        verifyNoInteractions(conversationService);
    }

    @Test
    void shouldMarkHistoryStartReachedWhenConnectorReportsEndOfHistory() {
        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(),
                        true
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        consumer.consume(
                event(),
                TELEGRAM_TOPIC
        );

        verify(conversationService).markHistoryStartReached(
                ACCOUNT_ID,
                CONVERSATION_EXTERNAL_ID
        );

        verifyNoInteractions(publisher);
    }

    @Test
    void shouldPublishMessagesAndMarkHistoryStartReached() {
        PlatformMessageRequest message =
                message("101");

        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(message),
                        true
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        consumer.consume(
                event(),
                TELEGRAM_TOPIC
        );

        verify(publisher).publish(message);

        verify(conversationService).markHistoryStartReached(
                ACCOUNT_ID,
                CONVERSATION_EXTERNAL_ID
        );
    }

    @Test
    void shouldRejectNullEvent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        null,
                        TELEGRAM_TOPIC
                )
        );

        verifyNoInteractions(
                connectorRegistry,
                publisher,
                conversationService
        );
    }

    @Test
    void shouldRejectWrongEventType() {
        KafkaEvent<SyncConversationHistoryKafkaCommand> wrongEvent =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.PLATFORM_MESSAGE,
                        1,
                        Instant.now(),
                        null,
                        new SyncConversationHistoryKafkaCommand(
                                ACCOUNT_ID,
                                CONVERSATION_EXTERNAL_ID,
                                BEFORE_EXTERNAL_ID,
                                LIMIT
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        wrongEvent,
                        TELEGRAM_TOPIC
                )
        );

        verifyNoInteractions(
                connectorRegistry,
                publisher,
                conversationService
        );
    }

    @Test
    void shouldRejectNullPayload() {
        KafkaEvent<SyncConversationHistoryKafkaCommand> eventWithoutPayload =
                new KafkaEvent<>(
                        UUID.randomUUID(),
                        KafkaEventType.SYNC_CONVERSATION_HISTORY,
                        1,
                        Instant.now(),
                        null,
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(
                        eventWithoutPayload,
                        TELEGRAM_TOPIC
                )
        );

        verifyNoInteractions(
                connectorRegistry,
                publisher,
                conversationService
        );
    }

    @Test
    void shouldProcessMessagesDirectlyForSynchronousHistorySync() {
        UUID correlationId = UUID.randomUUID();

        PlatformMessageRequest first =
                message("101");

        PlatformMessageRequest second =
                message("102");

        MessageDto firstDto = mock(MessageDto.class);
        MessageDto secondDto = mock(MessageDto.class);

        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(first, second),
                        false
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(messageProcessingService.processPlatformMessage(first))
                .thenReturn(firstDto);

        when(messageProcessingService.processPlatformMessage(second))
                .thenReturn(secondDto);

        consumer.consume(
                event(correlationId),
                TELEGRAM_TOPIC
        );

        verify(messageProcessingService)
                .processPlatformMessage(first);

        verify(messageProcessingService)
                .processPlatformMessage(second);

        verifyNoInteractions(publisher);
        verifyNoInteractions(conversationService);
    }

    @Test
    void shouldPublishSynchronousHistoryResultWithSameCorrelationId() {
        UUID correlationId = UUID.randomUUID();

        PlatformMessageRequest message =
                message("101");

        MessageDto messageDto =
                mock(MessageDto.class);

        when(connector.handle(
                any(SyncConversationHistoryCommand.class)
        )).thenReturn(
                new SyncConversationHistoryResult(
                        List.of(message),
                        true
                )
        );

        when(connectorRegistry.getConnector(
                ChannelType.TELEGRAM
        )).thenReturn(connector);

        when(messageProcessingService.processPlatformMessage(message))
                .thenReturn(messageDto);

        consumer.consume(
                event(correlationId),
                TELEGRAM_TOPIC
        );

        ArgumentCaptor<KafkaEvent> captor =
                ArgumentCaptor.forClass(KafkaEvent.class);

        verify(kafkaTemplate).send(
                eq(KafkaTopicNames.conversationHistoryResult()),
                eq(correlationId.toString()),
                captor.capture()
        );

        KafkaEvent resultEvent =
                captor.getValue();

        assertEquals(
                KafkaEventType.SYNC_CONVERSATION_HISTORY_RESULT,
                resultEvent.eventType()
        );

        assertEquals(
                correlationId,
                resultEvent.correlationId()
        );

        assertEquals(
                1,
                resultEvent.schemaVersion()
        );

        assertNotNull(resultEvent.payload());

        verify(messageProcessingService)
                .processPlatformMessage(message);

        verify(conversationService)
                .markHistoryStartReached(
                        ACCOUNT_ID,
                        CONVERSATION_EXTERNAL_ID
                );

        verifyNoInteractions(publisher);
    }

    private static KafkaEvent<SyncConversationHistoryKafkaCommand> event() {
        return event(null);
    }

    private static KafkaEvent<SyncConversationHistoryKafkaCommand> event(
            UUID correlationId
    ) {
        return new KafkaEvent<>(
                UUID.randomUUID(),
                KafkaEventType.SYNC_CONVERSATION_HISTORY,
                1,
                Instant.now(),
                correlationId,
                new SyncConversationHistoryKafkaCommand(
                        ACCOUNT_ID,
                        CONVERSATION_EXTERNAL_ID,
                        BEFORE_EXTERNAL_ID,
                        LIMIT
                )
        );
    }

    private static PlatformMessageRequest message(
            String externalId
    ) {
        return new PlatformMessageRequest(
                ACCOUNT_ID,
                CONVERSATION_EXTERNAL_ID,
                "john_doe",
                null,
                "John Doe",
                CONVERSATION_EXTERNAL_ID,
                externalId,
                MessageType.TEXT,
                "Message " + externalId,
                null,
                Instant.parse(
                        "2026-10-01T10:00:00Z"
                ),
                List.of()
        );
    }
}